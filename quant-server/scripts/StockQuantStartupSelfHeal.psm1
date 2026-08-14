Set-StrictMode -Version Latest

function Wait-StockQuantHostBrokerRecovery {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock] $HeartbeatProbe,
        [ValidateRange(1, 900000)]
        [int] $TimeoutMilliseconds = 120000,
        [ValidateRange(1, 5000)]
        [int] $PollMilliseconds = 1000,
        [scriptblock] $SleepAction
    )

    $deadline = [DateTimeOffset]::UtcNow.AddMilliseconds(
        $TimeoutMilliseconds)
    $attempts = 0
    while ($true) {
        $attempts++
        try {
            $heartbeat = & $HeartbeatProbe
            if ($null -ne $heartbeat) {
                $status = if ($attempts -eq 1) { 'HEALTHY' } else { 'RECOVERED' }
                return [pscustomobject]@{
                    Status = $status
                    Attempts = $attempts
                    Heartbeat = $heartbeat
                }
            }
        } catch {
            if ($_.Exception.Message -cne 'HOST_BROKER_NOT_RUNNING') {
                throw
            }
        }

        $remaining = $deadline - [DateTimeOffset]::UtcNow
        if ($remaining.TotalMilliseconds -le 0) { break }
        $delay = [Math]::Min($PollMilliseconds,
            [Math]::Max(1, [int][Math]::Ceiling($remaining.TotalMilliseconds)))
        if ($null -eq $SleepAction) {
            Start-Sleep -Milliseconds $delay
        } else {
            & $SleepAction $delay
        }
    }

    return [pscustomobject]@{
        Status = 'TIMEOUT'
        Attempts = $attempts
        Heartbeat = $null
    }
}

function Resolve-StockQuantHostBrokerRecoveryFailure {
    [CmdletBinding()]
    param(
        [bool] $TaskQuerySucceeded,
        [AllowNull()]
        [object] $Task,
        [bool] $TaskDefinitionValid = $true
    )

    if (-not $TaskQuerySucceeded) {
        return 'HOST_BROKER_TASK_STATUS_UNAVAILABLE'
    }
    if ($null -eq $Task) {
        return 'HOST_BROKER_TASK_NOT_INSTALLED'
    }
    if ([string]$Task.State -ceq 'Disabled') {
        return 'HOST_BROKER_TASK_DISABLED'
    }
    if (-not $TaskDefinitionValid) {
        return 'HOST_BROKER_TASK_DEFINITION_INVALID'
    }
    return 'HOST_BROKER_WATCHDOG_RECOVERY_TIMEOUT'
}

Export-ModuleMember -Function @(
    'Wait-StockQuantHostBrokerRecovery'
    'Resolve-StockQuantHostBrokerRecoveryFailure'
)
