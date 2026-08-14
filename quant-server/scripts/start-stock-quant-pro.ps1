[CmdletBinding()]
param(
    [ValidateSet('Start', 'Status', 'Stop', 'Backup')]
    [string] $Action = 'Start',
    [switch] $NoBrowser,
    [ValidateRange(10, 180)]
    [int] $TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$artifact = Join-Path $repoRoot `
    'quant-server\target\quant-server-1.3.1-research-production.jar'
$proof = "$artifact.f1f-b2-proof.properties"
$invokeBroker = Join-Path $PSScriptRoot `
    'host-broker\invoke-stock-quant-host-broker.ps1'
$brokerScript = Join-Path $PSScriptRoot `
    'host-broker\stock-quant-host-broker.ps1'
$protocolModule = Join-Path $PSScriptRoot `
    'host-broker\StockQuantHostBroker.Protocol.psm1'
$taskDefinitionModule = Join-Path $PSScriptRoot `
    'host-broker\StockQuantHostBroker.TaskDefinition.psm1'
$startupSelfHealModule = Join-Path $PSScriptRoot `
    'StockQuantStartupSelfHeal.psm1'

function Get-SystemHealth {
    Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/system/health' `
        -Method Get -TimeoutSec 10
}

function Get-StartupActionRequiredMessage([string] $Reason) {
    switch ($Reason) {
        'HOST_BROKER_TASK_NOT_INSTALLED' {
            return 'Resident Broker is not installed. Repair StockQuantLocalBroker once, then start again.'
        }
        'HOST_BROKER_TASK_DISABLED' {
            return 'Resident Broker watchdog is disabled. Repair StockQuantLocalBroker once, then start again.'
        }
        'HOST_BROKER_TASK_DEFINITION_INVALID' {
            return 'Resident Broker watchdog configuration is invalid. Repair StockQuantLocalBroker once, then start again.'
        }
        'HOST_BROKER_TASK_STATUS_UNAVAILABLE' {
            return 'Windows could not inspect Resident Broker status. Repair StockQuantLocalBroker once, then start again.'
        }
        'HOST_BROKER_WATCHDOG_RECOVERY_TIMEOUT' {
            return 'Resident Broker did not recover within the watchdog timeout. Repair StockQuantLocalBroker once, then start again.'
        }
        'M6_FORMAL_ARTIFACT_MISSING' {
            return 'The formal application build is missing. Run the controlled application update, then start again.'
        }
        'M6_JAVA_17_REQUIRED' {
            return 'Java 17 is required before Stock Quant Pro can start.'
        }
        'M6_DATABASE_NOT_LISTENING' {
            return 'The local research database is not running on 127.0.0.1:38432.'
        }
        'M6_HOST_BROKER_OPERATION_FAILED' {
            return 'Resident Broker could not complete the startup request. Retry Start once after watchdog recovery.'
        }
        'M6_SYSTEM_HEALTH_BLOCKED' {
            return 'Stock Quant Pro started but did not reach a safe READY state.'
        }
        default {
            return 'Stock Quant Pro could not start safely. Use the application repair entry, then start again.'
        }
    }
}

Push-Location $repoRoot
try {
    if ($Action -eq 'Backup') {
        $health = Get-SystemHealth
        if (-not $health.success) { throw 'M6_SYSTEM_HEALTH_BLOCKED' }
        $result = Invoke-RestMethod `
            -Uri 'http://127.0.0.1:8080/api/system/backups' `
            -Method Post -TimeoutSec 120
        if (-not $result.success) { throw 'M6_BACKUP_FAILED' }
        Write-Output "STOCK_QUANT_BACKUP_PATH=$($result.data.archivePath)"
        Write-Output "STOCK_QUANT_BACKUP_SHA256=$($result.data.archiveSha256)"
        Write-Output 'STOCK_QUANT_BACKUP_STATUS=SUCCEEDED'
        exit 0
    }
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath $proof -PathType Leaf)) {
        throw 'M6_FORMAL_ARTIFACT_MISSING'
    }
    if ($Action -eq 'Start') {
        $oldPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $java = @(& java -version 2>&1 | ForEach-Object { [string]$_ })
            $javaExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $oldPreference
        }
        if ($javaExitCode -ne 0 -or ($java -join "`n") -notmatch
                'version "17(?:\.|\")') {
            throw 'M6_JAVA_17_REQUIRED'
        }
        $database = Get-NetTCPConnection -LocalAddress 127.0.0.1 `
            -LocalPort 38432 -State Listen -ErrorAction SilentlyContinue
        if ($null -eq $database) { throw 'M6_DATABASE_NOT_LISTENING' }

        Import-Module $protocolModule -Force -ErrorAction Stop
        Import-Module $startupSelfHealModule -Force -ErrorAction Stop
        $expectedHead = (git rev-parse HEAD).Trim()
        Write-Output 'STOCK_QUANT_STARTUP_STAGE=CHECKING_RESIDENT_BROKER'
        $brokerWait = Wait-StockQuantHostBrokerRecovery `
            -TimeoutMilliseconds ($TimeoutSeconds * 1000) `
            -PollMilliseconds 1000 -HeartbeatProbe {
                Read-StockQuantHostBrokerHeartbeat `
                    -ExpectedGitCommit $expectedHead `
                    -AllowAncestorGitCommit
            }
        if ($brokerWait.Status -eq 'TIMEOUT') {
            $task = $null
            $taskQuerySucceeded = $true
            $taskDefinitionValid = $true
            try {
                Import-Module ScheduledTasks -ErrorAction Stop
                $task = Get-ScheduledTask -TaskName 'StockQuantLocalBroker' `
                    -ErrorAction SilentlyContinue
                if ($null -ne $task -and
                    [string]$task.State -cne 'Disabled') {
                    try {
                        Import-Module $taskDefinitionModule -Force `
                            -ErrorAction Stop
                        $powershellExe = Join-Path $env:SystemRoot `
                            'System32\WindowsPowerShell\v1.0\powershell.exe'
                        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
                        Assert-StockQuantHostBrokerTaskDefinition `
                            -Task $task `
                            -ExpectedPowerShellExecutable $powershellExe `
                            -ExpectedBrokerScript $brokerScript `
                            -ExpectedWorkingDirectory $repoRoot `
                            -ExpectedUserSid $identity.User.Value `
                            -ExpectedTaskName 'StockQuantLocalBroker'
                    } catch {
                        $taskDefinitionValid = $false
                    }
                }
            } catch {
                $taskQuerySucceeded = $false
            }
            $reason = Resolve-StockQuantHostBrokerRecoveryFailure `
                -TaskQuerySucceeded $taskQuerySucceeded -Task $task `
                -TaskDefinitionValid $taskDefinitionValid
            throw "STOCK_QUANT_STARTUP_ACTION_REQUIRED:$reason"
        }
        Write-Output "STOCK_QUANT_STARTUP_BROKER=$($brokerWait.Status)"
    }
    $operation = switch ($Action) {
        'Start' { 'START_RESEARCH_PRODUCTION' }
        'Stop' { 'STOP_RESEARCH_PRODUCTION' }
        'Status' { 'CHECK_RESEARCH_PRODUCTION_STATUS' }
    }
    $output = @(& $invokeBroker -Operation $operation `
        -ArtifactPath $artifact -TimeoutSeconds $TimeoutSeconds 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) {
        throw 'M6_HOST_BROKER_OPERATION_FAILED'
    }
    if ($Action -eq 'Stop') {
        Write-Output 'STOCK_QUANT_PRODUCTION_STATUS=STOPPED'
        exit 0
    }
    $health = Get-SystemHealth
    if (-not $health.success -or $health.data.status -eq 'BLOCKED' -or
        $health.data.gitCommit -ne (git rev-parse HEAD).Trim()) {
        throw 'M6_SYSTEM_HEALTH_BLOCKED'
    }
    Write-Output "STOCK_QUANT_PRODUCTION_HEAD=$($health.data.gitCommit)"
    Write-Output "STOCK_QUANT_PRODUCTION_HEALTH=$($health.data.status)"
    Write-Output "STOCK_QUANT_SCHEDULER_STATE=$($health.data.scheduler.state)"
    Write-Output "STOCK_QUANT_NEXT_SHADOW=$($health.data.scheduler.nextPlannedAt)"
    Write-Output 'STOCK_QUANT_REAL_TRADING=false'
    if ($Action -eq 'Start' -and -not $NoBrowser) {
        Start-Process 'http://127.0.0.1:8080/'
    }
    Write-Output 'STOCK_QUANT_PRODUCTION_STATUS=RUNNING'
} catch {
    $failure = [string]$_.Exception.Message
    if ($failure.StartsWith('STOCK_QUANT_STARTUP_ACTION_REQUIRED:',
            [StringComparison]::Ordinal)) {
        $reason = $failure.Substring(
            'STOCK_QUANT_STARTUP_ACTION_REQUIRED:'.Length)
    } elseif ($failure -in @(
            'M6_FORMAL_ARTIFACT_MISSING', 'M6_JAVA_17_REQUIRED',
            'M6_DATABASE_NOT_LISTENING',
            'M6_HOST_BROKER_OPERATION_FAILED',
            'M6_SYSTEM_HEALTH_BLOCKED')) {
        $reason = $failure
    } else {
        $reason = 'STOCK_QUANT_STARTUP_UNEXPECTED_FAILURE'
    }
    Write-Output 'STOCK_QUANT_PRODUCTION_STATUS=ACTION_REQUIRED'
    Write-Output "STOCK_QUANT_STARTUP_REASON=$reason"
    Write-Output ("STOCK_QUANT_STARTUP_MESSAGE=" +
        (Get-StartupActionRequiredMessage -Reason $reason))
    exit 20
} finally {
    Pop-Location
}
