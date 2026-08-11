[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force

$paths = Initialize-StockQuantHostBrokerDirectories
$testPrefix = 'stock-quant-host-broker-contract-'
$testRoot = Join-Path $paths.TargetRoot `
    ($testPrefix + [Guid]::NewGuid().ToString('N'))
$jar = Join-Path $testRoot 'day001-test.jar'
$proof = "$jar.f1f-b2-proof.properties"
$authorization = Join-Path $testRoot 'authorization.properties'
$createdFiles = [Collections.Generic.List[string]]::new()
$createdRequestIds = [Collections.Generic.List[string]]::new()
$passed = 0
$heartbeatCreated = $false

function Assert-ThrowsCode {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock] $Action,
        [Parameter(Mandatory = $true)]
        [string] $Expected
    )
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -eq $Expected) {
            $script:passed++
            return
        }
        throw
    }
    throw "EXPECTED_FAILURE_NOT_OBSERVED_$Expected"
}

function New-Values {
    param(
        [string] $Operation = 'CHECK_CREDENTIAL_STATUS',
        [DateTimeOffset] $CreatedAt = [DateTimeOffset]::UtcNow,
        [DateTimeOffset] $ExpiresAt = [DateTimeOffset]::UtcNow.AddMinutes(10)
    )
    $requestId = New-StockQuantHostBrokerRequestId
    $createdRequestIds.Add($requestId)
    [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = $requestId
        'operation' = $Operation
        'git.commit' = ('a' * 40)
        'jar.path' = $jar
        'jar.sha256' = ((Get-FileHash -LiteralPath $jar `
            -Algorithm SHA256).Hash).ToLowerInvariant()
        'authorization.file' = $authorization
        'day001.mode' = 'IDEMPOTENCY_VERIFICATION'
        'security.symbol' = '600000'
        'security.exchange' = 'SSE'
        'trade.date' = '2025-01-03'
        'database.host' = '127.0.0.1'
        'database.port' = '38432'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'schema.name' = 'tushare_research'
        'provider' = 'TUSHARE'
        'provider.endpoints' = 'daily,adj_factor,trade_cal'
        'endpoint.daily.requests' = '1'
        'endpoint.adj_factor.requests' = '1'
        'endpoint.trade_cal.requests' = '1'
        'maximum.provider.requests' = '3'
        'retry.budget' = '0'
        'redirects' = 'NEVER'
        'created.at' = $CreatedAt.ToString('o')
        'expires.at' = $ExpiresAt.ToString('o')
        'execution.source' = 'REDUCED_RESEARCH_MANUAL_DAY001'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
}

function Write-TestAuthorization {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ArtifactHash,
        [System.Collections.IDictionary] $Overrides = @{},
        [string] $OmitKey = '',
        [string[]] $AdditionalLines = @()
    )
    $issued = [DateTimeOffset]::UtcNow.AddSeconds(-2)
    $values = [ordered]@{
        'authorization.status' = 'E2E_DRY_RUN'
        'authorization.version' = `
            'REDUCED_RESEARCH_DAY001_AUTHORIZATION_V1'
        'run.id' = 'RRDAY001_BROKER_PROTOCOL_TEST_0001'
        'git.commit' = ('a' * 40)
        'artifact.sha256' = $ArtifactHash
        'build.proof.path' = $proof
        'provider' = 'TUSHARE'
        'security.symbol' = '600000'
        'security.exchange' = 'SSE'
        'trade.date' = '2025-01-03'
        'day001.mode' = 'IDEMPOTENCY_VERIFICATION'
        'endpoints' = 'daily,adj_factor,trade_cal'
        'endpoint.daily.requests' = '1'
        'endpoint.adj_factor.requests' = '1'
        'endpoint.trade_cal.requests' = '1'
        'maximum.provider.requests' = '3'
        'retry.budget' = '0'
        'redirects' = 'NEVER'
        'database.host' = '127.0.0.1'
        'database.port' = '38433'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'database.ssl.mode' = 'DISABLE_LOCAL_ONLY'
        'schema.name' = 'tushare_research'
        'issued.at' = $issued.ToString('o')
        'expires.at' = $issued.AddMinutes(20).ToString('o')
        'purpose' = '3A_R3B_RR_DAY001'
        'execution.source' = 'REDUCED_RESEARCH_MANUAL_DAY001'
        'user.approval.reference' = 'NOT_APPLICABLE_E2E_DRY_RUN'
    }
    foreach ($key in $Overrides.Keys) { $values[$key] = $Overrides[$key] }
    $lines = @($values.Keys | Where-Object { $_ -cne $OmitKey } |
        ForEach-Object { "$_=$($values[$_])" })
    $lines += $AdditionalLines
    $content = $lines -join "`n"
    [IO.File]::WriteAllText($authorization, $content + "`n",
        [Text.UTF8Encoding]::new($false))
}

