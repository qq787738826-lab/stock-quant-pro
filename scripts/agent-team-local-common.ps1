$ErrorActionPreference = 'Stop'
$script:AgentTeamRuntimeLockTokens = New-Object 'System.Collections.Generic.HashSet[guid]'

function Get-AgentTeamRepositoryRoot {
    $root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    if ((Get-Location).Path -cne $root) {
        throw 'Run this script from the repository root.'
    }
    return $root
}

function Get-ProcessEnvironmentVariable([string]$Name) {
    return [Environment]::GetEnvironmentVariable($Name, [EnvironmentVariableTarget]::Process)
}
function Set-ProcessEnvironmentVariable([string]$Name, [string]$Value) {
    [Environment]::SetEnvironmentVariable($Name, $Value, [EnvironmentVariableTarget]::Process)
}
function Remove-ProcessEnvironmentVariable([string]$Name) {
    [Environment]::SetEnvironmentVariable($Name, $null, [EnvironmentVariableTarget]::Process)
}

function Enter-AgentTeamRuntimeMutex([int]$TimeoutSeconds = 10) {
    $runtimeMutex = New-Object System.Threading.Mutex($false, 'Local\StockQuantAgentTeamLocalRuntimeV1')
    $acquired = $false
    try {
        try {
            $acquired = $runtimeMutex.WaitOne([TimeSpan]::FromSeconds($TimeoutSeconds))
        } catch [System.Threading.AbandonedMutexException] {
            $acquired = $true
        }
        if (-not $acquired) { throw 'The agent team runtime lock could not be acquired.' }
        $lockToken = [guid]::NewGuid()
        [void]$script:AgentTeamRuntimeLockTokens.Add($lockToken)
        return [pscustomobject]@{
            Mutex = $runtimeMutex
            Acquired = $true
            Token = $lockToken
        }
    } catch {
        if (-not $acquired) { $runtimeMutex.Dispose() }
        throw
    }
}

function Exit-AgentTeamRuntimeMutex([object]$LockContext) {
    if ($null -eq $LockContext -or $LockContext.Acquired -ne $true) { return }
    try {
        [void]$script:AgentTeamRuntimeLockTokens.Remove([guid]$LockContext.Token)
        $LockContext.Mutex.ReleaseMutex()
    }
    finally {
        $LockContext.Mutex.Dispose()
        $LockContext.Acquired = $false
    }
}

function Assert-AgentTeamRuntimeLock([object]$LockContext) {
    if ($null -eq $LockContext -or $LockContext.Acquired -ne $true -or
            $LockContext.Token -isnot [guid] -or $LockContext.Token -eq [guid]::Empty -or
            -not $script:AgentTeamRuntimeLockTokens.Contains([guid]$LockContext.Token)) {
        throw 'A valid agent team runtime lock is required.'
    }
}

function Normalize-DuplicateProcessEnvironmentNames {
    $environment = [Environment]::GetEnvironmentVariables([EnvironmentVariableTarget]::Process)
    $groups = New-Object 'System.Collections.Generic.Dictionary[string,System.Collections.Generic.List[string]]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($key in $environment.Keys) {
        $name = [string]$key
        if (-not $groups.ContainsKey($name)) { $groups[$name] = New-Object 'System.Collections.Generic.List[string]' }
        $groups[$name].Add($name)
    }
    $snapshots = New-Object 'System.Collections.Generic.List[object]'
    foreach ($group in $groups.Values) {
        if ($group.Count -lt 2) { continue }
        $names = @($group | Sort-Object)
        $entries = New-Object 'System.Collections.Generic.List[object]'
        foreach ($name in $names) { $entries.Add([pscustomobject]@{ Name=$name; Value=[string]$environment[$name] }) }
        $reference = $entries[0].Value
        foreach ($entry in $entries) {
            if (-not [string]::Equals($entry.Value, $reference, [StringComparison]::Ordinal)) {
                throw ('Duplicate process environment variable names have conflicting values: ' + ($names -join ', '))
            }
        }
        $snapshots.Add([pscustomobject]@{ CanonicalName=$names[0]; Entries=$entries.ToArray() })
    }
    foreach ($snapshot in $snapshots) {
        foreach ($entry in $snapshot.Entries) { Remove-ProcessEnvironmentVariable $entry.Name }
        Set-ProcessEnvironmentVariable $snapshot.CanonicalName $snapshot.Entries[0].Value
    }
    return $snapshots.ToArray()
}
function Restore-DuplicateProcessEnvironmentNames([object[]]$Snapshots) {
    $restoreErrors = New-Object 'System.Collections.Generic.List[string]'
    foreach ($snapshot in $Snapshots) {
        try { Remove-ProcessEnvironmentVariable $snapshot.CanonicalName }
        catch { $restoreErrors.Add([string]$snapshot.CanonicalName) }
        foreach ($entry in $snapshot.Entries) {
            try { Set-ProcessEnvironmentVariable $entry.Name $entry.Value }
            catch { $restoreErrors.Add([string]$entry.Name) }
        }
    }
    if ($restoreErrors.Count -gt 0) {
        throw ('Duplicate process environment restoration failed for ' + $restoreErrors.Count + ' name(s).')
    }
}

