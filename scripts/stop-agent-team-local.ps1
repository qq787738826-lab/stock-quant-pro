$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'agent-team-local-common.ps1')

$repositoryRoot = Get-AgentTeamRepositoryRoot
$runtimeState = $null
$trustedLogDirectory = $null
$runtimeLock = $null
try { $runtimeLock = Enter-AgentTeamRuntimeMutex 10 }
catch {
    Write-Output 'FAILURE_STAGE=RUNTIME_LOCK'
    Write-Output 'FAILURE_REASON=The agent team runtime lock could not be acquired.'
    Write-Output 'CLEANUP_SUCCEEDED=False'
    exit 1
}
try {
try {
    $runtimeState = Read-AgentTeamRuntimeState
} catch {
    Write-Output 'FAILURE_STAGE=STATE_READ'
    Write-Output 'FAILURE_REASON=Runtime state could not be read or parsed.'
    Write-Output 'CLEANUP_SUCCEEDED=False'
    Exit-AgentTeamRuntimeMutex $runtimeLock
    exit 1
}
if (-not $runtimeState) {
    Write-Output 'No agent team services are managed by the local runtime.'
    Exit-AgentTeamRuntimeMutex $runtimeLock
    exit 0
}

try { Assert-AgentTeamRuntimeState $runtimeState $repositoryRoot }
catch {
    Write-Output 'FAILURE_STAGE=STOP_PREFLIGHT'
    Write-Output 'FAILURE_REASON=Runtime state structure validation failed.'
    Write-Output 'CLEANUP_SUCCEEDED=False'
    Exit-AgentTeamRuntimeMutex $runtimeLock
    exit 1
}
$trustedLogDirectory = [string]$runtimeState.logDirectory
try {
    $stopPlans = @{}
    foreach ($service in @(
            $runtimeState.services.python,
            $runtimeState.services.java,
            $runtimeState.services.vue)) {
        $stopPlans[$service.name] = New-ManagedServiceStopPlan $service
    }
} catch {
    Write-Output 'FAILURE_STAGE=STOP_PREFLIGHT'
    Write-Output ('FAILURE_REASON=' + $_.Exception.Message)
    if ($trustedLogDirectory) {
        Write-Output ('FAILURE_LOG_DIRECTORY=' + $trustedLogDirectory)
    }
    Write-Output 'CLEANUP_SUCCEEDED=False'
    Exit-AgentTeamRuntimeMutex $runtimeLock
    exit 1
}

try {
    Assert-AllManagedStopPlansCurrent @(
        $stopPlans['python'],
        $stopPlans['java'],
        $stopPlans['vue'])
    foreach ($serviceName in @('vue','java','python')) {
        $result = Invoke-ManagedServiceStopPlan $stopPlans[$serviceName]
        Write-Output ($serviceName.ToUpper() + '_STOP_RESULT=' + $result)
    }

    foreach ($service in @(
            $runtimeState.services.python,
            $runtimeState.services.java,
            $runtimeState.services.vue)) {
        if (Get-Process -Id ([int]$service.rootProcessId) -ErrorAction SilentlyContinue) {
            throw ('Managed root remains: ' + $service.name)
        }
        if (Get-Process -Id ([int]$service.listenerProcessId) -ErrorAction SilentlyContinue) {
            throw ('Managed listener remains: ' + $service.name)
        }
        if (Get-PortListener ([int]$service.port)) {
            throw ('Fixed port remains occupied: ' + $service.port)
        }
    }

} catch {
    Write-Output 'FAILURE_STAGE=STOP_EXECUTION'
    Write-Output ('FAILURE_REASON=' + $_.Exception.Message)
    Write-Output ('FAILURE_LOG_DIRECTORY=' + $trustedLogDirectory)
    Write-Output 'CLEANUP_SUCCEEDED=False'
    Exit-AgentTeamRuntimeMutex $runtimeLock
    exit 1
}

try {
    Remove-AgentTeamRuntimeState ([guid]$runtimeState.runId) $repositoryRoot $runtimeLock
    Write-Output ('LOG_DIRECTORY=' + $trustedLogDirectory)
    Write-Output 'STATE_REMOVED=True'
    Write-Output 'CLEANUP_SUCCEEDED=True'
} catch {
    Write-Output 'FAILURE_STAGE=STATE_REMOVE'
    Write-Output ('FAILURE_REASON=' + $_.Exception.Message)
    Write-Output ('FAILURE_LOG_DIRECTORY=' + $trustedLogDirectory)
    Write-Output 'CLEANUP_SUCCEEDED=False'
    Exit-AgentTeamRuntimeMutex $runtimeLock
    exit 1
}
Exit-AgentTeamRuntimeMutex $runtimeLock
} finally {
    if ($runtimeLock -and $runtimeLock.Acquired) { Exit-AgentTeamRuntimeMutex $runtimeLock }
}
