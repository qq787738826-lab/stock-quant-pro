[CmdletBinding(
    SupportsShouldProcess = $true,
    ConfirmImpact = 'High',
    DefaultParameterSetName = 'Install'
)]
param(
    [Parameter(ParameterSetName = 'Uninstall', Mandatory = $true)]
    [switch] $Uninstall
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$taskName = 'StockQuantLocalBroker'
$repoRoot = (Resolve-Path -LiteralPath (
    Join-Path $PSScriptRoot '..\..\..')).Path
$brokerScriptCandidate = Join-Path $PSScriptRoot `
    'stock-quant-host-broker.ps1'
$taskDefinitionModule = Join-Path $PSScriptRoot `
    'StockQuantHostBroker.TaskDefinition.psm1'
$protocolModule = Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1'
$credentialStatusScript = Join-Path $repoRoot `
    'quant-server\scripts\set-stock-quant-secrets.ps1'
$powershellExe = Join-Path $env:SystemRoot `
    'System32\WindowsPowerShell\v1.0\powershell.exe'
$hostIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$hostPrincipal = [Security.Principal.WindowsPrincipal]::new($hostIdentity)
$isAdministrator = $hostPrincipal.IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)

if ($hostIdentity.Name -match '(?i)CodexSandbox') {
    throw 'STOCK_QUANT_HOST_BROKER_REAL_USER_REQUIRED'
}
if (-not (Test-Path -LiteralPath $brokerScriptCandidate -PathType Leaf) -or
    -not (Test-Path -LiteralPath $taskDefinitionModule -PathType Leaf) -or
    -not (Test-Path -LiteralPath $protocolModule -PathType Leaf) -or
    -not (Test-Path -LiteralPath $credentialStatusScript -PathType Leaf) -or
    -not (Test-Path -LiteralPath $powershellExe -PathType Leaf)) {
    throw 'STOCK_QUANT_HOST_BROKER_INSTALL_PREREQUISITE_MISSING'
}
$brokerScript = (Resolve-Path -LiteralPath $brokerScriptCandidate).Path
if (-not $isAdministrator -and -not $WhatIfPreference) {
    throw 'STOCK_QUANT_HOST_BROKER_ADMINISTRATOR_REQUIRED'
}

Import-Module ScheduledTasks -ErrorAction Stop
Import-Module $taskDefinitionModule -Force -ErrorAction Stop
Import-Module $protocolModule -Force -ErrorAction Stop
$brokerPaths = Get-StockQuantHostBrokerPaths

if ($Uninstall) {
    Write-Output "STOCK_QUANT_HOST_BROKER_TASK=$taskName"
    Write-Output "STOCK_QUANT_HOST_BROKER_FIXED_SCRIPT=$brokerScript"
    Write-Output "STOCK_QUANT_HOST_BROKER_ACCOUNT=$($hostIdentity.Name)"
    Write-Output "STOCK_QUANT_HOST_BROKER_ADMINISTRATOR=$isAdministrator"
    if ($PSCmdlet.ShouldProcess($taskName, 'Uninstall fixed host broker')) {
        $existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($null -ne $existing) {
            Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
            Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        }
        if (Test-Path -LiteralPath $brokerPaths.Heartbeat -PathType Leaf) {
            Remove-Item -LiteralPath $brokerPaths.Heartbeat -Force
        }
        Write-Output 'STOCK_QUANT_HOST_BROKER_UNINSTALLED=true'
    }
    exit 0
}

$credentialStatus = @(& $credentialStatusScript -Status 2>&1)
$databaseCredentialPresent = $credentialStatus -contains `
    'StockQuant/ResearchDbPassword=PRESENT'
$tushareCredentialPresent = $credentialStatus -contains `
    'StockQuant/TushareToken=PRESENT'
Write-Output ('StockQuant/ResearchDbPassword=' +
    $(if ($databaseCredentialPresent) { 'PRESENT' } else { 'MISSING' }))
Write-Output ('StockQuant/TushareToken=' +
    $(if ($tushareCredentialPresent) { 'PRESENT' } else { 'MISSING' }))
if ($LASTEXITCODE -ne 0 -or $credentialStatus.Count -ne 3 -or
    -not $databaseCredentialPresent -or -not $tushareCredentialPresent -or
    $credentialStatus -notcontains 'STOCK_QUANT_CREDENTIALS_READY=True') {
    throw 'STOCK_QUANT_HOST_BROKER_CREDENTIALS_MISSING'
}

