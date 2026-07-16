param([switch]$NonInteractive)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'agent-team-local-common.ps1')

$repositoryRoot = Get-AgentTeamRepositoryRoot
$runtimePaths = Get-AgentTeamRuntimePaths
$duplicateSnapshots = @()
$sensitiveNames = @()
$startupAttempts = New-Object 'System.Collections.Generic.List[object]'
$runDirectory = $null
$runtimeState = $null
$existingState = $null
$reuseExisting = $false
$mainSucceeded = $false
$environmentRestored = $false
$failureReason = $null
$runtimeLock = $null

function Test-AllRuntimePidsAbsent($State) {
    foreach ($service in @($State.services.python, $State.services.java, $State.services.vue)) {
        if (Get-Process -Id ([int]$service.rootProcessId) -ErrorAction SilentlyContinue) { return $false }
        if (Get-Process -Id ([int]$service.listenerProcessId) -ErrorAction SilentlyContinue) { return $false }
    }
    return $true
}

function Assert-RuntimeHealthy($State) {
    foreach ($service in @($State.services.python, $State.services.java, $State.services.vue)) {
        $identity = Test-ManagedServiceIdentity $service
        if (-not $identity.RootExists -or -not $identity.ListenerExists) {
            throw ('Managed service is not fully running: ' + $service.name)
        }
    }
    if (-not (Wait-HttpHealth 'http://127.0.0.1:8001/health' 3 {
                param($response)
                ($response.Content | ConvertFrom-Json).status -eq 'UP'
            })) { throw 'Python health check failed for existing runtime.' }
    if (-not (Wait-HttpHealth 'http://127.0.0.1:8080/api/health' 3 {
                param($response)
                $json = $response.Content | ConvertFrom-Json
                $json.success -and $json.data.status -eq 'UP' -and $json.data.database -eq 'UP'
            })) { throw 'Java health check failed for existing runtime.' }
    if (-not (Wait-HttpHealth 'http://127.0.0.1:5173/' 3 { param($response) $true })) {
        throw 'Vue health check failed for existing runtime.'
    }
    if (-not (Wait-HttpHealth 'http://127.0.0.1:5173/api/health' 3 {
                param($response)
                $json = $response.Content | ConvertFrom-Json
                $json.success -and $json.data.status -eq 'UP' -and $json.data.database -eq 'UP'
            })) { throw 'Vue proxy health check failed for existing runtime.' }
}

function Invoke-StartupCleanup {
    $errors = New-Object 'System.Collections.Generic.List[string]'
    $orderedNames = @('vue','java','python')
    foreach ($attemptName in $orderedNames) {
        foreach ($attempt in @($startupAttempts | Where-Object { $_.name -eq $attemptName })) {
            try { [void](Stop-ManagedStartupAttempt $attempt) }
            catch { $errors.Add($attempt.name + ': ' + $_.Exception.Message) }
        }
    }
    foreach ($attempt in $startupAttempts) {
        if ($attempt.identityComplete -ne $true) {
            if (Get-Process -Id ([int]$attempt.rootProcessId) -ErrorAction SilentlyContinue) {
                $errors.Add('startup PID recorded but identity incomplete: ' + $attempt.name +
                    ' PID ' + $attempt.rootProcessId)
            }
            continue
        }
        $rootIdentity = [pscustomobject]@{
            ProcessId=$attempt.rootProcessId
            ProcessName=$attempt.expectedRootProcessName
            StartTimeUtcTicks=$attempt.rootStartTimeUtcTicks
        }
        if (Test-ProcessIdentity $rootIdentity) {
            $errors.Add('managed root remains: ' + $attempt.name + ' PID ' + $attempt.rootProcessId)
        }
        if ($attempt.listenerProcessId) {
            $listenerIdentity = [pscustomobject]@{
                ProcessId=$attempt.listenerProcessId
                ProcessName=$attempt.expectedListenerProcessName
                StartTimeUtcTicks=$attempt.listenerStartTimeUtcTicks
            }
            if (Test-ProcessIdentity $listenerIdentity) {
                $errors.Add('managed listener remains: ' + $attempt.name + ' PID ' + $attempt.listenerProcessId)
            }
        }
        $listener = Get-PortListener ([int]$attempt.port)
        if ($listener -and $attempt.listenerProcessId -and
                $listener.ProcessId -eq [int]$attempt.listenerProcessId) {
            $errors.Add('managed listener remains on port ' + $attempt.port)
        } elseif ($listener) {
            $errors.Add('unknown listener occupies port ' + $attempt.port + '; it was not stopped')
        }
    }
    if ($errors.Count -gt 0) { return $errors.ToArray() }
    return @()
}