function Test-DuplicateProcessEnvironmentSnapshot([object[]]$Snapshots) {
    $environment = [Environment]::GetEnvironmentVariables([EnvironmentVariableTarget]::Process)
    foreach ($snapshot in $Snapshots) {
        $matchingNames = @($environment.Keys | Where-Object {
            [string]::Equals([string]$_, $snapshot.CanonicalName, [StringComparison]::OrdinalIgnoreCase)
        })
        if ($matchingNames.Count -ne $snapshot.Entries.Count) { return $false }
        foreach ($entry in $snapshot.Entries) {
            $exact = @($matchingNames | Where-Object {
                [string]::Equals([string]$_, $entry.Name, [StringComparison]::Ordinal)
            })
            if ($exact.Count -ne 1) { return $false }
            if (-not [string]::Equals([string]$environment[$exact[0]], $entry.Value, [StringComparison]::Ordinal)) {
                return $false
            }
        }
    }
    return $true
}

function Get-SensitiveEnvironmentNames {
    $names = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    @('AI_PROVIDER','AI_SERVICE_URL','STOCK_QUANT_PYTHON_BASE_URL','STOCK_QUANT_TEST_DB_URL',
      'STOCK_QUANT_TEST_DB_USERNAME','STOCK_QUANT_TEST_DB_PASSWORD','DATABASE_URL',
      'SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','DB_PASSWORD',
      'PGHOST','PGPORT','PGDATABASE','PGUSER','PGPASSWORD',
      'DB_HOST','DB_PORT','DB_NAME','DB_USER','SERVER_PORT','AGENT_TEAM_ENABLED','AGENT_TEAM_BASE_URL',
      'SPRING_APPLICATION_JSON','SPRING_PROFILES_ACTIVE','SPRING_CONFIG_LOCATION',
      'SPRING_CONFIG_ADDITIONAL_LOCATION','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_OPTS',
      'PYTHONHOME','PYTHONPATH','PYTHONSTARTUP','NODE_OPTIONS') | ForEach-Object { [void]$names.Add($_) }
    $environment = [Environment]::GetEnvironmentVariables([EnvironmentVariableTarget]::Process)
    foreach ($key in $environment.Keys) {
        $name = [string]$key
        if ($name -match '(?i)^(DB_|SPRING_|AGENT_TEAM_)' -or
                $name -match '(?i)(OPENAI|ANTHROPIC|DEEPSEEK|DASHSCOPE|LLM|AKSHARE|TUSHARE|MARKET|QUOTE|BROKER|TRADING|TRADE|API[_-]?KEY|TOKEN|SECRET)') {
            [void]$names.Add($name)
        }
    }
    return @($names | Sort-Object)
}

function Invoke-WithTemporaryProcessEnvironment([string[]]$RemoveNames, [hashtable]$SetValues, [scriptblock]$Action) {
    $saved = New-Object 'System.Collections.Generic.Dictionary[string,object]' ([StringComparer]::OrdinalIgnoreCase)
    $allNames = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($name in $RemoveNames) { [void]$allNames.Add($name) }
    foreach ($name in $SetValues.Keys) { [void]$allNames.Add([string]$name) }
    try {
        foreach ($name in $allNames) {
            $value = Get-ProcessEnvironmentVariable $name
            $saved[$name] = [pscustomobject]@{ Exists=($null -ne $value); Value=$value }
            Remove-ProcessEnvironmentVariable $name
        }
        foreach ($name in $SetValues.Keys) { Set-ProcessEnvironmentVariable $name ([string]$SetValues[$name]) }
        return & $Action
    } finally {
        $restoreErrors = New-Object 'System.Collections.Generic.List[string]'
        foreach ($name in $allNames) {
            try {
                if ($saved[$name].Exists) { Set-ProcessEnvironmentVariable $name $saved[$name].Value }
                else { Remove-ProcessEnvironmentVariable $name }
            } catch {
                $restoreErrors.Add([string]$name)
            }
        }
        if ($restoreErrors.Count -gt 0) {
            throw ('Process environment restoration failed for ' + $restoreErrors.Count + ' variable(s).')
        }
    }
}