Write-Output "STOCK_QUANT_HOST_BROKER_TASK=$taskName"
Write-Output "STOCK_QUANT_HOST_BROKER_FIXED_SCRIPT=$brokerScript"
Write-Output "STOCK_QUANT_HOST_BROKER_ACCOUNT=$($hostIdentity.Name)"
Write-Output "STOCK_QUANT_HOST_BROKER_ADMINISTRATOR=$isAdministrator"
Write-Output 'STOCK_QUANT_HOST_BROKER_ADMINISTRATOR_REQUIRED=true'
Write-Output 'STOCK_QUANT_HOST_BROKER_LOGON_TYPE=Interactive'
Write-Output 'STOCK_QUANT_HOST_BROKER_RUN_LEVEL=Limited'
Write-Output 'STOCK_QUANT_HOST_BROKER_TRIGGERS=2'
Write-Output 'STOCK_QUANT_HOST_BROKER_TRIGGER_1=AT_LOGON_CURRENT_USER'
Write-Output 'STOCK_QUANT_HOST_BROKER_TRIGGER_2=WATCHDOG_EVERY_1_MINUTE'
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_INTERVAL=PT1M'
Write-Output 'STOCK_QUANT_HOST_BROKER_MULTIPLE_INSTANCES=IgnoreNew'
Write-Output 'STOCK_QUANT_HOST_BROKER_AUTOSTART=true'
Write-Output 'STOCK_QUANT_HOST_BROKER_PROVIDER_AUTOSTART=false'
Write-Output 'STOCK_QUANT_HOST_BROKER_CREDENTIALS_READY=true'
Write-Output 'STOCK_QUANT_HOST_BROKER_CODEX_CLI_REQUIRED=false'

$definition = New-StockQuantHostBrokerTaskDefinition `
    -PowerShellExecutable $powershellExe -BrokerScript $brokerScript `
    -WorkingDirectory $repoRoot -UserId $hostIdentity.Name
Assert-StockQuantHostBrokerTaskDefinition -Task $definition `
    -ExpectedPowerShellExecutable $powershellExe `
    -ExpectedBrokerScript $brokerScript `
    -ExpectedWorkingDirectory $repoRoot `
    -ExpectedUserSid $hostIdentity.User.Value
$existingBeforeInstall = Get-ScheduledTask -TaskName $taskName `
    -ErrorAction SilentlyContinue
$existingXml = $null
$existingWasRunning = $false
if ($null -ne $existingBeforeInstall) {
    Assert-StockQuantHostBrokerTaskDefinition -Task $existingBeforeInstall `
        -ExpectedPowerShellExecutable $powershellExe `
        -ExpectedBrokerScript $brokerScript `
        -ExpectedWorkingDirectory $repoRoot `
        -ExpectedUserSid $hostIdentity.User.Value `
        -ExpectedTaskName $taskName -AllowLegacyOnDemand
    $existingXml = [string](Export-ScheduledTask -TaskName $taskName)
    $existingWasRunning = [string]$existingBeforeInstall.State -eq 'Running'
}
Write-Output ("STOCK_QUANT_HOST_BROKER_EXISTING_TASK=" +
    $(if ($null -ne $existingBeforeInstall) { 'VALID' } else { 'ABSENT' }))

$expectedGitCommit = @(& git -C $repoRoot rev-parse HEAD 2>&1 |
    ForEach-Object { [string]$_ })