try { $runtimeLock = Enter-AgentTeamRuntimeMutex 10 }
catch {
    Write-Output 'FAILURE_STAGE=RUNTIME_LOCK'
    Write-Output 'FAILURE_REASON=The agent team runtime lock could not be acquired.'
    Write-Output 'CLEANUP_SUCCEEDED=True'
    exit 1
}

try {
try {
    $duplicateSnapshots = Normalize-DuplicateProcessEnvironmentNames
    $sensitiveNames = Get-SensitiveEnvironmentNames
    try { $existingState = Read-AgentTeamRuntimeState }
    catch { throw 'Runtime state could not be read or parsed.' }

    if ($existingState) {
        Assert-AgentTeamRuntimeState $existingState $repositoryRoot
        if (Test-AllRuntimePidsAbsent $existingState) {
            $portsFree = $true
            foreach ($port in 8001, 8080, 5173) {
                if (Get-PortListener $port) { $portsFree = $false }
            }
            if (-not $portsFree) { throw 'A fixed port is occupied while the recorded runtime is absent.' }
            Remove-AgentTeamRuntimeState ([guid]$existingState.runId) $repositoryRoot $runtimeLock
            $existingState = $null
        } else {
            Assert-RuntimeHealthy $existingState
            $reuseExisting = $true
            $mainSucceeded = $true
        }
    }

    if (-not $reuseExisting) {
        foreach ($port in 8001, 8080, 5173) {
            $listener = Get-PortListener $port
            if ($listener) {
                throw ('Unknown listener on port ' + $port + ' PID ' + $listener.ProcessId +
                    ' process ' + $listener.ProcessName + ' started ' +
                    ([DateTime]::new($listener.StartTimeUtcTicks, [DateTimeKind]::Utc).ToString('o')))
            }
        }

        $configuredUrl = Get-ProcessEnvironmentVariable 'STOCK_QUANT_TEST_DB_URL'
        if ($configuredUrl -and $configuredUrl -cne 'jdbc:postgresql://127.0.0.1:5432/stock_quant_test') {
            throw 'The configured test database URL is not allowed.'
        }
        $configuredUser = Get-ProcessEnvironmentVariable 'STOCK_QUANT_TEST_DB_USERNAME'
        if ($configuredUser -and $configuredUser -cne 'stock_quant_test') {
            throw 'The configured test database username is not allowed.'
        }
        $databasePassword = Get-ProcessEnvironmentVariable 'STOCK_QUANT_TEST_DB_PASSWORD'
        if ([string]::IsNullOrWhiteSpace($databasePassword)) {
            if ($NonInteractive) { throw 'The test database password is required in NonInteractive mode.' }
            $securePassword = Read-Host 'Enter the stock_quant_test password' -AsSecureString
            $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
            try { $databasePassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer) }
        }
        if ([string]::IsNullOrWhiteSpace($databasePassword)) { throw 'The test database password is empty.' }

        $psql = Find-Psql
        $identity = Invoke-WithTemporaryProcessEnvironment $sensitiveNames @{ PGPASSWORD=$databasePassword } {
            $result = & $psql -h 127.0.0.1 -p 5432 -U stock_quant_test -d stock_quant_test -Atqc `
                "select (current_database()='stock_quant_test')::text||','||(current_user='stock_quant_test')::text||','||coalesce((select max(version) from flyway_schema_history where success),'none');"
            if ($LASTEXITCODE -ne 0) { throw 'Dedicated database identity query failed.' }
            return $result
        }
        if ($identity -cne 'true,true,5') { throw 'Dedicated database identity or Flyway version check failed.' }

        $python = Find-Python $repositoryRoot
        Invoke-WithTemporaryProcessEnvironment $sensitiveNames @{} {
            Push-Location (Join-Path $repositoryRoot 'quant-ai')
            try { & $python -c 'import fastapi, uvicorn, pydantic; import app.main' 2>$null }
            finally { Pop-Location }
            if ($LASTEXITCODE -ne 0) { throw 'Python import preflight failed.' }
        }

        $mavenWrapper = (Resolve-Path (Join-Path $repositoryRoot 'mvnw.cmd')).Path
        Invoke-WithTemporaryProcessEnvironment $sensitiveNames @{} {
            & $mavenWrapper -o -pl quant-server -am -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw 'Offline Java package failed.' }
        }
        $jar = Find-JavaJar $repositoryRoot
        [void](Find-RequiredCommand 'java.exe')
        [void](Find-RequiredCommand 'npm.cmd')
        if (-not (Test-Path (Join-Path $repositoryRoot 'quant-web\node_modules'))) {
            throw 'quant-web node_modules is missing.'
        }

        $runId = [guid]::NewGuid().ToString('D')
        $runDirectory = Join-Path $runtimePaths.Runs $runId
        New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

        $pythonLogs = New-SafeLogPaths $runDirectory 'python'
        $pythonStart = Start-ManagedProcessWithTemporaryEnvironment $sensitiveNames @{} {
            Start-Process powershell.exe -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
                ('& "' + $python + '" -m uvicorn app.main:app --host 127.0.0.1 --port 8001 --workers 1 --access-log')
            ) -WorkingDirectory (Join-Path $repositoryRoot 'quant-ai') `
                -RedirectStandardOutput $pythonLogs.Stdout -RedirectStandardError $pythonLogs.Stderr `
                -PassThru -WindowStyle Hidden
        } $startupAttempts 'python' 8001 'python' $pythonLogs
        $pythonAttempt = $pythonStart.Attempt
        if (-not (Wait-ManagedServiceHealth $pythonAttempt 'http://127.0.0.1:8001/health' 30 {
                    param($response) ($response.Content | ConvertFrom-Json).status -eq 'UP'
                })) { throw 'Python health check failed.' }
        $pythonService = Complete-ManagedServiceRecord $pythonAttempt

        $javaLogs = New-SafeLogPaths $runDirectory 'java'
        $javaEnvironment = @{
            DB_HOST='127.0.0.1'; DB_PORT='5432'; DB_NAME='stock_quant_test'; DB_USER='stock_quant_test'
            DB_PASSWORD=$databasePassword; SERVER_PORT='8080'; AGENT_TEAM_ENABLED='true'
            AGENT_TEAM_BASE_URL='http://127.0.0.1:8001'
        }
        $javaStart = Start-ManagedProcessWithTemporaryEnvironment $sensitiveNames $javaEnvironment {
            Start-Process powershell.exe -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', ('& java.exe -jar "' + $jar + '"')
            ) -WorkingDirectory $runDirectory -RedirectStandardOutput $javaLogs.Stdout `
                -RedirectStandardError $javaLogs.Stderr -PassThru -WindowStyle Hidden
        } $startupAttempts 'java' 8080 'java' $javaLogs
        $javaAttempt = $javaStart.Attempt
        if (-not (Wait-ManagedServiceHealth $javaAttempt 'http://127.0.0.1:8080/api/health' 45 {
                    param($response)
                    $json = $response.Content | ConvertFrom-Json
                    $json.success -and $json.data.status -eq 'UP' -and $json.data.database -eq 'UP'
                })) { throw 'Java health check failed.' }
        $javaService = Complete-ManagedServiceRecord $javaAttempt

        $vueLogs = New-SafeLogPaths $runDirectory 'vue'
        $vueStart = Start-ManagedProcessWithTemporaryEnvironment $sensitiveNames @{} {
            Start-Process powershell.exe -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', '& npm.cmd run dev'
            ) -WorkingDirectory (Join-Path $repositoryRoot 'quant-web') `
                -RedirectStandardOutput $vueLogs.Stdout -RedirectStandardError $vueLogs.Stderr `
                -PassThru -WindowStyle Hidden
        } $startupAttempts 'vue' 5173 'node' $vueLogs
        $vueAttempt = $vueStart.Attempt
        if (-not (Wait-ManagedServiceHealth $vueAttempt 'http://127.0.0.1:5173/' 30 {
                    param($response) $true
                })) {
            throw 'Vue home health check failed.'
        }
        if (-not (Wait-HttpHealth 'http://127.0.0.1:5173/api/health' 30 {
                    param($response)
                    $json = $response.Content | ConvertFrom-Json
                    $json.success -and $json.data.status -eq 'UP' -and $json.data.database -eq 'UP'
                })) { throw 'Vue proxy health check failed.' }
        $vueService = Complete-ManagedServiceRecord $vueAttempt

        $runtimeState = [pscustomobject]@{
            schemaVersion=1; runId=$runId; createdAt=[DateTime]::UtcNow.ToString('o')
            repositoryRoot=$repositoryRoot; logDirectory=$runDirectory
            services=[pscustomobject]@{ python=$pythonService; java=$javaService; vue=$vueService }
        }
        Assert-AgentTeamRuntimeState $runtimeState $repositoryRoot
        $mainSucceeded = $true
    }
} catch {
    $failureReason = $_.Exception.Message
} finally {
    $restoreFailed = $false
    $snapshotFailed = $false
    try { Restore-DuplicateProcessEnvironmentNames $duplicateSnapshots }
    catch { $restoreFailed = $true }
    try {
        if (-not (Test-DuplicateProcessEnvironmentSnapshot $duplicateSnapshots)) {
            $snapshotFailed = $true
        }
    } catch { $snapshotFailed = $true }
    $environmentRestored = (-not $restoreFailed -and -not $snapshotFailed)
    if (-not $environmentRestored -and -not $failureReason) {
        $failureReason = 'Parent process environment restoration failed.'
    }
}

