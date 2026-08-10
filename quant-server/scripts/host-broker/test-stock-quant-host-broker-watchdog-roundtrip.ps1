[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedCommit
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module ScheduledTasks -ErrorAction Stop
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.TaskDefinition.psm1') -Force -ErrorAction Stop

$formalTaskName = 'StockQuantLocalBroker'
$probeTaskName = 'StockQuantHostBrokerRoundTrip_' +
    [Guid]::NewGuid().ToString('N').ToUpperInvariant()
$repoRoot = (Resolve-Path -LiteralPath (
    Join-Path $PSScriptRoot '..\..\..')).Path
$targetRoot = Join-Path $repoRoot 'quant-server\target'
$probePrefix = 'stock-quant-host-broker-watchdog-'
$probeRoot = Join-Path $targetRoot `
    ($probePrefix + [Guid]::NewGuid().ToString('N'))
$probeScript = Join-Path $probeRoot 'watchdog-probe-broker.ps1'
$heartbeatPath = Join-Path $probeRoot 'heartbeat.json'
$startLogPath = Join-Path $probeRoot 'starts.log'
$failurePath = Join-Path $probeRoot 'failure.json'
$taskModule = Join-Path $PSScriptRoot `
    'StockQuantHostBroker.TaskDefinition.psm1'
$powershellExe = Join-Path $env:SystemRoot `
    'System32\WindowsPowerShell\v1.0\powershell.exe'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$created = $false
$formalTaskWasPresent = $false
$formalTaskBefore = $null
$passed = 0
$recoverySeconds = $null
$firstProcessId = $null
$secondProcessId = $null

function Get-CanonicalXml {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Xml
    )
    $document = [Xml.XmlDocument]::new()
    $document.PreserveWhitespace = $false
    $document.LoadXml($Xml)
    return $document.OuterXml
}

function Get-ProbeProcesses {
    return @(Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" |
        Where-Object {
            $null -ne $_.CommandLine -and
            $_.CommandLine.IndexOf(
                $probeScript, [StringComparison]::OrdinalIgnoreCase) -ge 0
        })
}

function Read-ProbeHeartbeat {
    param(
        [DateTimeOffset] $Deadline = [DateTimeOffset]::UtcNow.AddSeconds(10)
    )
    do {
        try {
            if (Test-Path -LiteralPath $heartbeatPath -PathType Leaf) {
                $stream = [IO.FileStream]::new($heartbeatPath,
                    [IO.FileMode]::Open, [IO.FileAccess]::Read,
                    ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
                try {
                    $reader = [IO.StreamReader]::new(
                        $stream, [Text.Encoding]::UTF8, $true)
                    try {
                        $heartbeat = $reader.ReadToEnd() | ConvertFrom-Json
                    } finally {
                        $reader.Dispose()
                    }
                } finally {
                    $stream.Dispose()
                }
                if ($heartbeat.schemaVersion -eq
                        'STOCK_QUANT_HOST_BROKER_WATCHDOG_PROBE_V1' -and
                    [string]$heartbeat.state -eq 'IDLE' -and
                    [int]$heartbeat.processId -gt 0) {
                    return $heartbeat
                }
            }
        } catch {
            # A concurrent atomic heartbeat replacement is transient.
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTimeOffset]::UtcNow -lt $Deadline)
    throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_HEARTBEAT_TIMEOUT'
}

function Wait-Until {
    param(
        [Parameter(Mandatory = $true)]
        [DateTimeOffset] $Instant
    )
    while ([DateTimeOffset]::Now -lt $Instant) {
        Start-Sleep -Milliseconds 100
    }
}

if ($identity.Name -match '(?i)CodexSandbox') {
    throw 'STOCK_QUANT_HOST_BROKER_REAL_USER_REQUIRED'
}
if ($PSVersionTable.PSVersion.Major -ne 5 -or
    $PSVersionTable.PSVersion.Minor -ne 1) {
    throw 'STOCK_QUANT_HOST_BROKER_POWERSHELL_51_REQUIRED'
}
if ((git -C $repoRoot rev-parse HEAD).Trim() -ne $ExpectedCommit) {
    throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_GIT_INVALID'
}
if (-not (Test-Path -LiteralPath $taskModule -PathType Leaf) -or
    -not (Test-Path -LiteralPath $powershellExe -PathType Leaf)) {
    throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_PREREQUISITE_MISSING'
}

$formalTask = Get-ScheduledTask -TaskName $formalTaskName `
    -ErrorAction SilentlyContinue
if ($null -ne $formalTask) {
    $formalTaskWasPresent = $true
    $formalTaskBefore = [string](Export-ScheduledTask `
        -TaskName $formalTaskName)
}
if ($null -ne (Get-ScheduledTask -TaskName $probeTaskName `
        -ErrorAction SilentlyContinue)) {
    throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_NAME_COLLISION'
}

try {
    New-Item -ItemType Directory -Path $probeRoot | Out-Null
    $escapedProbeRoot = $probeRoot.Replace("'", "''")
    $mutexName = 'Local\StockQuantHostBrokerWatchdogProbe_' +
        [Guid]::NewGuid().ToString('N')
    $probeSource = @'
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = '__PROBE_ROOT__'
$heartbeat = Join-Path $root 'heartbeat.json'
$starts = Join-Path $root 'starts.log'
$failure = Join-Path $root 'failure.json'
$mutex = [Threading.Mutex]::new($false, '__MUTEX_NAME__')
$held = $false
try {
    $held = $mutex.WaitOne(0)
    if (-not $held) { exit 0 }
    $startedAt = [DateTimeOffset]::UtcNow
    [IO.File]::AppendAllText($starts,
        "$PID|$($startedAt.ToString('o'))`r`n",
        [Text.UTF8Encoding]::new($false))
    while ($true) {
        $body = [ordered]@{
            schemaVersion = 'STOCK_QUANT_HOST_BROKER_WATCHDOG_PROBE_V1'
            processId = $PID
            startedAt = $startedAt.ToString('o')
            lastHeartbeat = [DateTimeOffset]::UtcNow.ToString('o')
            state = 'IDLE'
            credentialReadCount = 0
            providerCallCount = 0
            permanentDatabaseWriteCount = 0
        }
        $temporary = Join-Path $root `
            ('.heartbeat.' + [Guid]::NewGuid().ToString('N') + '.tmp')
        $backup = Join-Path $root `
            ('.heartbeat.backup.' + [Guid]::NewGuid().ToString('N') + '.tmp')
        try {
            [IO.File]::WriteAllText($temporary,
                (($body | ConvertTo-Json -Compress) + "`n"),
                [Text.UTF8Encoding]::new($false))
            $written = $false
            for ($attempt = 1; $attempt -le 20 -and -not $written;
                    $attempt++) {
                try {
                    if (Test-Path -LiteralPath $heartbeat -PathType Leaf) {
                        [IO.File]::Replace($temporary, $heartbeat, $backup)
                    } else {
                        [IO.File]::Move($temporary, $heartbeat)
                    }
                    $written = $true
                } catch [IO.IOException] {
                    if ($attempt -eq 20) { throw }
                    Start-Sleep -Milliseconds 25
                } catch [UnauthorizedAccessException] {
                    if ($attempt -eq 20) { throw }
                    Start-Sleep -Milliseconds 25
                }
            }
        } finally {
            if (Test-Path -LiteralPath $temporary -PathType Leaf) {
                Remove-Item -LiteralPath $temporary -Force
            }
            if (Test-Path -LiteralPath $backup -PathType Leaf) {
                Remove-Item -LiteralPath $backup -Force
            }
        }
        Start-Sleep -Milliseconds 500
    }
} catch {
    $unsignedHResult = [BitConverter]::ToUInt32(
        [BitConverter]::GetBytes([int]$_.Exception.HResult), 0)
    $safeFailure = [ordered]@{
        exceptionType = $_.Exception.GetType().FullName
        innerExceptionType = $(if ($null -eq $_.Exception.InnerException) {
            'NONE'
        } else { $_.Exception.InnerException.GetType().FullName })
        hResult = ('0x{0:X8}' -f $unsignedHResult)
        fullyQualifiedErrorId = [string]$_.FullyQualifiedErrorId
        scriptLineNumber = [int]$_.InvocationInfo.ScriptLineNumber
    }
    [IO.File]::WriteAllText($failure,
        (($safeFailure | ConvertTo-Json -Compress) + "`n"),
        [Text.UTF8Encoding]::new($false))
    exit 20
} finally {
    if ($held) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
'@
    $probeSource = $probeSource.Replace(
        '__PROBE_ROOT__', $escapedProbeRoot)
    $probeSource = $probeSource.Replace('__MUTEX_NAME__', $mutexName)
    [IO.File]::WriteAllText($probeScript, $probeSource,
        [Text.UTF8Encoding]::new($false))

    $watchdogStartAt = [DateTime]::Now.AddSeconds(15)
    $definition = New-StockQuantHostBrokerTaskDefinition `
        -PowerShellExecutable $powershellExe -BrokerScript $probeScript `
        -WorkingDirectory $repoRoot -UserId $identity.Name `
        -WatchdogStartAt $watchdogStartAt `
        -Description 'Temporary Stock Quant watchdog lifecycle probe.'
    Assert-StockQuantHostBrokerTaskDefinition -Task $definition `
        -ExpectedPowerShellExecutable $powershellExe `
        -ExpectedBrokerScript $probeScript `
        -ExpectedWorkingDirectory $repoRoot `
        -ExpectedUserSid $identity.User.Value
    $passed++

    $transaction = Invoke-StockQuantHostBrokerTaskRegistrationTransaction `
        -TaskName $probeTaskName -Definition $definition `
        -ExpectedPowerShellExecutable $powershellExe `
        -ExpectedBrokerScript $probeScript `
        -ExpectedWorkingDirectory $repoRoot `
        -ExpectedUserSid $identity.User.Value
    $created = $true
    if (-not $transaction.Created -or $transaction.Updated) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_REGISTRATION_INVALID'
    }
    $registered = Get-ScheduledTask -TaskName $probeTaskName `
        -ErrorAction Stop
    Assert-StockQuantHostBrokerTaskDefinition -Task $registered `
        -ExpectedPowerShellExecutable $powershellExe `
        -ExpectedBrokerScript $probeScript `
        -ExpectedWorkingDirectory $repoRoot `
        -ExpectedUserSid $identity.User.Value `
        -ExpectedTaskName $probeTaskName
    $passed++

    $serialized = [string](Export-ScheduledTask -TaskName $probeTaskName)
    $xml = [Xml.XmlDocument]::new()
    $xml.LoadXml($serialized)
    $logonNode = $xml.SelectSingleNode(
        "//*[local-name()='Triggers']/*[local-name()='LogonTrigger']")
    $timeNode = $xml.SelectSingleNode(
        "//*[local-name()='Triggers']/*[local-name()='TimeTrigger']")
    $intervalNode = $xml.SelectSingleNode(
        "//*[local-name()='TimeTrigger']/*[local-name()='Repetition']/*[local-name()='Interval']")
    $durationNode = $xml.SelectSingleNode(
        "//*[local-name()='TimeTrigger']/*[local-name()='Repetition']/*[local-name()='Duration']")
    $stopAtDurationEndNode = $xml.SelectSingleNode(
        "//*[local-name()='TimeTrigger']/*[local-name()='Repetition']/*[local-name()='StopAtDurationEnd']")
    $passwordNode = $xml.SelectSingleNode("//*[local-name()='Password']")
    if ($null -eq $logonNode -or $null -eq $timeNode -or
        $null -eq $intervalNode -or $intervalNode.InnerText -cne 'PT1M' -or
        $null -ne $durationNode -or $null -ne $passwordNode -or
        ($null -ne $stopAtDurationEndNode -and
            $stopAtDurationEndNode.InnerText -cne 'false')) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_XML_INVALID'
    }
    $passed++

    Start-ScheduledTask -TaskName $probeTaskName -ErrorAction Stop
    $firstHeartbeat = Read-ProbeHeartbeat
    $firstProcessId = [int]$firstHeartbeat.processId
    if (@(Get-ProbeProcesses).Count -ne 1 -or
        @(Get-Content -LiteralPath $startLogPath).Count -ne 1) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_INITIAL_START_INVALID'
    }
    $passed++

    Wait-Until -Instant ([DateTimeOffset]$watchdogStartAt.AddSeconds(20))
    $afterIgnoredTrigger = Read-ProbeHeartbeat
    if ([int]$afterIgnoredTrigger.processId -ne $firstProcessId) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_IGNORE_NEW_PID_CHANGED'
    }
    $processCountAfterIgnoredTrigger = @(Get-ProbeProcesses).Count
    if ($processCountAfterIgnoredTrigger -ne 1) {
        Write-Output ("STOCK_QUANT_HOST_BROKER_WATCHDOG_DIAGNOSTIC_PROCESS_COUNT=" +
            $processCountAfterIgnoredTrigger)
        Write-Output ("STOCK_QUANT_HOST_BROKER_WATCHDOG_DIAGNOSTIC_PID_ALIVE=" +
            ($null -ne (Get-Process -Id $firstProcessId `
                -ErrorAction SilentlyContinue)))
        Write-Output ("STOCK_QUANT_HOST_BROKER_WATCHDOG_DIAGNOSTIC_TASK_STATE=" +
            [string](Get-ScheduledTask -TaskName $probeTaskName).State)
        $diagnosticTaskInfo = Get-ScheduledTaskInfo `
            -TaskName $probeTaskName -ErrorAction Stop
        Write-Output ("STOCK_QUANT_HOST_BROKER_WATCHDOG_DIAGNOSTIC_LAST_RESULT=" +
            ('0x{0:X8}' -f ([uint32]$diagnosticTaskInfo.LastTaskResult)))
        Write-Output ("STOCK_QUANT_HOST_BROKER_WATCHDOG_DIAGNOSTIC_LAST_RUN=" +
            $diagnosticTaskInfo.LastRunTime.ToString('o'))
        Write-Output ("STOCK_QUANT_HOST_BROKER_WATCHDOG_DIAGNOSTIC_NEXT_RUN=" +
            $diagnosticTaskInfo.NextRunTime.ToString('o'))
        $diagnosticEvents = @(Get-WinEvent -FilterHashtable @{
                LogName = 'Microsoft-Windows-TaskScheduler/Operational'
                StartTime = [DateTime]::Now.AddMinutes(-3)
            } -ErrorAction SilentlyContinue | Where-Object {
                $_.Message -like "*$probeTaskName*"
            } | Select-Object -First 8)
        foreach ($event in $diagnosticEvents) {
            Write-Output (
                'STOCK_QUANT_HOST_BROKER_WATCHDOG_DIAGNOSTIC_EVENT=' +
                $event.Id + '|' + $event.LevelDisplayName)
        }
        if (Test-Path -LiteralPath $failurePath -PathType Leaf) {
            $safeFailureJson = (Get-Content -LiteralPath $failurePath `
                -Raw -Encoding UTF8).Trim()
            Write-Output ('STOCK_QUANT_HOST_BROKER_WATCHDOG_DIAGNOSTIC_FAILURE=' +
                $safeFailureJson)
        }
        throw `
            'STOCK_QUANT_HOST_BROKER_WATCHDOG_IGNORE_NEW_PROCESS_COUNT_MISMATCH'
    }
    $startCountAfterIgnoredTrigger = @(
        Get-Content -LiteralPath $startLogPath).Count
    if ($startCountAfterIgnoredTrigger -ne 1) {
        throw `
            'STOCK_QUANT_HOST_BROKER_WATCHDOG_IGNORE_NEW_START_COUNT_MISMATCH'
    }
    $passed++

    $taskInfo = Get-ScheduledTaskInfo -TaskName $probeTaskName `
        -ErrorAction Stop
    $nextRun = [DateTimeOffset]$taskInfo.NextRunTime
    if ($nextRun -le [DateTimeOffset]::Now -or
        $nextRun -gt [DateTimeOffset]::Now.AddSeconds(50)) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_NEXT_RUN_INVALID'
    }
    $passed++

    $firstProcess = Get-CimInstance Win32_Process `
        -Filter "ProcessId = $firstProcessId" -ErrorAction Stop
    if ($null -eq $firstProcess.CommandLine -or
        $firstProcess.CommandLine.IndexOf(
            $probeScript, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_KILL_TARGET_INVALID'
    }
    $killedAt = [DateTimeOffset]::UtcNow
    Stop-Process -Id $firstProcessId -Force -ErrorAction Stop
    $exitDeadline = [DateTimeOffset]::UtcNow.AddSeconds(5)
    while ($null -ne (Get-Process -Id $firstProcessId `
            -ErrorAction SilentlyContinue) -and
        [DateTimeOffset]::UtcNow -lt $exitDeadline) {
        Start-Sleep -Milliseconds 100
    }
    if ($null -ne (Get-Process -Id $firstProcessId `
            -ErrorAction SilentlyContinue)) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_FORCED_KILL_FAILED'
    }
    $passed++

    $recoveryDeadline = $killedAt.AddSeconds(55)
    $secondHeartbeat = $null
    while ($null -eq $secondHeartbeat -and
        [DateTimeOffset]::UtcNow -lt $recoveryDeadline) {
        try {
            $candidate = Read-ProbeHeartbeat `
                -Deadline ([DateTimeOffset]::UtcNow.AddMilliseconds(300))
            if ([int]$candidate.processId -ne $firstProcessId -and
                [DateTimeOffset]::Parse(
                    [string]$candidate.startedAt) -gt $killedAt) {
                $secondHeartbeat = $candidate
            }
        } catch {
            # Wait for the scheduled watchdog trigger; never demand-start here.
        }
        if ($null -eq $secondHeartbeat) {
            Start-Sleep -Milliseconds 200
        }
    }
    if ($null -eq $secondHeartbeat) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_AUTO_RECOVERY_TIMEOUT'
    }
    $secondProcessId = [int]$secondHeartbeat.processId
    $recoverySeconds = [Math]::Round(
        ([DateTimeOffset]::UtcNow - $killedAt).TotalSeconds, 3)
    if ($recoverySeconds -ge 55) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_AUTO_RECOVERY_TOO_SLOW'
    }
    $passed++

    $heartbeatBeforeIdle = [DateTimeOffset]::Parse(
        [string]$secondHeartbeat.lastHeartbeat)
    Start-Sleep -Milliseconds 2200
    $idleHeartbeat = Read-ProbeHeartbeat
    if ([int]$idleHeartbeat.processId -ne $secondProcessId -or
        [string]$idleHeartbeat.state -cne 'IDLE' -or
        [DateTimeOffset]::Parse([string]$idleHeartbeat.lastHeartbeat) -le
            $heartbeatBeforeIdle) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_RECOVERED_HEARTBEAT_INVALID'
    }
    $passed++

    $probeProcesses = @(Get-ProbeProcesses)
    if ($probeProcesses.Count -ne 1 -or
        [int]$probeProcesses[0].ProcessId -ne $secondProcessId -or
        @(Get-Content -LiteralPath $startLogPath).Count -ne 2 -or
        [string](Get-ScheduledTask -TaskName $probeTaskName).State -cne
            'Running') {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_SINGLE_INSTANCE_INVALID'
    }
    $passed++

    $connections = @(Get-NetTCPConnection -OwningProcess $secondProcessId `
        -ErrorAction SilentlyContinue)
    if ([int]$idleHeartbeat.credentialReadCount -ne 0 -or
        [int]$idleHeartbeat.providerCallCount -ne 0 -or
        [int]$idleHeartbeat.permanentDatabaseWriteCount -ne 0 -or
        $connections.Count -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_IDLE_SIDE_EFFECT'
    }
    $passed++
} finally {
    if ($probeTaskName -notmatch
            '^StockQuantHostBrokerRoundTrip_[A-F0-9]{32}$') {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_CLEANUP_NAME_INVALID'
    }
    $probeTask = Get-ScheduledTask -TaskName $probeTaskName `
        -ErrorAction SilentlyContinue
    if ($null -ne $probeTask) {
        Stop-ScheduledTask -TaskName $probeTaskName `
            -ErrorAction SilentlyContinue
    }
    foreach ($process in @(Get-ProbeProcesses)) {
        Stop-Process -Id ([int]$process.ProcessId) -Force `
            -ErrorAction SilentlyContinue
    }
    $processDeadline = [DateTimeOffset]::UtcNow.AddSeconds(5)
    while (@(Get-ProbeProcesses).Count -ne 0 -and
        [DateTimeOffset]::UtcNow -lt $processDeadline) {
        Start-Sleep -Milliseconds 100
    }
    if ($created -or $null -ne $probeTask) {
        Unregister-ScheduledTask -TaskName $probeTaskName `
            -Confirm:$false -ErrorAction Stop
    }
    if ($null -ne (Get-ScheduledTask -TaskName $probeTaskName `
            -ErrorAction SilentlyContinue) -or
        @(Get-ProbeProcesses).Count -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_TASK_RESIDUAL'
    }

    $formalTaskAfter = Get-ScheduledTask -TaskName $formalTaskName `
        -ErrorAction SilentlyContinue
    if (($formalTaskWasPresent -and $null -eq $formalTaskAfter) -or
        (-not $formalTaskWasPresent -and $null -ne $formalTaskAfter)) {
        throw 'STOCK_QUANT_HOST_BROKER_FORMAL_TASK_TOUCHED'
    }
    if ($formalTaskWasPresent) {
        $formalTaskAfterXml = [string](Export-ScheduledTask `
            -TaskName $formalTaskName)
        if ((Get-CanonicalXml -Xml $formalTaskBefore) -cne
            (Get-CanonicalXml -Xml $formalTaskAfterXml)) {
            throw 'STOCK_QUANT_HOST_BROKER_FORMAL_TASK_TOUCHED'
        }
    }

    if (Test-Path -LiteralPath $probeRoot) {
        $resolved = [IO.Path]::GetFullPath($probeRoot).TrimEnd('\')
        $targetPrefix = [IO.Path]::GetFullPath($targetRoot).TrimEnd('\') + '\'
        if (-not $resolved.StartsWith(
                $targetPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($resolved).StartsWith($probePrefix)) {
            throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_CLEANUP_PATH_INVALID'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    if (Test-Path -LiteralPath $probeRoot) {
        throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_DIRECTORY_RESIDUAL'
    }
}

$passed++
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_ROUNDTRIP=PASS'
Write-Output "STOCK_QUANT_HOST_BROKER_WATCHDOG_TESTS=$passed/0/0/0"
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_TRIGGERS=2'
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_LOGON_TRIGGER=PASS'
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_INTERVAL=PT1M'
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_MULTIPLE_INSTANCES=IgnoreNew'
Write-Output "STOCK_QUANT_HOST_BROKER_WATCHDOG_FORCED_KILL_RECOVERY_SECONDS=$recoverySeconds"
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_SINGLE_INSTANCE=PASS'
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_IDLE_CREDENTIAL_READS=0'
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_IDLE_PROVIDER_CALLS=0'
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_REAL_PROVIDER_CALLS=0'
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_PERMANENT_DATABASE_WRITES=0'
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_RESIDUALS=0'
if ($passed -ne 12) {
    throw 'STOCK_QUANT_HOST_BROKER_WATCHDOG_TEST_COUNT_INVALID'
}
