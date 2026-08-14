[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$module = Join-Path $PSScriptRoot 'StockQuantStartupSelfHeal.psm1'
$launcher = Join-Path $PSScriptRoot 'start-stock-quant-pro.ps1'
Import-Module $module -Force -ErrorAction Stop
$tests = 0

function Assert-Equal([object] $Expected, [object] $Actual,
        [string] $FailureCode) {
    if ($Expected -cne $Actual) { throw $FailureCode }
}

$script:probeAttempts = 0
$healthy = Wait-StockQuantHostBrokerRecovery `
    -TimeoutMilliseconds 50 -PollMilliseconds 1 -HeartbeatProbe {
        $script:probeAttempts++
        [pscustomobject]@{ state = 'IDLE' }
    } -SleepAction { param($Milliseconds) }
Assert-Equal 'HEALTHY' $healthy.Status `
    'STARTUP_SELF_HEAL_IMMEDIATE_STATUS_FAILED'
Assert-Equal 1 $healthy.Attempts `
    'STARTUP_SELF_HEAL_IMMEDIATE_ATTEMPTS_FAILED'
$tests++

$script:probeAttempts = 0
$recovered = Wait-StockQuantHostBrokerRecovery `
    -TimeoutMilliseconds 100 -PollMilliseconds 1 -HeartbeatProbe {
        $script:probeAttempts++
        if ($script:probeAttempts -lt 3) { throw 'HOST_BROKER_NOT_RUNNING' }
        [pscustomobject]@{ state = 'IDLE' }
    } -SleepAction { param($Milliseconds) }
Assert-Equal 'RECOVERED' $recovered.Status `
    'STARTUP_SELF_HEAL_RECOVERY_STATUS_FAILED'
Assert-Equal 3 $recovered.Attempts `
    'STARTUP_SELF_HEAL_RECOVERY_ATTEMPTS_FAILED'
$tests++

function Invoke-ClosureBoundRecoveryProbe {
    $expectedMarker = 'V1_0_2_EXPECTED_HEAD'
    $probeState = [pscustomobject]@{ Samples = 0 }
    function Read-ClosureBoundTestHeartbeat([string] $Marker) {
        if ($Marker -cne 'V1_0_2_EXPECTED_HEAD') {
            throw 'STARTUP_SELF_HEAL_CLOSURE_VALUE_LOST'
        }
        [pscustomobject]@{ state = 'IDLE' }
    }
    $readHeartbeat = Get-Command 'Read-ClosureBoundTestHeartbeat' `
        -CommandType Function -ErrorAction Stop
    $probe = {
        $heartbeat = & $readHeartbeat -Marker $expectedMarker
        $probeState.Samples++
        if ($probeState.Samples -lt 2) { throw 'HOST_BROKER_NOT_RUNNING' }
        $heartbeat
    }.GetNewClosure()
    Wait-StockQuantHostBrokerRecovery `
        -TimeoutMilliseconds 100 -PollMilliseconds 1 `
        -HeartbeatProbe $probe -SleepAction { param($Milliseconds) }
}

$closureBound = Invoke-ClosureBoundRecoveryProbe
Assert-Equal 'RECOVERED' $closureBound.Status `
    'STARTUP_SELF_HEAL_CLOSURE_RECOVERY_FAILED'
Assert-Equal 2 $closureBound.Attempts `
    'STARTUP_SELF_HEAL_CLOSURE_ATTEMPTS_FAILED'
$tests++

$timedOut = Wait-StockQuantHostBrokerRecovery `
    -TimeoutMilliseconds 15 -PollMilliseconds 1 -HeartbeatProbe {
        throw 'HOST_BROKER_NOT_RUNNING'
    } -SleepAction { param($Milliseconds) }
Assert-Equal 'TIMEOUT' $timedOut.Status `
    'STARTUP_SELF_HEAL_TIMEOUT_STATUS_FAILED'
if ($timedOut.Attempts -lt 1) {
    throw 'STARTUP_SELF_HEAL_TIMEOUT_ATTEMPTS_FAILED'
}
$tests++

try {
    Wait-StockQuantHostBrokerRecovery `
        -TimeoutMilliseconds 20 -PollMilliseconds 1 -HeartbeatProbe {
            throw 'UNEXPECTED_HEARTBEAT_FAILURE'
        } -SleepAction { param($Milliseconds) } | Out-Null
    throw 'STARTUP_SELF_HEAL_UNEXPECTED_ERROR_NOT_PROPAGATED'
} catch {
    if ($_.Exception.Message -cne 'UNEXPECTED_HEARTBEAT_FAILURE') { throw }
}
$tests++

Assert-Equal 'HOST_BROKER_TASK_STATUS_UNAVAILABLE' `
    (Resolve-StockQuantHostBrokerRecoveryFailure `
        -TaskQuerySucceeded $false -Task $null) `
    'STARTUP_SELF_HEAL_QUERY_CLASSIFICATION_FAILED'
$tests++
Assert-Equal 'HOST_BROKER_TASK_NOT_INSTALLED' `
    (Resolve-StockQuantHostBrokerRecoveryFailure `
        -TaskQuerySucceeded $true -Task $null) `
    'STARTUP_SELF_HEAL_MISSING_CLASSIFICATION_FAILED'
$tests++
Assert-Equal 'HOST_BROKER_TASK_DISABLED' `
    (Resolve-StockQuantHostBrokerRecoveryFailure `
        -TaskQuerySucceeded $true `
        -Task ([pscustomobject]@{ State = 'Disabled' })) `
    'STARTUP_SELF_HEAL_DISABLED_CLASSIFICATION_FAILED'
$tests++
Assert-Equal 'HOST_BROKER_TASK_DEFINITION_INVALID' `
    (Resolve-StockQuantHostBrokerRecoveryFailure `
        -TaskQuerySucceeded $true `
        -Task ([pscustomobject]@{ State = 'Ready' }) `
        -TaskDefinitionValid $false) `
    'STARTUP_SELF_HEAL_DEFINITION_CLASSIFICATION_FAILED'
$tests++
Assert-Equal 'HOST_BROKER_WATCHDOG_RECOVERY_TIMEOUT' `
    (Resolve-StockQuantHostBrokerRecoveryFailure `
        -TaskQuerySucceeded $true `
        -Task ([pscustomobject]@{ State = 'Ready' })) `
    'STARTUP_SELF_HEAL_TIMEOUT_CLASSIFICATION_FAILED'
$tests++

$launcherText = Get-Content -LiteralPath $launcher -Raw
if ($launcherText -notmatch 'Wait-StockQuantHostBrokerRecovery' -or
    $launcherText -notmatch 'Read-StockQuantHostBrokerHeartbeat' -or
    $launcherText -notmatch 'CHECKING_RESIDENT_BROKER' -or
    $launcherText -notmatch 'STOCK_QUANT_STARTUP_BROKER=') {
    throw 'STARTUP_SELF_HEAL_LAUNCHER_WAIT_CONTRACT_FAILED'
}
$tests++
if ($launcherText -notmatch '\[ValidateRange\(10, 900\)\]' -or
    $launcherText -notmatch '\[int\] \$TimeoutSeconds = 600') {
    throw 'STARTUP_SELF_HEAL_WATCHDOG_GRACE_WINDOW_FAILED'
}
$tests++
if ($launcherText -notmatch 'ConsecutiveSamples' -or
    $launcherText -notmatch 'Get-Process -Id \$candidateProcessId' -or
    $launcherText -notmatch 'ConsecutiveSamples -lt 2' -or
    $launcherText -notmatch '\$readBrokerHeartbeat = Get-Command' -or
    $launcherText -notmatch '& \$readBrokerHeartbeat' -or
    $launcherText -notmatch '\.GetNewClosure\(\)' -or
    $launcherText -notmatch '-HeartbeatProbe \$heartbeatProbe') {
    throw 'STARTUP_SELF_HEAL_STABLE_PROCESS_PROBE_FAILED'
}
$tests++
if ($launcherText -notmatch 'Get-ScheduledTask' -or
    $launcherText -notmatch 'Assert-StockQuantHostBrokerTaskDefinition' -or
    $launcherText -notmatch 'StockQuantLocalBroker') {
    throw 'STARTUP_SELF_HEAL_TASK_DIAGNOSIS_CONTRACT_FAILED'
}
$tests++
if ($launcherText -notmatch
        'STOCK_QUANT_PRODUCTION_STATUS=ACTION_REQUIRED' -or
    $launcherText -notmatch 'STOCK_QUANT_STARTUP_REASON=' -or
    $launcherText -notmatch 'STOCK_QUANT_STARTUP_MESSAGE=') {
    throw 'STARTUP_SELF_HEAL_ACTION_REQUIRED_CONTRACT_FAILED'
}
$tests++
if ($launcherText -match '(?i)schtasks' -or
    $launcherText -match 'Start-ScheduledTask' -or
    $launcherText -match 'DB_PASSWORD' -or
    $launcherText -match 'TUSHARE_TOKEN' -or
    $launcherText -match 'Write-Error') {
    throw 'STARTUP_SELF_HEAL_SAFETY_CONTRACT_FAILED'
}
$tests++

Write-Output "STARTUP_SELF_HEAL_TESTS=$tests"
Write-Output 'STARTUP_SELF_HEAL_SCHEDULED_TASK_TRIGGERS=0'
Write-Output 'STARTUP_SELF_HEAL_CREDENTIAL_READS=0'
Write-Output 'STARTUP_SELF_HEAL_PROVIDER_CALLS=0'
Write-Output 'STARTUP_SELF_HEAL_PERMANENT_DATABASE_WRITES=0'
Write-Output 'STARTUP_SELF_HEAL_STATUS=PASS'