if ($LASTEXITCODE -ne 0 -or $expectedGitCommit.Count -ne 1 -or
    $expectedGitCommit[0] -notmatch '^[0-9a-f]{40}$') {
    throw 'STOCK_QUANT_HOST_BROKER_GIT_BINDING_INVALID'
}
$expectedGitCommit = $expectedGitCommit[0]
$claimableRequests = @(Get-ChildItem -LiteralPath $brokerPaths.Requests `
    -File -Filter 'SQHB_*.request.properties' -ErrorAction SilentlyContinue)
if ($claimableRequests.Count -ne 0) {
    throw 'STOCK_QUANT_HOST_BROKER_INSTALL_QUEUE_NOT_EMPTY'
}

if ($PSCmdlet.ShouldProcess($taskName, 'Install or update fixed host broker')) {
    try {
        if ($null -ne $existingBeforeInstall) {
            Stop-ScheduledTask -TaskName $taskName `
                -ErrorAction SilentlyContinue
        }
        $transaction = Invoke-StockQuantHostBrokerTaskRegistrationTransaction `
            -TaskName $taskName -Definition $definition `
            -ExpectedPowerShellExecutable $powershellExe `
            -ExpectedBrokerScript $brokerScript `
            -ExpectedWorkingDirectory $repoRoot `
            -ExpectedUserSid $hostIdentity.User.Value
        if (Test-Path -LiteralPath $brokerPaths.Heartbeat -PathType Leaf) {
            Remove-Item -LiteralPath $brokerPaths.Heartbeat -Force
        }
        Start-ScheduledTask -TaskName $taskName -ErrorAction Stop
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds(20)
        $heartbeat = $null
        while ($null -eq $heartbeat -and
            [DateTimeOffset]::UtcNow -lt $deadline) {
            try {
                $heartbeat = Read-StockQuantHostBrokerHeartbeat `
                    -ExpectedGitCommit $expectedGitCommit
            } catch {
                Start-Sleep -Milliseconds 250
            }
        }
        $installedTask = Get-ScheduledTask -TaskName $taskName `
            -ErrorAction Stop
        if ($null -eq $heartbeat -or
            [string]$heartbeat.windowsUser -cne $hostIdentity.Name -or
            [string]$heartbeat.state -cne 'IDLE' -or
            [string]$installedTask.State -cne 'Running') {
            throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_START_FAILED'
        }
        Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALLED=true'
        Write-Output "STOCK_QUANT_HOST_BROKER_CREATED=$($transaction.Created)"
        Write-Output "STOCK_QUANT_HOST_BROKER_UPDATED=$($transaction.Updated)"
        Write-Output 'STOCK_QUANT_HOST_BROKER_PASSWORD_STORED_IN_TASK=false'
        Write-Output 'STOCK_QUANT_HOST_BROKER_PROVIDER_AUTOSTART=false'
        Write-Output 'STOCK_QUANT_HOST_BROKER_RESIDENT_HEARTBEAT=PASS'
    } catch {
        $installError = $_
        try {
            Stop-ScheduledTask -TaskName $taskName `
                -ErrorAction SilentlyContinue
            if (Test-Path -LiteralPath $brokerPaths.Heartbeat -PathType Leaf) {
                Remove-Item -LiteralPath $brokerPaths.Heartbeat -Force
            }
            if ($null -eq $existingXml) {
                if ($null -ne (Get-ScheduledTask -TaskName $taskName `
                        -ErrorAction SilentlyContinue)) {
                    Unregister-ScheduledTask -TaskName $taskName `
                        -Confirm:$false -ErrorAction Stop
                }
            } else {
                Register-ScheduledTask -TaskName $taskName `
                    -Xml $existingXml -Force | Out-Null
                $restored = Get-ScheduledTask -TaskName $taskName `
                    -ErrorAction Stop
                Assert-StockQuantHostBrokerTaskDefinition -Task $restored `
                    -ExpectedPowerShellExecutable $powershellExe `
                    -ExpectedBrokerScript $brokerScript `
                    -ExpectedWorkingDirectory $repoRoot `
                    -ExpectedUserSid $hostIdentity.User.Value `
                    -ExpectedTaskName $taskName -AllowLegacyOnDemand
                $restoredXml = [string](Export-ScheduledTask `
                    -TaskName $taskName)
                $beforeDocument = [Xml.XmlDocument]::new()
                $beforeDocument.PreserveWhitespace = $false
                $beforeDocument.LoadXml($existingXml)
                $afterDocument = [Xml.XmlDocument]::new()
                $afterDocument.PreserveWhitespace = $false
                $afterDocument.LoadXml($restoredXml)
                if ($beforeDocument.OuterXml -cne $afterDocument.OuterXml) {
                    throw 'RESTORE_MISMATCH'
                }
                if ($existingWasRunning) {
                    Start-ScheduledTask -TaskName $taskName -ErrorAction Stop
                }
            }
        } catch {
            throw 'STOCK_QUANT_HOST_BROKER_TASK_ROLLBACK_FAILED'
        }
        throw $installError
    }
} else {
    Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALL_PREFLIGHT=PASS'
    Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALLED=false'
}
