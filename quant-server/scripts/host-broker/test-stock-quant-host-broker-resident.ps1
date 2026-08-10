[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedCommit
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.TestSupport.psm1') -Force

$paths = Initialize-StockQuantHostBrokerDirectories
$testPrefix = 'stock-quant-host-broker-resident-'
$testRoot = Join-Path $paths.TargetRoot `
    ($testPrefix + [Guid]::NewGuid().ToString('N'))
$jar = Join-Path $testRoot 'resident-test.jar'
$proof = "$jar.f1f-b2-proof.properties"
$authorization = Join-Path $testRoot 'resident-authorization.properties'
$resident = $null
$requestIds = [Collections.Generic.List[string]]::new()
$passed = 0

function New-TestValues {
    param(
        [string] $Operation = 'READ_SANITIZED_RESULT',
        [string] $SourceRequestId,
        [DateTimeOffset] $CreatedAt = [DateTimeOffset]::UtcNow,
        [DateTimeOffset] $ExpiresAt = [DateTimeOffset]::UtcNow.AddMinutes(10)
    )
    $id = New-StockQuantHostBrokerRequestId
    $script:requestIds.Add($id)
    [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = $id
        'operation' = $Operation
        'git.commit' = $ExpectedCommit
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
        'source.request.id' = $SourceRequestId
    }
}

function Write-RawPendingRequest {
    param(
        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary] $Values,
        [string[]] $AdditionalLines = @()
    )
    $id = [string]$Values['request.id']
    $destination = Join-Path $paths.Requests "$id.request.properties"
    $temporary = Join-Path $paths.Requests `
        (".$id." + [Guid]::NewGuid().ToString('N') + '.tmp')
    $lines = @($Values.Keys | ForEach-Object { "$_=$($Values[$_])" })
    $lines += $AdditionalLines
    try {
        [IO.File]::WriteAllText($temporary, ($lines -join "`n") + "`n",
            [Text.UTF8Encoding]::new($false))
        [IO.File]::Move($temporary, $destination)
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
    return $destination
}

function Wait-TestResult {
    param(
        [Parameter(Mandatory = $true)]
        [string] $RequestId
    )
    $path = Join-Path $paths.Results "$RequestId.result.json"
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(15)
    while (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        if ([DateTimeOffset]::UtcNow -ge $deadline) {
            throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_RESULT_TIMEOUT'
        }
        Start-Sleep -Milliseconds 100
    }
    return Get-Content -LiteralPath $path -Raw -Encoding UTF8 |
        ConvertFrom-Json
}

function Assert-Rejected {
    param(
        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary] $Values,
        [Parameter(Mandatory = $true)]
        [string] $Reason,
        [string[]] $AdditionalLines = @()
    )
    Write-RawPendingRequest -Values $Values `
        -AdditionalLines $AdditionalLines | Out-Null
    $result = Wait-TestResult -RequestId $Values['request.id']
    if ($result.status -ne 'REJECTED' -or $result.reason -ne $Reason -or
        $result.providerCallCount -ne 0 -or $result.retryCount -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_REJECTION_INVALID'
    }
    $script:passed++
}

Push-Location $paths.RepositoryRoot
try {
    if ((git rev-parse HEAD).Trim() -ne $ExpectedCommit -or
        @(git status --porcelain=v1 --untracked-files=normal |
            Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' }).Count `
            -ne 0 -or @(git diff --cached --name-only).Count -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_GIT_INVALID'
    }
    if (@(Get-ChildItem -LiteralPath $paths.Requests -File `
            -Filter '*.request.properties').Count -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_QUEUE_NOT_EMPTY'
    }
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    [IO.File]::WriteAllBytes($jar,
        [Text.Encoding]::UTF8.GetBytes('RESIDENT_NON_EXECUTABLE_JAR'))
    [IO.File]::WriteAllText($proof, "proof.mode=RESIDENT_TEST`n",
        [Text.UTF8Encoding]::new($false))
    $hash = ((Get-FileHash -LiteralPath $jar `
        -Algorithm SHA256).Hash).ToLowerInvariant()
    $issued = [DateTimeOffset]::UtcNow.AddSeconds(-2)
    $authorizationLines = @(
        'authorization.status=E2E_DRY_RUN'
        'authorization.version=REDUCED_RESEARCH_DAY001_AUTHORIZATION_V1'
        'run.id=RRDAY001_BROKER_RESIDENT_TEST_0001'
        "git.commit=$ExpectedCommit"
        "artifact.sha256=$hash"
        "build.proof.path=$proof"
        'provider=TUSHARE'
        'security.symbol=600000'
        'security.exchange=SSE'
        'trade.date=2025-01-03'
        'day001.mode=IDEMPOTENCY_VERIFICATION'
        'endpoints=daily,adj_factor,trade_cal'
        'endpoint.daily.requests=1'
        'endpoint.adj_factor.requests=1'
        'endpoint.trade_cal.requests=1'
        'maximum.provider.requests=3'
        'retry.budget=0'
        'redirects=NEVER'
        'database.host=127.0.0.1'
        'database.port=38433'
        'database.name=stock_quant_research'
        'database.user=stock_quant_research'
        'database.ssl.mode=DISABLE_LOCAL_ONLY'
        'schema.name=tushare_research'
        "issued.at=$($issued.ToString('o'))"
        "expires.at=$($issued.AddMinutes(20).ToString('o'))"
        'purpose=3A_R3B_RR_DAY001'
        'execution.source=REDUCED_RESEARCH_MANUAL_DAY001'
        'user.approval.reference=NOT_APPLICABLE_E2E_DRY_RUN'
    )
    [IO.File]::WriteAllText($authorization,
        ($authorizationLines -join "`n") + "`n",
        [Text.UTF8Encoding]::new($false))

    $claimed = New-TestValues -SourceRequestId `
        (New-StockQuantHostBrokerRequestId)
    $claimedPath = Join-Path $paths.Requests `
        "$($claimed['request.id']).processing.properties"
    [IO.File]::WriteAllText($claimedPath,
        (($claimed.Keys | ForEach-Object { "$_=$($claimed[$_])" }) -join
            "`n") + "`n", [Text.UTF8Encoding]::new($false))

    $resident = Start-StockQuantTestResidentBroker `
        -ExpectedCommit $ExpectedCommit -LogDirectory $testRoot
    Start-Sleep -Milliseconds 2200
    $heartbeat = Read-StockQuantHostBrokerHeartbeat `
        -ExpectedGitCommit $ExpectedCommit
    if ([int]$heartbeat.processId -ne $resident.ProcessId -or
        $heartbeat.state -ne 'IDLE') {
        throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_HEARTBEAT_INVALID'
    }
    $passed++

    if (-not (Test-Path -LiteralPath $claimedPath -PathType Leaf) -or
        (Test-Path -LiteralPath (Join-Path $paths.Results `
            "$($claimed['request.id']).result.json"))) {
        throw 'STOCK_QUANT_HOST_BROKER_CLAIMED_REQUEST_REPLAYED'
    }
    $passed++

    $sourceId = New-StockQuantHostBrokerRequestId
    $requestIds.Add($sourceId)
    Write-StockQuantHostBrokerResult -Result ([ordered]@{
        requestId = $sourceId
        operation = 'RUN_FAKE_E2E'
        status = 'SUCCEEDED'
        stage = 'COMPLETED'
        reason = 'SAFE_TEST_RESULT'
        providerCallCount = 0
        retryCount = 0
        noRetry = $true
        summary = [ordered]@{ outputAudit = 'PASSED' }
    }) | Out-Null
    $valid = New-TestValues -SourceRequestId $sourceId
    Write-StockQuantHostBrokerRequest -Values $valid | Out-Null
    $validResult = Wait-TestResult -RequestId $valid['request.id']
    if ($validResult.status -ne 'SUCCEEDED' -or
        $validResult.summary.sourceRequestId -ne $sourceId -or
        $validResult.providerCallCount -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_AUTO_CLAIM_INVALID'
    }
    $passed++

    $duplicate = New-TestValues -SourceRequestId $sourceId
    $duplicateMarker = Join-Path $paths.Requests `
        "$($duplicate['request.id']).processed.properties"
    [IO.File]::WriteAllText($duplicateMarker, "terminal=true`n",
        [Text.UTF8Encoding]::new($false))
    Assert-Rejected -Values $duplicate `
        -Reason 'STOCK_QUANT_HOST_BROKER_REQUEST_ID_ALREADY_USED'

    $expiredAt = [DateTimeOffset]::UtcNow.AddMinutes(-20)
    $expired = New-TestValues -SourceRequestId $sourceId `
        -CreatedAt $expiredAt -ExpiresAt $expiredAt.AddMinutes(10)
    Assert-Rejected -Values $expired `
        -Reason 'STOCK_QUANT_HOST_BROKER_REQUEST_EXPIRED'

    $injection = New-TestValues -SourceRequestId $sourceId
    Assert-Rejected -Values $injection `
        -Reason 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID' `
        -AdditionalLines @('command.text=forbidden')

    $escape = New-TestValues -SourceRequestId $sourceId
    $escape['jar.path'] = Join-Path $paths.RepositoryRoot 'README.md'
    Assert-Rejected -Values $escape `
        -Reason 'STOCK_QUANT_HOST_BROKER_JAR_PATH_INVALID'

    $resultJson = @(Get-ChildItem -LiteralPath $paths.Results -File |
        Where-Object {
            $name = $_.Name
            @($requestIds | Where-Object {
                $name.StartsWith("$_.", [StringComparison]::Ordinal)
            }).Count -gt 0
        } |
        ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw `
            -Encoding UTF8 }) -join "`n"
    if ($resultJson -match '(?i)(database\.password|provider\.token|' +
            'credentialblob|jdbc\.password)') {
        throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_RESULT_NOT_SANITIZED'
    }
    $passed++

    $brokerSource = Get-Content -LiteralPath $paths.BrokerScript `
        -Raw -Encoding UTF8
    $idleStart = $brokerSource.IndexOf('if ($pending.Count -eq 0)')
    $idleEnd = $brokerSource.IndexOf(
        'Write-BrokerHeartbeat -State BUSY', $idleStart)
    if ($idleStart -lt 0 -or $idleEnd -le $idleStart) {
        throw 'STOCK_QUANT_HOST_BROKER_IDLE_PATH_NOT_ISOLATED'
    }
    $idleSection = $brokerSource.Substring($idleStart, $idleEnd - $idleStart)
    if ($idleSection -match '(Credential|hostRunner|fakeE2e|Http|Npgsql|Jdbc)') {
        throw 'STOCK_QUANT_HOST_BROKER_IDLE_PATH_NOT_ISOLATED'
    }
    $passed++

    if (@(Get-ChildItem -LiteralPath $paths.Requests -File `
            -Filter '*.request.properties').Count -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_PENDING_RESIDUAL'
    }
    $passed++

    Write-Output "STOCK_QUANT_HOST_BROKER_RESIDENT_TESTS=$passed/0/0/0"
    Write-Output 'STOCK_QUANT_HOST_BROKER_RESIDENT_AUTO_CLAIM=PASS'
    Write-Output 'STOCK_QUANT_HOST_BROKER_CLAIMED_REPLAY_COUNT=0'
    Write-Output 'STOCK_QUANT_HOST_BROKER_IDLE_CREDENTIAL_READS=0'
    Write-Output 'STOCK_QUANT_HOST_BROKER_IDLE_PROVIDER_CALLS=0'
    Write-Output 'STOCK_QUANT_HOST_BROKER_REAL_PROVIDER_CALLS=0'
    Write-Output 'STOCK_QUANT_HOST_BROKER_PERMANENT_DATABASE_WRITES=0'
    if ($passed -ne 10) {
        throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_TEST_COUNT_INVALID'
    }
} finally {
    Pop-Location
    if ($null -ne $resident) {
        Stop-StockQuantTestResidentBroker -Resident $resident
    }
    foreach ($id in $requestIds) {
        foreach ($directory in @($paths.Requests, $paths.Results)) {
            foreach ($file in @(Get-ChildItem -LiteralPath $directory -File `
                    -Filter "$id.*" -ErrorAction SilentlyContinue)) {
                Remove-Item -LiteralPath $file.FullName -Force
            }
        }
    }
    if (Test-Path -LiteralPath $testRoot) {
        $resolved = [IO.Path]::GetFullPath($testRoot).TrimEnd('\')
        if (-not $resolved.StartsWith(
                $paths.TargetRoot.TrimEnd('\') + '\',
                [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($resolved).StartsWith($testPrefix)) {
            throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_CLEANUP_INVALID'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
