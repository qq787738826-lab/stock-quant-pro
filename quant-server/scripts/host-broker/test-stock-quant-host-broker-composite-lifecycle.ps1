[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$brokerPath = Join-Path $PSScriptRoot 'stock-quant-host-broker.ps1'
$tokens = $null
$parseErrors = $null
$brokerAst = [Management.Automation.Language.Parser]::ParseFile(
    $brokerPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) {
    throw 'COMPOSITE_LIFECYCLE_BROKER_PARSE_FAILED'
}
$testTargetRoot = [IO.Path]::GetFullPath((Join-Path `
    (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'target'))
$testRoot = [IO.Path]::GetFullPath((Join-Path $testTargetRoot `
    ('stock-quant-composite-lifecycle-' +
        [Guid]::NewGuid().ToString('N'))))
$tests = 0

function Set-ActualBrokerFunction([string] $Name) {
    $matches = @($brokerAst.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst]
    }, $true) | Where-Object { $_.Name -ceq $Name })
    if ($matches.Count -ne 1) {
        throw 'COMPOSITE_LIFECYCLE_FUNCTION_NOT_FOUND'
    }
    $body = $matches[0].Body.Extent.Text
    $body = $body.Substring(1, $body.Length - 2)
    Set-Item -Path ("Function:script:$Name") `
        -Value ([scriptblock]::Create($body))
}

function Assert-True([bool] $Condition, [string] $Code) {
    if (-not $Condition) { throw $Code }
}

function Assert-Sequence([string[]] $Expected, [string] $Code) {
    if (($script:events -join ',') -cne ($Expected -join ',')) {
        throw $Code
    }
}

function Expect-SafeFailure([scriptblock] $Action, [string] $Code) {
    try {
        & $Action
        throw 'COMPOSITE_LIFECYCLE_EXPECTED_FAILURE_MISSING'
    } catch {
        if ($_.Exception.Message -cne $Code) { throw }
    }
}

try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null

    Set-ActualBrokerFunction 'Start-ResearchProductionProcess'
    $script:events = @()
    function script:Assert-ResearchProductionBinding { param($Binding) }
    function script:Start-ResearchFastApiProcess {
        param($Binding, [switch] $Recovery)
        $script:events += 'start-fastapi'
        return [pscustomobject]@{
            Process = [pscustomobject]@{ ProcessId = 101 }
        }
    }
    function script:Start-ResearchBackendProcess {
        param($Binding, [switch] $Recovery)
        $script:events += 'start-java'
        return [pscustomobject]@{
            Process = [pscustomobject]@{ Id = 202 }
            Health = [pscustomobject]@{
                data = [pscustomobject]@{
                    components = @([pscustomobject]@{
                        component = 'Database'
                        details = [pscustomobject]@{ schemaVersion = 18 }
                    })
                    scheduler = [pscustomobject]@{ state = 'IDLE' }
                    status = 'HEALTHY'
                }
            }
        }
    }
    function script:Stop-ResearchFastApiProcess {
        param($Managed)
        $script:events += 'rollback-fastapi'
        if ($script:rollbackFailure) { throw 'M6_FASTAPI_STOP_FAILED' }
    }
    $script:rollbackFailure = $false
    $binding = [pscustomobject]@{ GitCommit = ('a' * 40) }
    $started = Start-ResearchProductionProcess -Binding $binding
    Assert-Sequence @('start-fastapi', 'start-java') `
        'COMPOSITE_LIFECYCLE_START_ORDER_FAILED'
    Assert-True ($started.fastApiState -ceq 'RUNNING' -and
        $started.javaState -ceq 'RUNNING' -and
        $started.fastApiPortOpen -and $started.backendPortOpen) `
        'COMPOSITE_LIFECYCLE_START_SUMMARY_FAILED'
    $tests++

    $script:events = @()
    function script:Start-ResearchBackendProcess {
        param($Binding, [switch] $Recovery)
        $script:events += 'start-java'
        throw 'MOCK_JAVA_START_FAILED'
    }
    Expect-SafeFailure {
        Start-ResearchProductionProcess -Binding $binding | Out-Null
    } 'MOCK_JAVA_START_FAILED'
    Assert-Sequence @('start-fastapi', 'start-java', 'rollback-fastapi') `
        'COMPOSITE_LIFECYCLE_JAVA_ROLLBACK_FAILED'
    $tests++

    $script:events = @()
    $script:rollbackFailure = $true
    Expect-SafeFailure {
        Start-ResearchProductionProcess -Binding $binding | Out-Null
    } 'M6_FASTAPI_STOP_FAILED'
    Assert-Sequence @('start-fastapi', 'start-java', 'rollback-fastapi') `
        'COMPOSITE_LIFECYCLE_ROLLBACK_FAILURE_HIDDEN'
    $script:rollbackFailure = $false
    $tests++

    Set-ActualBrokerFunction 'Stop-ResearchProductionProcesses'
    $managedJava = [pscustomobject]@{
        Process = [pscustomobject]@{ ProcessId = 202 }
    }
    $managedFastApi = [pscustomobject]@{
        Process = [pscustomobject]@{ ProcessId = 101 }
    }
    function script:Get-ResearchProductionProcess { return $managedJava }
    function script:Get-ResearchFastApiProcess { return $managedFastApi }
    function script:Get-ResearchProductionPortOwner {
        param([int] $Port)
        if ($Port -eq 8080) { return 202 }
        return 101
    }
    function script:Stop-ResearchBackendProcess {
        param($Managed)
        $script:events += 'stop-java'
        return $true
    }
    function script:Stop-ResearchFastApiProcess {
        param($Managed)
        $script:events += 'stop-fastapi'
    }
    $script:events = @()
    Stop-ResearchProductionProcesses | Out-Null
    Assert-Sequence @('stop-java', 'stop-fastapi') `
        'COMPOSITE_LIFECYCLE_STOP_ORDER_FAILED'
    $tests++

    function script:Get-ResearchFastApiProcess { return $null }
    function script:Get-ResearchProductionPortOwner {
        param([int] $Port)
        if ($Port -eq 8080) { return 202 }
        return 999
    }
    $script:events = @()
    Expect-SafeFailure {
        Stop-ResearchProductionProcesses | Out-Null
    } 'M6_FASTAPI_PORT_OCCUPIED'
    Assert-True ($script:events.Count -eq 0) `
        'COMPOSITE_LIFECYCLE_UNKNOWN_PORT_MUTATED_SERVICE'
    $tests++

    Set-ActualBrokerFunction 'Invoke-ResearchProductionStop'
    $productionAutostartFile = Join-Path $testRoot 'stop.autostart.json'
    $productionRecoveryStatusFile = Join-Path $testRoot 'stop.recovery.json'
    [IO.File]::WriteAllText($productionAutostartFile, '{}',
        [Text.UTF8Encoding]::new($false))
    function script:Get-ResearchProductionProcess { return $null }
    function script:Get-ResearchFastApiProcess { return $null }
    function script:Stop-ResearchProductionProcesses {
        if (Test-Path -LiteralPath $productionAutostartFile) {
            throw 'MOCK_AUTORECOVERY_STILL_ARMED'
        }
        $script:events += 'stop-after-disarm'
        return [pscustomobject]@{ Graceful = $true }
    }
    $script:events = @()
    $stopped = Invoke-ResearchProductionStop `
        -BrokerRequest ([pscustomobject]@{})
    Assert-Sequence @('stop-after-disarm') `
        'COMPOSITE_LIFECYCLE_DISARM_ORDER_FAILED'
    Assert-True ($stopped.autoRecovery -ceq 'DISARMED' -and
        -not $stopped.backendPortOpen -and
        -not $stopped.fastApiPortOpen) `
        'COMPOSITE_LIFECYCLE_STOP_SUMMARY_FAILED'
    $tests++

    Set-ActualBrokerFunction 'Get-ResearchFastApiProcess'
    $productionFastApiPidFile = Join-Path $testRoot 'fastapi.pid.json'
    [IO.File]::WriteAllText($productionFastApiPidFile, '{}',
        [Text.UTF8Encoding]::new($false))
    Expect-SafeFailure { Get-ResearchFastApiProcess | Out-Null } `
        'M6_FASTAPI_PROCESS_STATE_INVALID'
    $tests++

    $staleState = [ordered]@{
        schemaVersion = 'STOCK_QUANT_RESEARCH_FASTAPI_PROCESS_V1'
        processId = 424242
        gitCommit = ('a' * 40)
        pythonPath = 'C:\fixture\python.exe'
        appDirectory = 'C:\fixture\quant-ai'
        commandContract = 'UVICORN_APP_MAIN_V1'
        processCreatedAt = [DateTimeOffset]::UtcNow.ToString('o')
        startedAt = [DateTimeOffset]::UtcNow.ToString('o')
    }
    [IO.File]::WriteAllText($productionFastApiPidFile,
        ($staleState | ConvertTo-Json -Depth 3),
        [Text.UTF8Encoding]::new($false))
    function script:Get-CimInstance {
        [CmdletBinding()]
        param(
            [Parameter(Position = 0)] [string] $ClassName,
            [string] $Filter
        )
        return $null
    }
    $stale = Get-ResearchFastApiProcess
    Assert-True ($null -eq $stale -and
        -not (Test-Path -LiteralPath $productionFastApiPidFile)) `
        'COMPOSITE_LIFECYCLE_STALE_PID_NOT_REMOVED'
    Remove-Item -Path Function:script:Get-CimInstance -Force
    $tests++

    Set-ActualBrokerFunction 'Test-ResearchProductionProcessCreatedAt'
    $createdAt = [DateTimeOffset]::UtcNow
    $cimIdentity = [pscustomobject]@{ CreationDate = $createdAt.UtcDateTime }
    Assert-True (Test-ResearchProductionProcessCreatedAt `
        -Process $cimIdentity -Expected $createdAt.ToString('o')) `
        'COMPOSITE_LIFECYCLE_CIM_CREATION_TIME_REJECTED'
    Assert-True (-not (Test-ResearchProductionProcessCreatedAt `
        -Process $cimIdentity `
        -Expected $createdAt.AddMinutes(-1).ToString('o'))) `
        'COMPOSITE_LIFECYCLE_CIM_PID_REUSE_NOT_REJECTED'
    $tests++

    Set-ActualBrokerFunction 'Test-ResearchProductionNativeProcessCreatedAt'
    $nativeIdentity = [pscustomobject]@{ StartTime = $createdAt.LocalDateTime }
    Assert-True (Test-ResearchProductionNativeProcessCreatedAt `
        -Process $nativeIdentity -Expected $createdAt.ToString('o')) `
        'COMPOSITE_LIFECYCLE_NATIVE_CREATION_TIME_REJECTED'
    Assert-True (-not (Test-ResearchProductionNativeProcessCreatedAt `
        -Process $nativeIdentity `
        -Expected $createdAt.AddMinutes(-1).ToString('o'))) `
        'COMPOSITE_LIFECYCLE_NATIVE_PID_REUSE_NOT_REJECTED'
    $tests++

    $paths = [pscustomobject]@{
        RepositoryRoot = Join-Path $testRoot 'repository'
        TargetRoot = Join-Path $testRoot 'target'
    }
    $python = [IO.Path]::GetFullPath((Join-Path $paths.RepositoryRoot `
        'quant-ai\.venv\Scripts\python.exe'))
    $appDirectory = [IO.Path]::GetFullPath(
        (Join-Path $paths.RepositoryRoot 'quant-ai'))
    $strictFastApiState = [ordered]@{
        schemaVersion = 'STOCK_QUANT_RESEARCH_FASTAPI_PROCESS_V1'
        processId = 101
        gitCommit = ('a' * 40)
        pythonPath = $python
        appDirectory = $appDirectory
        commandContract = 'UVICORN_APP_MAIN_V1'
        processCreatedAt = [DateTimeOffset]::UtcNow.ToString('o')
        startedAt = [DateTimeOffset]::UtcNow.ToString('o')
    }
    [IO.File]::WriteAllText($productionFastApiPidFile,
        ($strictFastApiState | ConvertTo-Json -Depth 3),
        [Text.UTF8Encoding]::new($false))
    $script:mockCimProcess = [pscustomobject]@{
        ProcessId = 101
        Name = 'python.exe'
        ExecutablePath = $python
        CommandLine = '"' + $python + '" -m uvicorn app.main:app ' +
            '--app-dir "' + $appDirectory + '" --host 127.0.0.1 ' +
            '--port 8001 --workers 1 --no-access-log'
        CreationDate = [DateTime]::UtcNow
    }
    function script:Get-CimInstance {
        [CmdletBinding()]
        param(
            [Parameter(Position = 0)] [string] $ClassName,
            [string] $Filter
        )
        return $script:mockCimProcess
    }
    function script:Get-ResearchProductionPythonPath { return $python }
    function script:Test-ResearchProductionProcessCreatedAt { return $true }
    function script:Get-ResearchProductionPortOwner {
        param([int] $Port)
        return 101
    }
    $strictFastApi = Get-ResearchFastApiProcess
    Assert-True ($strictFastApi.Process.ProcessId -eq 101) `
        'COMPOSITE_LIFECYCLE_FASTAPI_IDENTITY_REJECTED'
    $script:mockCimProcess.CommandLine += ' --reload'
    Expect-SafeFailure { Get-ResearchFastApiProcess | Out-Null } `
        'M6_FASTAPI_PROCESS_IDENTITY_INVALID'
    $tests++

    Set-ActualBrokerFunction 'Get-ResearchProductionProcess'
    $productionPidFile = Join-Path $testRoot 'backend.pid.json'
    $java = [IO.Path]::GetFullPath((Join-Path $testRoot 'java\java.exe'))
    $workingDirectory = [IO.Path]::GetFullPath($paths.RepositoryRoot)
    $fixedJar = [IO.Path]::GetFullPath((Join-Path $paths.TargetRoot `
        'quant-server-1.3.1-research-production.jar'))
    $strictJavaState = [ordered]@{
        schemaVersion = 'STOCK_QUANT_RESEARCH_PRODUCTION_PROCESS_V2'
        processId = 202
        gitCommit = ('a' * 40)
        jarPath = $fixedJar
        jarSha256 = ('b' * 64)
        javaPath = $java
        workingDirectory = $workingDirectory
        commandContract = 'JAVA_JAR_V2'
        processCreatedAt = [DateTimeOffset]::UtcNow.ToString('o')
        startedAt = [DateTimeOffset]::UtcNow.ToString('o')
    }
    [IO.File]::WriteAllText($productionPidFile,
        ($strictJavaState | ConvertTo-Json -Depth 3),
        [Text.UTF8Encoding]::new($false))
    $script:mockCimProcess = [pscustomobject]@{
        ProcessId = 202
        Name = 'java.exe'
        ExecutablePath = $java
        CommandLine = '"' + $java + '" "-Dstockquant.runtime.' +
            'working-directory=' + $workingDirectory + '" -jar "' +
            $fixedJar + '"'
        CreationDate = [DateTime]::UtcNow
    }
    function script:Get-ResearchProductionJavaPath { return $java }
    function script:Get-ResearchProductionPortOwner {
        param([int] $Port)
        return 202
    }
    $strictJava = Get-ResearchProductionProcess
    Assert-True ($strictJava.Process.ProcessId -eq 202 -and
        -not $strictJava.Legacy) `
        'COMPOSITE_LIFECYCLE_JAVA_IDENTITY_REJECTED'
    $script:mockCimProcess.CommandLine += ' -Dunexpected=true'
    Expect-SafeFailure { Get-ResearchProductionProcess | Out-Null } `
        'M6_PRODUCTION_PROCESS_IDENTITY_INVALID'
    Remove-Item -Path Function:script:Get-CimInstance -Force
    $tests++

    Set-ActualBrokerFunction 'Invoke-ResearchProductionRecovery'
    $productionAutostartFile = Join-Path $testRoot 'autostart.json'
    $productionRecoveryStatusFile = Join-Path $testRoot 'recovery.json'
    $productionMaximumRestarts = 3
    [IO.File]::WriteAllText($productionAutostartFile, '{}',
        [Text.UTF8Encoding]::new($false))
    function script:Read-ResearchProductionAutostart {
        return [pscustomobject]@{
            RestartCount = 0
            LastRestartAt = 'NONE'
            Binding = [pscustomobject]@{ GitCommit = ('a' * 40) }
        }
    }
    $script:recoveryJava = $null
    $script:recoveryFastApi = $managedFastApi
    function script:Get-ResearchProductionProcess {
        return $script:recoveryJava
    }
    function script:Get-ResearchFastApiProcess {
        return $script:recoveryFastApi
    }
    function script:Test-ResearchFastApiHealth { return $true }
    function script:Write-ResearchProductionAutostart {
        param($Binding, [int] $RestartCount, [string] $LastRestartAt)
        $script:events += 'arm-recovery'
    }
    function script:Stop-ResearchProductionProcesses {
        $script:events += 'stop-composite'
    }
    function script:Start-ResearchProductionProcess {
        param($Binding, [switch] $Recovery)
        if (-not $Recovery) { throw 'MOCK_RECOVERY_FLAG_MISSING' }
        $script:events += 'start-composite'
    }
    function script:ConvertTo-StockQuantSafeCode { param($ErrorValue) 'MOCK' }
    $script:events = @()
    Invoke-ResearchProductionRecovery
    Assert-Sequence @('arm-recovery', 'stop-composite', 'start-composite') `
        'COMPOSITE_LIFECYCLE_WATCHDOG_RECOVERY_FAILED'
    $recovery = Get-Content -LiteralPath $productionRecoveryStatusFile -Raw |
        ConvertFrom-Json
    Assert-True ($recovery.status -ceq 'SUCCEEDED' -and
        $recovery.fastApiState -ceq 'RUNNING' -and
        $recovery.javaState -ceq 'RUNNING') `
        'COMPOSITE_LIFECYCLE_RECOVERY_STATUS_FAILED'
    $tests++

    $script:recoveryJava = [pscustomobject]@{ Legacy = $false }
    $script:recoveryFastApi = $null
    $script:events = @()
    Invoke-ResearchProductionRecovery
    Assert-Sequence @('arm-recovery', 'stop-composite', 'start-composite') `
        'COMPOSITE_LIFECYCLE_FASTAPI_EXIT_RECOVERY_FAILED'
    $tests++

    Set-ActualBrokerFunction 'Invoke-ResearchProductionStart'
    $existingJava = [pscustomobject]@{
        Legacy = $false
        Process = [pscustomobject]@{ ProcessId = 202 }
        State = [pscustomobject]@{
            gitCommit = ('a' * 40)
            jarSha256 = ('b' * 64)
        }
    }
    $existingFastApi = [pscustomobject]@{
        Process = [pscustomobject]@{ ProcessId = 101 }
        State = [pscustomobject]@{ gitCommit = ('a' * 40) }
    }
    function script:Assert-ResearchProductionBinding { param($Binding) }
    function script:Get-ResearchProductionProcess { return $existingJava }
    function script:Get-ResearchFastApiProcess { return $existingFastApi }
    function script:Test-ResearchFastApiHealth { return $true }
    function script:Get-ResearchBackendHealth {
        param($Binding)
        if ([string]$existingFastApi.State.gitCommit -cne
                [string]$Binding.GitCommit) {
            return $null
        }
        return [pscustomobject]@{ healthy = $true }
    }
    function script:Write-ResearchProductionAutostart {
        param($Binding, [int] $RestartCount, [string] $LastRestartAt)
        $script:events += 'arm-autorecovery'
    }
    function script:Start-ResearchProductionProcess {
        param($Binding, [switch] $Recovery)
        $script:events += 'unexpected-cold-start'
    }
    $request = [pscustomobject]@{
        AuthorizationStatus = 'M6_LOCAL_PRODUCTION_APPROVED'
        GitCommit = ('a' * 40)
        JarPath = 'C:\fixture\backend.jar'
        JarSha256 = ('b' * 64)
    }
    $script:events = @()
    $reused = Invoke-ResearchProductionStart -BrokerRequest $request
    Assert-Sequence @('arm-autorecovery') `
        'COMPOSITE_LIFECYCLE_REUSE_FAILED'
    Assert-True ($reused.backendState -ceq 'ALREADY_RUNNING' -and
        $reused.fastApiState -ceq 'RUNNING' -and
        $reused.javaState -ceq 'RUNNING' -and
        $reused.autoRecovery -ceq 'ARMED') `
        'COMPOSITE_LIFECYCLE_REUSE_SUMMARY_FAILED'
    $tests++

    $existingFastApi.State.gitCommit = ('f' * 40)
    $script:events = @()
    Expect-SafeFailure {
        Invoke-ResearchProductionStart -BrokerRequest $request | Out-Null
    } 'M6_PRODUCTION_RUNNING_VERSION_MISMATCH'
    Assert-True ($script:events.Count -eq 0) `
        'COMPOSITE_LIFECYCLE_GIT_MISMATCH_MUTATED_SERVICE'
    $tests++

    Write-Output "COMPOSITE_LIFECYCLE_TESTS=$tests"
    Write-Output 'COMPOSITE_LIFECYCLE_PROVIDER_CALLS=0'
    Write-Output 'COMPOSITE_LIFECYCLE_DATABASE_WRITES=0'
    Write-Output 'COMPOSITE_LIFECYCLE_STATUS=PASS'
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        $safePrefix = $testTargetRoot.TrimEnd('\', '/') +
            [IO.Path]::DirectorySeparatorChar
        if (-not $testRoot.StartsWith($safePrefix,
                [StringComparison]::OrdinalIgnoreCase)) {
            throw 'COMPOSITE_LIFECYCLE_TEST_ROOT_UNSAFE'
        }
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