function Start-ManagedProcessWithTemporaryEnvironment([string[]]$RemoveNames, [hashtable]$SetValues,
        [scriptblock]$StartAction, [System.Collections.IList]$StartupAttempts,
        [string]$Name, [int]$Port, [string]$ExpectedListenerName, [object]$Logs) {
    $saved = New-Object 'System.Collections.Generic.Dictionary[string,object]' ([StringComparer]::OrdinalIgnoreCase)
    $allNames = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($itemName in $RemoveNames) { [void]$allNames.Add($itemName) }
    foreach ($itemName in $SetValues.Keys) { [void]$allNames.Add([string]$itemName) }
    $serviceProcess = $null
    try {
        foreach ($itemName in $allNames) {
            $value = Get-ProcessEnvironmentVariable $itemName
            $saved[$itemName] = [pscustomobject]@{ Exists=($null -ne $value); Value=$value }
            Remove-ProcessEnvironmentVariable $itemName
        }
        foreach ($itemName in $SetValues.Keys) {
            Set-ProcessEnvironmentVariable $itemName ([string]$SetValues[$itemName])
        }
        $serviceProcess = & $StartAction
        if (-not $serviceProcess -or [int]$serviceProcess.Id -le 0) { throw ('Start-Process returned no PID: ' + $Name) }
        $attempt = [pscustomobject]@{
            name=$Name; port=$Port; expectedRootProcessName='powershell'; expectedListenerProcessName=$ExpectedListenerName
            rootProcessId=[int]$serviceProcess.Id; rootStartTimeUtcTicks=0L; identityComplete=$false
            listenerProcessId=$null; listenerStartTimeUtcTicks=$null; listenerProcessName=$null
            stdoutLog=$Logs.Stdout; stderrLog=$Logs.Stderr; ownedByRuntime=$true
        }
        [void]$StartupAttempts.Add($attempt)
        $attempt.rootStartTimeUtcTicks = $serviceProcess.StartTime.ToUniversalTime().Ticks
        $attempt.identityComplete = $true
    } finally {
        $restoreErrors = New-Object 'System.Collections.Generic.List[string]'
        foreach ($itemName in $allNames) {
            try {
                if ($saved[$itemName].Exists) { Set-ProcessEnvironmentVariable $itemName $saved[$itemName].Value }
                else { Remove-ProcessEnvironmentVariable $itemName }
            } catch { $restoreErrors.Add([string]$itemName) }
        }
        if ($restoreErrors.Count -gt 0) {
            throw ('Process environment restoration failed after starting ' + $Name + '.')
        }
    }
    if (-not (Test-ProcessIdentity ([pscustomobject]@{
                ProcessId=$attempt.rootProcessId
                ProcessName='powershell'
                StartTimeUtcTicks=$attempt.rootStartTimeUtcTicks
            }))) { throw ('Startup root identity validation failed: ' + $Name) }
    return [pscustomobject]@{ Process=$serviceProcess; Attempt=$attempt }
}

function Get-AgentTeamRuntimePaths {
    $base = Join-Path ([IO.Path]::GetTempPath()) 'stock-quant-agent-team-local'
    return [pscustomobject]@{ Base=$base; Runs=(Join-Path $base 'runs'); State=(Join-Path $base 'state.json') }
}
function Get-PortListener([int]$Port) {
    $line = @(netstat -ano -p tcp | Select-String (':' + $Port + '\s+.*LISTENING') | Select-Object -First 1)
    if ($line.Count -eq 0) { return $null }
    $parts = $line[0].Line.Trim() -split '\s+'
    $listenerId = [int]$parts[-1]
    $process = Get-Process -Id $listenerId -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    return [pscustomobject]@{ Port=$Port; ProcessId=$listenerId; ProcessName=$process.ProcessName; StartTimeUtcTicks=$process.StartTime.ToUniversalTime().Ticks }
}
function Wait-HttpHealth([string]$Uri, [int]$TimeoutSeconds, [scriptblock]$Validate) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 3
            if ($response.StatusCode -eq 200 -and (& $Validate $response)) { return $true }
        } catch { }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    return $false
}
function Get-ProcessIdentity([int]$ProcessId) {
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    return [pscustomobject]@{ ProcessId=$ProcessId; ProcessName=$process.ProcessName; StartTimeUtcTicks=$process.StartTime.ToUniversalTime().Ticks }
}
function Test-ProcessIdentity([object]$Expected) {
    $actual = Get-ProcessIdentity ([int]$Expected.ProcessId)
    return ($null -ne $actual -and $actual.ProcessName -ieq $Expected.ProcessName -and $actual.StartTimeUtcTicks -eq [long]$Expected.StartTimeUtcTicks)
}
function Get-ProcessAncestorIds([int]$ProcessId, [int]$MaximumDepth = 64) {
    $ancestors = New-Object 'System.Collections.Generic.List[int]'
    $visited = New-Object 'System.Collections.Generic.HashSet[int]'
    $currentId = $ProcessId
    for ($depth = 0; $depth -lt $MaximumDepth; $depth++) {
        if (-not $visited.Add($currentId)) { throw 'A process ancestry cycle was detected.' }
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$currentId" -ErrorAction SilentlyContinue
        if (-not $processInfo) { break }
        $parentId = [int]$processInfo.ParentProcessId
        if ($parentId -le 0) { break }
        $ancestors.Add($parentId)
        $currentId = $parentId
    }
    if ($ancestors.Count -ge $MaximumDepth) { throw 'Process ancestry exceeded the maximum depth.' }
    return $ancestors.ToArray()
}

function Test-ProcessDescendantOf([int]$ProcessId, [int]$RootProcessId) {
    if ($ProcessId -eq $RootProcessId) { return $false }
    return @(Get-ProcessAncestorIds $ProcessId 64) -contains $RootProcessId
}