function Write-RawValues {
    param(
        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary] $Values,
        [string[]] $AdditionalLines = @()
    )
    $requestId = [string]$Values['request.id']
    $path = Join-Path $paths.Requests "$requestId.request.properties"
    $lines = @($Values.Keys | ForEach-Object { "$_=$($Values[$_])" })
    $lines += $AdditionalLines
    [IO.File]::WriteAllText(
        $path, ($lines -join "`n") + "`n",
        [Text.UTF8Encoding]::new($false))
    $createdFiles.Add($path)
    return $path
}

try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    [IO.File]::WriteAllBytes($jar, [Text.Encoding]::UTF8.GetBytes(
        'PACKAGED_FAKE_DAY001_JAR'))
    [IO.File]::WriteAllText($proof, 'proof.mode=E2E_DRY_RUN' + "`n",
        [Text.UTF8Encoding]::new($false))
    $artifactHash = ((Get-FileHash -LiteralPath $jar `
        -Algorithm SHA256).Hash).ToLowerInvariant()
    Write-TestAuthorization -ArtifactHash $artifactHash

    $valid = New-Values
    $validPath = Write-StockQuantHostBrokerRequest -Values $valid
    $createdFiles.Add($validPath)
    $parsed = Read-StockQuantHostBrokerRequest -Path $validPath
    if ($parsed.RequestId -ne $valid['request.id'] -or
        $parsed.Operation -ne 'CHECK_CREDENTIAL_STATUS' -or
        $parsed.AuthorizationStatus -ne 'E2E_DRY_RUN' -or
        -not $parsed.NoRetry) {
        throw 'VALID_REQUEST_ROUND_TRIP_FAILED'
    }
    $passed++

    $diagnosticSourceId = New-StockQuantHostBrokerRequestId
    $createdRequestIds.Add($diagnosticSourceId)
    Write-StockQuantHostBrokerResult -Result ([ordered]@{
        requestId = $diagnosticSourceId
        operation = 'RUN_DAY001'
        status = 'FAILED'
        stage = 'DAY001_RUNNER'
        reason = 'STOCK_QUANT_LOCAL_AUTOMATION_FAILED'
        gitCommit = ('a' * 40)
        providerCallCount = 1
        retryCount = 0
        noRetry = $true
        startedAt = [DateTimeOffset]::UtcNow.AddSeconds(-2).ToString('o')
        completedAt = [DateTimeOffset]::UtcNow.AddSeconds(-1).ToString('o')
        summary = $null
    }) | Out-Null
    $diagnosticDay001Path = Join-Path $paths.Results `
        "$diagnosticSourceId.day001.json"
    $diagnosticDay001 = [ordered]@{
        status = 'FAILED_VALIDATION'
        safeFailureCode = 'TUSHARE_API_ERROR_40101'
        providerCallCount = 1
        retryCount = 0
    } | ConvertTo-Json
    [IO.File]::WriteAllText(
        $diagnosticDay001Path, $diagnosticDay001 + "`n",
        [Text.UTF8Encoding]::new($false))

    $diagnostic = New-Values -Operation 'DIAGNOSE_TUSHARE_CREDENTIAL'
    $diagnostic['authorization.file'] = 'NONE'
    $diagnostic['source.request.id'] = $diagnosticSourceId
    $diagnosticPath = Write-StockQuantHostBrokerRequest -Values $diagnostic
    $createdFiles.Add($diagnosticPath)
    $parsedDiagnostic = Read-StockQuantHostBrokerRequest `
        -Path $diagnosticPath
    if ($parsedDiagnostic.Operation -ne 'DIAGNOSE_TUSHARE_CREDENTIAL' -or
        $null -ne $parsedDiagnostic.AuthorizationFile -or
        $parsedDiagnostic.AuthorizationStatus -ne
            'NOT_REQUIRED_ZERO_PROVIDER_DIAGNOSTIC') {
        throw 'DIAGNOSTIC_REQUEST_ROUND_TRIP_FAILED'
    }
    $passed++

    $diagnosticWithAuthorization = New-Values `
        -Operation 'DIAGNOSE_TUSHARE_CREDENTIAL'
    $diagnosticWithAuthorization['source.request.id'] = $diagnosticSourceId
    $diagnosticWithAuthorizationPath = Write-RawValues `
        -Values $diagnosticWithAuthorization
    Assert-ThrowsCode {
        Read-StockQuantHostBrokerRequest $diagnosticWithAuthorizationPath
    } 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_MODE_INVALID'

    Write-TestAuthorization -ArtifactHash $artifactHash `
        -OmitKey 'redirects'
    $missingAuthorizationPath = Write-RawValues -Values (New-Values)
    Assert-ThrowsCode {
        Read-StockQuantHostBrokerRequest $missingAuthorizationPath
    } 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_INVALID'

    Write-TestAuthorization -ArtifactHash $artifactHash `
        -AdditionalLines @('unexpected.field=forbidden')
    $unknownAuthorizationPath = Write-RawValues -Values (New-Values)
    Assert-ThrowsCode {
        Read-StockQuantHostBrokerRequest $unknownAuthorizationPath
    } 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_INVALID'

    Write-TestAuthorization -ArtifactHash $artifactHash `
        -Overrides @{ 'git.commit' = ('b' * 40) }
    $mismatchedAuthorizationPath = Write-RawValues -Values (New-Values)
    Assert-ThrowsCode {
        Read-StockQuantHostBrokerRequest $mismatchedAuthorizationPath
    } 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_INVALID'

    Write-TestAuthorization -ArtifactHash $artifactHash
    $wrongAuthorizationModePath = Write-RawValues `
        -Values (New-Values -Operation 'RUN_DAY001')
    Assert-ThrowsCode {
        Read-StockQuantHostBrokerRequest $wrongAuthorizationModePath
    } 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_MODE_INVALID'

    $injection = New-Values
    $injectionPath = Write-RawValues -Values $injection `
        -AdditionalLines @('command.text=Remove-Item C:\')
    Assert-ThrowsCode { Read-StockQuantHostBrokerRequest $injectionPath } `
        'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'

    $escape = New-Values
    $escape['jar.path'] = Join-Path $paths.RepositoryRoot 'pom.xml'
    $escapePath = Write-RawValues -Values $escape
    Assert-ThrowsCode { Read-StockQuantHostBrokerRequest $escapePath } `
        'STOCK_QUANT_HOST_BROKER_JAR_PATH_INVALID'

    $unknown = New-Values -Operation 'EXECUTE_COMMAND'
    $unknownPath = Write-RawValues -Values $unknown
    Assert-ThrowsCode { Read-StockQuantHostBrokerRequest $unknownPath } `
        'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'

    $wrongScope = New-Values
    $wrongScope['security.symbol'] = '000001'
    $wrongScopePath = Write-RawValues -Values $wrongScope
    Assert-ThrowsCode { Read-StockQuantHostBrokerRequest $wrongScopePath } `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'

    $expiredCreated = [DateTimeOffset]::UtcNow.AddMinutes(-20)
    $expired = New-Values -CreatedAt $expiredCreated `
        -ExpiresAt $expiredCreated.AddMinutes(10)
    $expiredPath = Write-RawValues -Values $expired
    Assert-ThrowsCode { Read-StockQuantHostBrokerRequest $expiredPath } `
        'STOCK_QUANT_HOST_BROKER_REQUEST_EXPIRED'

    $duplicate = New-Values
    $duplicatePath = Write-RawValues -Values $duplicate `
        -AdditionalLines @('operation=RUN_FAKE_E2E')
    Assert-ThrowsCode { Read-StockQuantHostBrokerRequest $duplicatePath } `
        'STOCK_QUANT_HOST_BROKER_REQUEST_LINE_INVALID'

    $secretField = New-Values
    $secretPath = Write-RawValues -Values $secretField `
        -AdditionalLines @('provider.token=forbidden-test-value')
    Assert-ThrowsCode { Read-StockQuantHostBrokerRequest $secretPath } `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SECRET_FIELD_FORBIDDEN'

    Assert-ThrowsCode {
        Assert-StockQuantHostBrokerRequestIdAvailable `
            -RequestId $valid['request.id']
    } 'STOCK_QUANT_HOST_BROKER_REQUEST_ID_ALREADY_USED'

    $resultId = New-StockQuantHostBrokerRequestId
    $createdRequestIds.Add($resultId)
    Assert-ThrowsCode {
        Write-StockQuantHostBrokerResult -Result ([ordered]@{
            requestId = $resultId
            operation = 'RUN_DAY001'
            status = 'FAILED'
            stage = 'TEST'
            reason = 'SAFE_TEST_FAILURE'
            token = 'forbidden-test-value'
        })
    } 'STOCK_QUANT_HOST_BROKER_RESULT_SECRET_FIELD_FORBIDDEN'

    if (Test-Path -LiteralPath $paths.Heartbeat -PathType Leaf) {
        throw 'BROKER_PROTOCOL_HEARTBEAT_ALREADY_EXISTS'
    }
    $heartbeatNow = [DateTimeOffset]::UtcNow
    Write-StockQuantHostBrokerHeartbeat -GitCommit ('a' * 40) `
        -WindowsUser ([Security.Principal.WindowsIdentity]::GetCurrent().Name) `
        -ProcessId $PID -StartedAt $heartbeatNow.AddSeconds(-1) `
        -State IDLE -Now $heartbeatNow | Out-Null
    $heartbeatCreated = $true
    $heartbeatNow = $heartbeatNow.AddMilliseconds(10)
    Write-StockQuantHostBrokerHeartbeat -GitCommit ('a' * 40) `
        -WindowsUser ([Security.Principal.WindowsIdentity]::GetCurrent().Name) `
        -ProcessId $PID -StartedAt $heartbeatNow.AddSeconds(-1) `
        -State BUSY -Now $heartbeatNow | Out-Null
    $sharedRead = [IO.FileStream]::new(
        $paths.Heartbeat, [IO.FileMode]::Open, [IO.FileAccess]::Read,
        ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try {
        $heartbeatNow = $heartbeatNow.AddMilliseconds(10)
        Write-StockQuantHostBrokerHeartbeat -GitCommit ('a' * 40) `
            -WindowsUser `
                ([Security.Principal.WindowsIdentity]::GetCurrent().Name) `
            -ProcessId $PID -StartedAt $heartbeatNow.AddSeconds(-1) `
            -State IDLE -Now $heartbeatNow | Out-Null
    } finally {
        $sharedRead.Dispose()
    }
    $heartbeat = Read-StockQuantHostBrokerHeartbeat `
        -ExpectedGitCommit ('a' * 40) -Now $heartbeatNow
    if ($heartbeat.state -ne 'IDLE' -or [int]$heartbeat.processId -ne $PID -or
        @(Get-ChildItem -LiteralPath $paths.Base -File `
            -Filter '.heartbeat.*.tmp').Count -ne 0) {
        throw 'BROKER_PROTOCOL_HEARTBEAT_ROUND_TRIP_FAILED'
    }
    $passed++

    Assert-ThrowsCode {
        Read-StockQuantHostBrokerHeartbeat -ExpectedGitCommit ('b' * 40) `
            -Now $heartbeatNow
    } 'HOST_BROKER_NOT_RUNNING'

    Assert-ThrowsCode {
        Read-StockQuantHostBrokerHeartbeat -ExpectedGitCommit ('a' * 40) `
            -Now $heartbeatNow.AddSeconds(10)
    } 'HOST_BROKER_NOT_RUNNING'

    $invalidHeartbeat = [ordered]@{
        schemaVersion = 'STOCK_QUANT_HOST_BROKER_HEARTBEAT_V1'
        brokerVersion = 'STOCK_QUANT_HOST_BROKER_RESIDENT_V1'
        gitCommit = ('a' * 40)
        windowsUser = 'SAFE_TEST_USER'
        processId = $PID
        startedAt = $heartbeatNow.AddSeconds(-1).ToString('o')
        lastHeartbeat = $heartbeatNow.ToString('o')
        state = 'IDLE'
        unexpectedField = 'FORBIDDEN'
    } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($paths.Heartbeat, $invalidHeartbeat + "`n",
        [Text.UTF8Encoding]::new($false))
    Assert-ThrowsCode {
        Read-StockQuantHostBrokerHeartbeat -ExpectedGitCommit ('a' * 40) `
            -Now $heartbeatNow
    } 'HOST_BROKER_NOT_RUNNING'

    Remove-Item -LiteralPath $paths.Heartbeat -Force
    $heartbeatCreated = $false
    Assert-ThrowsCode {
        Read-StockQuantHostBrokerHeartbeat -ExpectedGitCommit ('a' * 40) `
            -Now $heartbeatNow
    } 'HOST_BROKER_NOT_RUNNING'

    Write-Output "STOCK_QUANT_HOST_BROKER_PROTOCOL_TESTS=$passed/0/0/0"
    if ($passed -ne 21) { throw 'BROKER_PROTOCOL_TEST_COUNT_INVALID' }
} finally {
    if ($heartbeatCreated -and
        (Test-Path -LiteralPath $paths.Heartbeat -PathType Leaf)) {
        Remove-Item -LiteralPath $paths.Heartbeat -Force
    }
    foreach ($id in $createdRequestIds) {
        foreach ($directory in @($paths.Requests, $paths.Results)) {
            foreach ($generated in @(Get-ChildItem -LiteralPath $directory `
                    -File -Filter "$id.*" -ErrorAction SilentlyContinue)) {
                $resolved = [IO.Path]::GetFullPath($generated.FullName)
                $directoryPrefix = $directory.TrimEnd('\') + '\'
                if (-not $resolved.StartsWith(
                        $directoryPrefix,
                        [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'BROKER_PROTOCOL_TEST_CLEANUP_PATH_INVALID'
                }
                Remove-Item -LiteralPath $resolved -Force
            }
        }
    }
    foreach ($path in $createdFiles) {
        if (Test-Path -LiteralPath $path) {
            $resolved = [IO.Path]::GetFullPath($path)
            $requestsPrefix = $paths.Requests.TrimEnd('\') + '\'
            if (-not $resolved.StartsWith(
                    $requestsPrefix, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'BROKER_PROTOCOL_TEST_CLEANUP_PATH_INVALID'
            }
            Remove-Item -LiteralPath $resolved -Force
        }
    }
    if (Test-Path -LiteralPath $testRoot) {
        $resolvedRoot = [IO.Path]::GetFullPath($testRoot).TrimEnd('\')
        $targetPrefix = $paths.TargetRoot.TrimEnd('\') + '\'
        if (-not $resolvedRoot.StartsWith(
                $targetPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($resolvedRoot).StartsWith($testPrefix)) {
            throw 'BROKER_PROTOCOL_TEST_CLEANUP_PATH_INVALID'
        }
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}