if (-not $mainSucceeded -or -not $environmentRestored) {
    $cleanupErrors = @()
    if (-not $reuseExisting) {
        $cleanupErrors = @(Invoke-StartupCleanup)
    }
    Write-Output 'FAILURE_STAGE=STARTUP'
    Write-Output ('FAILURE_REASON=' + $failureReason)
    if ($runDirectory) { Write-Output ('FAILURE_LOG_DIRECTORY=' + $runDirectory) }
    Write-Output ('CLEANUP_SUCCEEDED=' + ($cleanupErrors.Count -eq 0))
    foreach ($cleanupError in $cleanupErrors) { Write-Warning ('CLEANUP_ERROR=' + $cleanupError) }
    Exit-AgentTeamRuntimeMutex $runtimeLock
    $runtimeLock = $null
    exit 1
}

if ($reuseExisting) {
    Write-Output 'Agent team local services are already running.'
    Write-Output ('RUN_ID=' + $existingState.runId)
    Write-Output ('STATE_FILE=' + $runtimePaths.State)
    Write-Output ('LOG_DIRECTORY=' + $existingState.logDirectory)
    foreach ($service in @($existingState.services.python, $existingState.services.java, $existingState.services.vue)) {
        Write-Output ($service.name.ToUpper() + '_ROOT_PID=' + $service.rootProcessId +
            ',LISTENER_PID=' + $service.listenerProcessId)
    }
    Write-Output 'PYTHON_HEALTH=http://127.0.0.1:8001/health'
    Write-Output 'JAVA_HEALTH=http://127.0.0.1:8080/api/health'
    Write-Output 'VUE_HOME=http://127.0.0.1:5173/'
    Write-Output 'VUE_PROXY_HEALTH=http://127.0.0.1:5173/api/health'
    Write-Output 'AGENT_TEAM_PAGE=http://127.0.0.1:5173/#/agent-team'
    Exit-AgentTeamRuntimeMutex $runtimeLock
    $runtimeLock = $null
    exit 0
}