function Get-ProcessTreeSnapshot([int]$RootProcessId) {
    $root = Get-Process -Id $RootProcessId -ErrorAction SilentlyContinue
    if (-not $root) { throw 'The process tree root does not exist.' }
    $result = New-Object 'System.Collections.Generic.List[object]'
    $queue = New-Object 'System.Collections.Generic.Queue[object]'
    $queue.Enqueue([pscustomobject]@{ ProcessId=$RootProcessId; ParentProcessId=0; Depth=0 })
    $visited = New-Object 'System.Collections.Generic.HashSet[int]'
    while ($queue.Count -gt 0) {
        $item = $queue.Dequeue()
        if (-not $visited.Add([int]$item.ProcessId)) { throw 'A process tree cycle was detected.' }
        $process = Get-Process -Id ([int]$item.ProcessId) -ErrorAction SilentlyContinue
        if (-not $process) { throw ('A process disappeared while snapshotting PID ' + $item.ProcessId) }
        $result.Add([pscustomobject]@{
            ProcessId=[int]$item.ProcessId
            ProcessName=$process.ProcessName
            StartTimeUtcTicks=$process.StartTime.ToUniversalTime().Ticks
            ParentProcessId=[int]$item.ParentProcessId
            Depth=[int]$item.Depth
        })
        if ($item.Depth -ge 64) { throw 'Process tree exceeded the maximum depth.' }
        foreach ($child in @(Get-CimInstance Win32_Process -Filter ("ParentProcessId=" + $item.ProcessId) -ErrorAction SilentlyContinue)) {
            $queue.Enqueue([pscustomobject]@{
                ProcessId=[int]$child.ProcessId
                ParentProcessId=[int]$item.ProcessId
                Depth=[int]$item.Depth + 1
            })
        }
    }
    return $result.ToArray()
}

function Update-ManagedStartupListener([object]$Attempt) {
    $listener = Get-PortListener ([int]$Attempt.port)
    if (-not $listener) { return $false }
    if ($listener.ProcessName -ine [string]$Attempt.expectedListenerProcessName) {
        throw ('Unexpected listener process on port ' + $Attempt.port)
    }
    if (-not (Test-ProcessDescendantOf $listener.ProcessId ([int]$Attempt.rootProcessId))) {
        throw ('Listener is not a descendant of startup root: ' + $Attempt.name)
    }
    $Attempt.listenerProcessId = $listener.ProcessId
    $Attempt.listenerStartTimeUtcTicks = $listener.StartTimeUtcTicks
    $Attempt.listenerProcessName = $listener.ProcessName
    return $true
}

function Wait-ManagedServiceHealth([object]$Attempt, [string]$Uri, [int]$TimeoutSeconds,
        [scriptblock]$Validate) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (-not $Attempt.listenerProcessId) { [void](Update-ManagedStartupListener $Attempt) }
        if ($Attempt.listenerProcessId) {
            try {
                $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 3
                if ($response.StatusCode -eq 200 -and (& $Validate $response)) { return $true }
            } catch { }
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    return $false
}

function Complete-ManagedServiceRecord([object]$Attempt) {
    if (-not $Attempt.listenerProcessId) { throw ('Startup listener was not recorded: ' + $Attempt.name) }
    if (-not (Test-ProcessDescendantOf ([int]$Attempt.listenerProcessId) ([int]$Attempt.rootProcessId))) {
        throw ('Startup listener relationship changed: ' + $Attempt.name)
    }
    return [pscustomobject]@{
        name=$Attempt.name; port=$Attempt.port
        rootProcessId=$Attempt.rootProcessId; rootStartTimeUtcTicks=$Attempt.rootStartTimeUtcTicks
        listenerProcessId=$Attempt.listenerProcessId; listenerStartTimeUtcTicks=$Attempt.listenerStartTimeUtcTicks
        processName=$Attempt.expectedRootProcessName; listenerProcessName=$Attempt.expectedListenerProcessName
        ownedByRuntime=$true; stdoutLog=$Attempt.stdoutLog; stderrLog=$Attempt.stderrLog
    }
}
function Assert-ManagedServiceRecord([object]$Service, [string]$ExpectedName, [int]$ExpectedPort,
        [string]$ExpectedListenerName, [string]$RunDirectory) {
    if ($null -eq $Service) { throw ('Missing service record: ' + $ExpectedName) }
    if ([string]$Service.name -cne $ExpectedName) { throw ('Invalid service name: ' + $ExpectedName) }
    if ([int]$Service.port -ne $ExpectedPort) { throw ('Invalid service port: ' + $ExpectedName) }
    if ($Service.ownedByRuntime -isnot [bool] -or $Service.ownedByRuntime -ne $true) {
        throw ('Service is not strictly runtime-owned: ' + $ExpectedName)
    }
    if ([string]$Service.processName -ine 'powershell') { throw ('Invalid root process name: ' + $ExpectedName) }
    if ([string]$Service.listenerProcessName -ine $ExpectedListenerName) {
        throw ('Invalid listener process name: ' + $ExpectedName)
    }
    foreach ($propertyName in @('rootProcessId','listenerProcessId')) {
        $number = 0L
        if (-not [long]::TryParse([string]$Service.$propertyName, [ref]$number) -or $number -le 0 -or $number -gt [int]::MaxValue) {
            throw ('Invalid process ID in service record: ' + $ExpectedName)
        }
    }
    foreach ($propertyName in @('rootStartTimeUtcTicks','listenerStartTimeUtcTicks')) {
        $ticks = 0L
        if (-not [long]::TryParse([string]$Service.$propertyName, [ref]$ticks) -or $ticks -le 0) {
            throw ('Invalid process start time in service record: ' + $ExpectedName)
        }
    }
    $expectedStdout = [IO.Path]::GetFullPath((Join-Path $RunDirectory ($ExpectedName + '.out.log')))
    $expectedStderr = [IO.Path]::GetFullPath((Join-Path $RunDirectory ($ExpectedName + '.err.log')))
    if ([IO.Path]::GetFullPath([string]$Service.stdoutLog) -cne $expectedStdout -or
            [IO.Path]::GetFullPath([string]$Service.stderrLog) -cne $expectedStderr) {
        throw ('Service log paths are invalid: ' + $ExpectedName)
    }
}