try {
    Assert-AgentTeamRuntimeState $runtimeState $repositoryRoot
    Assert-RuntimeHealthy $runtimeState
    Write-AgentTeamRuntimeState $runtimeState $runtimeLock
} catch {
    $cleanupErrors = @(Invoke-StartupCleanup)
    Write-Output 'FAILURE_STAGE=STATE_WRITE'
    Write-Output 'FAILURE_REASON=Runtime state could not be written.'
    Write-Output ('FAILURE_LOG_DIRECTORY=' + $runDirectory)
    Write-Output ('CLEANUP_SUCCEEDED=' + ($cleanupErrors.Count -eq 0))
    foreach ($cleanupError in $cleanupErrors) { Write-Warning ('CLEANUP_ERROR=' + $cleanupError) }
    Exit-AgentTeamRuntimeMutex $runtimeLock
    $runtimeLock = $null
    exit 1
}

Write-Output ('RUN_ID=' + $runtimeState.runId)
Write-Output ('STATE_FILE=' + $runtimePaths.State)
Write-Output ('LOG_DIRECTORY=' + $runDirectory)
foreach ($service in @($runtimeState.services.python, $runtimeState.services.java, $runtimeState.services.vue)) {
    Write-Output ($service.name.ToUpper() + '_ROOT_PID=' + $service.rootProcessId +
        ',LISTENER_PID=' + $service.listenerProcessId)
}
Write-Output 'PYTHON_HEALTH=http://127.0.0.1:8001/health'
Write-Output 'JAVA_HEALTH=http://127.0.0.1:8080/api/health'
Write-Output 'VUE_HOME=http://127.0.0.1:5173/'
Write-Output 'VUE_PROXY_HEALTH=http://127.0.0.1:5173/api/health'
Write-Output 'AGENT_TEAM_PAGE=http://127.0.0.1:5173/#/agent-team'
Exit-AgentTeamRuntimeMutex $runtimeLock
$runtimeLock = $null
} finally {
    if ($runtimeLock) { Exit-AgentTeamRuntimeMutex $runtimeLock }
}