function Assert-AgentTeamRuntimeState([object]$State, [string]$RepositoryRoot) {
    if ($null -eq $State) { throw 'Runtime state is missing.' }
    if ([int]$State.schemaVersion -ne 1) { throw 'Unsupported runtime state schema.' }
    if ([string]$State.repositoryRoot -cne $RepositoryRoot) { throw 'Runtime state belongs to another repository.' }
    $parsedRunId = [guid]::Empty
    if (-not [guid]::TryParse([string]$State.runId, [ref]$parsedRunId)) { throw 'Runtime runId is invalid.' }
    if ($parsedRunId -eq [guid]::Empty) { throw 'Runtime runId cannot be empty.' }
    $createdAt = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$State.createdAt, [ref]$createdAt)) {
        throw 'Runtime createdAt is invalid.'
    }
    if ($null -eq $State.services) { throw 'Runtime services object is missing.' }
    $serviceNames = @($State.services.PSObject.Properties.Name | Sort-Object)
    if (($serviceNames -join ',') -cne 'java,python,vue') { throw 'Runtime services mapping is invalid.' }
    $paths = Get-AgentTeamRuntimePaths
    $runDirectory = [IO.Path]::GetFullPath([string]$State.logDirectory).TrimEnd('\')
    $expectedRunDirectory = [IO.Path]::GetFullPath((Join-Path $paths.Runs $parsedRunId.ToString('D'))).TrimEnd('\')
    if ($runDirectory -cne $expectedRunDirectory) {
        throw 'Runtime log directory does not match runId.'
    }
    Assert-ManagedServiceRecord $State.services.python 'python' 8001 'python' $runDirectory
    Assert-ManagedServiceRecord $State.services.java 'java' 8080 'java' $runDirectory
    Assert-ManagedServiceRecord $State.services.vue 'vue' 5173 'node' $runDirectory
}

function Test-ManagedServiceIdentity([object]$Service) {
    $root = Get-Process -Id ([int]$Service.rootProcessId) -ErrorAction SilentlyContinue
    if ($root) {
        if ($root.ProcessName -ine [string]$Service.processName -or
                $root.StartTime.ToUniversalTime().Ticks -ne [long]$Service.rootStartTimeUtcTicks) {
            throw ('Root process identity mismatch: ' + $Service.name)
        }
    }
    $listenerProcess = Get-Process -Id ([int]$Service.listenerProcessId) -ErrorAction SilentlyContinue
    $portListener = Get-PortListener ([int]$Service.port)
    if ($listenerProcess) {
        if ($listenerProcess.ProcessName -ine [string]$Service.listenerProcessName -or
                $listenerProcess.StartTime.ToUniversalTime().Ticks -ne [long]$Service.listenerStartTimeUtcTicks) {
            throw ('Listener process identity mismatch: ' + $Service.name)
        }
        if (-not $portListener -or $portListener.ProcessId -ne [int]$Service.listenerProcessId) {
            throw ('Recorded listener is not listening on its fixed port: ' + $Service.name)
        }
        if (-not (Test-ProcessDescendantOf ([int]$Service.listenerProcessId) ([int]$Service.rootProcessId))) {
            throw ('Listener is not a descendant of root process: ' + $Service.name)
        }
    } elseif ($portListener) {
        throw ('Unknown listener occupies fixed port ' + $Service.port)
    }
    if ($portListener -and $portListener.ProcessId -ne [int]$Service.listenerProcessId) {
        throw ('Unknown listener occupies fixed port ' + $Service.port)
    }
    return [pscustomobject]@{ RootExists=($null -ne $root); ListenerExists=($null -ne $listenerProcess) }
}

function New-ManagedServiceStopPlan([object]$Service) {
    $root = Get-Process -Id ([int]$Service.rootProcessId) -ErrorAction SilentlyContinue
    $listener = Get-Process -Id ([int]$Service.listenerProcessId) -ErrorAction SilentlyContinue
    $portListener = Get-PortListener ([int]$Service.port)
    if ($root) {
        if ($root.ProcessName -ine [string]$Service.processName -or
                $root.StartTime.ToUniversalTime().Ticks -ne [long]$Service.rootStartTimeUtcTicks) {
            throw ('Root process identity mismatch: ' + $Service.name)
        }
    }
    if ($listener) {
        if ($listener.ProcessName -ine [string]$Service.listenerProcessName -or
                $listener.StartTime.ToUniversalTime().Ticks -ne [long]$Service.listenerStartTimeUtcTicks) {
            throw ('Listener process identity mismatch: ' + $Service.name)
        }
    }
    if ($portListener -and $portListener.ProcessId -ne [int]$Service.listenerProcessId) {
        throw ('Unknown listener occupies fixed port ' + $Service.port)
    }
    if ($root -and $listener) {
        if (-not $portListener) { throw ('Recorded listener is not listening: ' + $Service.name) }
        if (-not (Test-ProcessDescendantOf $listener.Id $root.Id)) {
            throw ('Listener is not a descendant of root process: ' + $Service.name)
        }
        $snapshot = Get-ProcessTreeSnapshot $root.Id
        if (-not (@($snapshot.ProcessId) -contains $listener.Id)) {
            throw ('Root tree snapshot does not include listener: ' + $Service.name)
        }
        return [pscustomobject]@{
            serviceName=$Service.name; mode='RootTree'; snapshot=$snapshot
            rootIdentity=(Get-ProcessIdentity $root.Id); listenerIdentity=(Get-ProcessIdentity $listener.Id)
            port=[int]$Service.port
        }
    }
    if (-not $root -and -not $listener) {
        if ($portListener) { throw ('Unknown listener occupies fixed port ' + $Service.port) }
        return [pscustomobject]@{
            serviceName=$Service.name; mode='AlreadyStopped'; snapshot=@()
            rootIdentity=$null; listenerIdentity=$null; port=[int]$Service.port
            recordedRootProcessId=[int]$Service.rootProcessId
            recordedListenerProcessId=[int]$Service.listenerProcessId
            recordedRootStartTimeUtcTicks=[long]$Service.rootStartTimeUtcTicks
            recordedListenerStartTimeUtcTicks=[long]$Service.listenerStartTimeUtcTicks
            expectedRootProcessName=[string]$Service.processName
            expectedListenerProcessName=[string]$Service.listenerProcessName
        }
    }
    if (-not $root -and $listener) {
        if (Get-Process -Id ([int]$Service.rootProcessId) -ErrorAction SilentlyContinue) {
            throw ('Root PID was reused: ' + $Service.name)
        }
        if (-not $portListener -or -not (Test-ProcessDescendantOf $listener.Id ([int]$Service.rootProcessId))) {
            throw ('Listener ownership cannot be proven after root exit: ' + $Service.name)
        }
        return [pscustomobject]@{
            serviceName=$Service.name; mode='ListenerTree'; snapshot=(Get-ProcessTreeSnapshot $listener.Id)
            rootIdentity=$null; listenerIdentity=(Get-ProcessIdentity $listener.Id); port=[int]$Service.port
        }
    }
    throw ('Root exists but listener is absent: ' + $Service.name)
}

function Invoke-ManagedServiceStopPlan([object]$Plan) {
    Assert-ManagedServiceStopPlanCurrent $Plan
    if ($Plan.mode -eq 'AlreadyStopped') { return 'already stopped' }
    foreach ($entry in $Plan.snapshot) {
        if (-not (Test-ProcessIdentity $entry)) { throw ('Stop plan identity changed: PID ' + $entry.ProcessId) }
    }
    Stop-ProcessTreeSnapshot $Plan.snapshot
    foreach ($entry in $Plan.snapshot) {
        if (Test-ProcessIdentity $entry) { throw ('Stop plan process remains: PID ' + $entry.ProcessId) }
    }
    if (Get-PortListener ([int]$Plan.port)) { throw ('Port remains occupied after stop: ' + $Plan.port) }
    return 'stopped'
}

function Assert-ManagedServiceStopPlanCurrent([object]$Plan) {
    if ($Plan.mode -eq 'AlreadyStopped') {
        $recordedRoot = Get-Process -Id ([int]$Plan.recordedRootProcessId) -ErrorAction SilentlyContinue
        $recordedListener = Get-Process -Id ([int]$Plan.recordedListenerProcessId) -ErrorAction SilentlyContinue
        if ($recordedRoot -or $recordedListener -or (Get-PortListener ([int]$Plan.port))) {
            throw ('Already-stopped plan changed: ' + $Plan.serviceName)
        }
        return
    }
    foreach ($entry in $Plan.snapshot) {
        if (-not (Test-ProcessIdentity $entry)) {
            throw ('Stop plan identity changed: PID ' + $entry.ProcessId)
        }
    }
    if (-not (Test-ProcessIdentity $Plan.listenerIdentity)) {
        throw ('Stop plan listener identity changed: ' + $Plan.serviceName)
    }
    $portListener = Get-PortListener ([int]$Plan.port)
    if (-not $portListener -or $portListener.ProcessId -ne [int]$Plan.listenerIdentity.ProcessId) {
        throw ('Stop plan fixed port changed: ' + $Plan.serviceName)
    }
    $snapshotIds = @($Plan.snapshot | ForEach-Object { [int]$_.ProcessId })
    if ($snapshotIds -notcontains [int]$Plan.listenerIdentity.ProcessId) {
        throw ('Stop plan no longer contains its listener: ' + $Plan.serviceName)
    }
    $listenerIsDescendant = Test-ProcessDescendantOf `
        ([int]$Plan.listenerIdentity.ProcessId) ([int]$Plan.rootIdentity.ProcessId)
    if ($Plan.mode -eq 'RootTree' -and -not $listenerIsDescendant) {
        throw ('Stop plan listener relationship changed: ' + $Plan.serviceName)
    }
}

function Assert-AllManagedStopPlansCurrent([object[]]$Plans) {
    foreach ($plan in $Plans) { Assert-ManagedServiceStopPlanCurrent $plan }
}

function Stop-ProcessTreeSnapshot([object[]]$Snapshot) {
    foreach ($entry in @($Snapshot | Sort-Object Depth -Descending)) {
        if (-not (Test-ProcessIdentity $entry)) {
            if (Get-Process -Id ([int]$entry.ProcessId) -ErrorAction SilentlyContinue) {
                throw ('Process identity changed before stop: PID ' + $entry.ProcessId)
            }
            continue
        }
        Stop-Process -Id ([int]$entry.ProcessId) -ErrorAction SilentlyContinue
        $deadline = [DateTime]::UtcNow.AddSeconds(2)
        do {
            if (-not (Test-ProcessIdentity $entry)) { break }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $deadline)
        if (Test-ProcessIdentity $entry) { Stop-Process -Id ([int]$entry.ProcessId) -Force }
    }
    Start-Sleep -Milliseconds 200
}

function Stop-ManagedStartupAttempt([object]$Attempt) {
    if ($Attempt.identityComplete -ne $true) {
        if (Get-Process -Id ([int]$Attempt.rootProcessId) -ErrorAction SilentlyContinue) {
            throw ('Startup PID was recorded but identity was not fully established: ' + $Attempt.name)
        }
        return 'already stopped'
    }
    $rootExpected = [pscustomobject]@{
        ProcessId=[int]$Attempt.rootProcessId
        ProcessName=[string]$Attempt.expectedRootProcessName
        StartTimeUtcTicks=[long]$Attempt.rootStartTimeUtcTicks
    }
    $root = Get-Process -Id $rootExpected.ProcessId -ErrorAction SilentlyContinue
    if ($root -and -not (Test-ProcessIdentity $rootExpected)) {
        throw ('Startup root identity changed: ' + $Attempt.name)
    }
    $listener = Get-PortListener ([int]$Attempt.port)
    if (-not $Attempt.listenerProcessId -and $listener) {
        if ($root -or -not (Get-Process -Id $rootExpected.ProcessId -ErrorAction SilentlyContinue)) {
            if ($listener.ProcessName -ieq [string]$Attempt.expectedListenerProcessName -and
                    (Test-ProcessDescendantOf $listener.ProcessId $rootExpected.ProcessId)) {
                $Attempt.listenerProcessId = $listener.ProcessId
                $Attempt.listenerStartTimeUtcTicks = $listener.StartTimeUtcTicks
                $Attempt.listenerProcessName = $listener.ProcessName
            } else { throw ('Unknown listener occupies startup port ' + $Attempt.port) }
        }
    }
    $listenerProcess = $null
    if ($Attempt.listenerProcessId) {
        $listenerProcess = Get-Process -Id ([int]$Attempt.listenerProcessId) -ErrorAction SilentlyContinue
        if ($listenerProcess -and
                ($listenerProcess.ProcessName -ine [string]$Attempt.expectedListenerProcessName -or
                 $listenerProcess.StartTime.ToUniversalTime().Ticks -ne [long]$Attempt.listenerStartTimeUtcTicks -or
                 -not $listener -or $listener.ProcessId -ne [int]$Attempt.listenerProcessId -or
                 -not (Test-ProcessDescendantOf $listenerProcess.Id $rootExpected.ProcessId))) {
            throw ('Startup listener identity changed: ' + $Attempt.name)
        }
    }
    if (-not $root -and -not $listenerProcess) { return 'already stopped' }
    if ($root) { $snapshot = Get-ProcessTreeSnapshot $rootExpected.ProcessId }
    else { $snapshot = Get-ProcessTreeSnapshot $listenerProcess.Id }
    Stop-ProcessTreeSnapshot $snapshot
    foreach ($entry in $snapshot) {
        if (Test-ProcessIdentity $entry) { throw ('Startup process remains: PID ' + $entry.ProcessId) }
    }
    $remainingListener = Get-PortListener ([int]$Attempt.port)
    if ($remainingListener) {
        throw ('A listener remains on startup port ' + $Attempt.port + '; it was not stopped.')
    }
    return 'stopped'
}

function Read-AgentTeamRuntimeState {
    $paths = Get-AgentTeamRuntimePaths
    if (-not (Test-Path -LiteralPath $paths.State)) { return $null }
    $stateText = Get-Content -LiteralPath $paths.State -Raw -ErrorAction Stop
    if ([string]::IsNullOrWhiteSpace($stateText)) { throw 'Runtime state content is empty.' }
    $trimmedStateText = $stateText.TrimStart()
    if (-not $trimmedStateText.StartsWith('{', [StringComparison]::Ordinal)) {
        throw 'Runtime state top level must be a JSON object.'
    }
    $parsedState = $stateText | ConvertFrom-Json -ErrorAction Stop
    if ($null -eq $parsedState -or $parsedState -isnot [pscustomobject]) {
        throw 'Runtime state top level must be a JSON object.'
    }
    return $parsedState
}
function Write-AgentTeamRuntimeState([object]$State, [object]$LockContext) {
    Assert-AgentTeamRuntimeLock $LockContext
    $paths = Get-AgentTeamRuntimePaths
    New-Item -ItemType Directory -Path $paths.Base -Force | Out-Null
    if (Test-Path -LiteralPath $paths.State) { throw 'Refusing to overwrite an existing runtime state.' }
    $temporary = Join-Path $paths.Base ('state.' + [guid]::NewGuid().ToString('N') + '.tmp')
    $moved = $false
    $writeError = $null
    $cleanupError = $null
    try {
        $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $temporary -Encoding UTF8
        Move-Item -LiteralPath $temporary -Destination $paths.State
        $moved = $true
    } catch {
        $writeError = $_.Exception
    } finally {
        if (-not $moved -and (Test-Path -LiteralPath $temporary)) {
            try {
                Remove-Item -LiteralPath $temporary -Force -ErrorAction Stop
                if (Test-Path -LiteralPath $temporary) { throw 'Temporary state file still exists.' }
            } catch { $cleanupError = $_.Exception }
        }
    }
    if ($writeError -or $cleanupError) {
        if ($cleanupError) { throw ('Runtime state write or temporary cleanup failed: ' + $temporary) }
        throw 'Runtime state write failed.'
    }
    if (Test-Path -LiteralPath $temporary) { throw ('Runtime state temporary file remains: ' + $temporary) }
}
function Remove-AgentTeamRuntimeState([guid]$ExpectedRunId, [string]$ExpectedRepositoryRoot,
        [object]$LockContext) {
    Assert-AgentTeamRuntimeLock $LockContext
    $paths = Get-AgentTeamRuntimePaths
    if (-not (Test-Path -LiteralPath $paths.State)) { throw 'Runtime state does not exist.' }
    $current = Read-AgentTeamRuntimeState
    Assert-AgentTeamRuntimeState $current $ExpectedRepositoryRoot
    $actualRunId = [guid]::Empty
    if (-not [guid]::TryParse([string]$current.runId, [ref]$actualRunId) -or
            $actualRunId -ne $ExpectedRunId -or
            [string]$current.repositoryRoot -cne $ExpectedRepositoryRoot) {
        throw 'Runtime state ownership changed; refusing to remove it.'
    }
    Remove-Item -LiteralPath $paths.State -Force -ErrorAction Stop
    if (Test-Path -LiteralPath $paths.State) { throw 'Runtime state removal could not be confirmed.' }
}
function New-SafeLogPaths([string]$RunDirectory,[string]$Name) { return [pscustomobject]@{ Stdout=(Join-Path $RunDirectory ($Name+'.out.log')); Stderr=(Join-Path $RunDirectory ($Name+'.err.log')) } }
function Find-Psql { $command=Get-Command psql.exe -ErrorAction SilentlyContinue; if($command){return $command.Source}; $candidate=Get-ChildItem 'C:\Program Files\PostgreSQL' -Filter psql.exe -Recurse -ErrorAction SilentlyContinue|Select-Object -First 1; if($candidate){return $candidate.FullName}; throw 'psql.exe was not found.' }
function Find-Python([string]$Root) { foreach($path in @((Join-Path $Root 'quant-ai\.venv\Scripts\python.exe'),(Join-Path $Root '.venv\Scripts\python.exe'))){if(Test-Path -LiteralPath $path){return (Resolve-Path $path).Path}}; throw 'An existing Python interpreter was not found.' }
function Find-JavaJar([string]$Root) { $jars=@(Get-ChildItem (Join-Path $Root 'quant-server\target') -Filter '*.jar' -File|Where-Object{$_.Name -notlike 'original-*' -and $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*'});if($jars.Count-ne 1){throw 'Expected exactly one executable quant-server JAR.'};return $jars[0].FullName }
function Find-RequiredCommand([string]$Name) { $command=Get-Command $Name -ErrorAction SilentlyContinue;if(-not $command){throw ($Name+' was not found.')};return $command.Source }
