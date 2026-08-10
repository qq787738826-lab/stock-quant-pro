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
$testPrefix = 'stock-quant-host-broker-fake-e2e-'
$testRoot = Join-Path $paths.TargetRoot `
    ($testPrefix + [Guid]::NewGuid().ToString('N'))
$artifact = Join-Path $paths.TargetRoot `
    'quant-server-1.3.1-reduced-research-day001-runner.jar'
$proof = "$artifact.f1f-b2-proof.properties"
$authorization = Join-Path $testRoot 'broker-fake-e2e-authorization.properties'
$requestId = $null
$resident = $null

function Write-E2eAuthorization {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ArtifactHash,
        [Parameter(Mandatory = $true)]
        [int] $DatabasePort
    )
    $issued = [DateTimeOffset]::UtcNow.AddSeconds(-2)
    $expires = $issued.AddMinutes(20)
    $content = @(
        'authorization.status=E2E_DRY_RUN'
        'authorization.version=REDUCED_RESEARCH_DAY001_AUTHORIZATION_V1'
        'run.id=RRDAY001_BROKER_FAKE_E2E_0001'
        "git.commit=$ExpectedCommit"
        "artifact.sha256=$ArtifactHash"
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
        "database.port=$DatabasePort"
        'database.name=stock_quant_research'
        'database.user=stock_quant_research'
        'database.ssl.mode=DISABLE_LOCAL_ONLY'
        'schema.name=tushare_research'
        "issued.at=$($issued.ToString('o'))"
        "expires.at=$($expires.ToString('o'))"
        'purpose=3A_R3B_RR_DAY001'
        'execution.source=REDUCED_RESEARCH_MANUAL_DAY001'
        'user.approval.reference=NOT_APPLICABLE_E2E_DRY_RUN'
    ) -join "`n"
    [IO.File]::WriteAllText($authorization, $content + "`n",
        [Text.UTF8Encoding]::new($false))
}

Push-Location $paths.RepositoryRoot
try {
    if ((git rev-parse HEAD).Trim() -ne $ExpectedCommit -or
        @(git status --porcelain=v1 --untracked-files=normal |
            Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' }).Count `
            -ne 0 -or @(git diff --cached --name-only).Count -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_FAKE_E2E_GIT_INVALID'
    }
    if (@(Get-ChildItem -LiteralPath $paths.Requests -File `
            -Filter '*.request.properties').Count -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_FAKE_E2E_QUEUE_NOT_EMPTY'
    }
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    & (Join-Path $paths.RepositoryRoot `
        'quant-server\scripts\prepare-reduced-research-day001-build-proof.ps1') `
        -ExpectedCommit $ExpectedCommit -Mode E2E_DRY_RUN
    if ($LASTEXITCODE -ne 0 -or
        -not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath $proof -PathType Leaf)) {
        throw 'STOCK_QUANT_HOST_BROKER_FAKE_E2E_BUILD_FAILED'
    }
    $artifactHash = ((Get-FileHash -LiteralPath $artifact `
        -Algorithm SHA256).Hash).ToLowerInvariant()
    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $fakePort = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    if ($fakePort -eq 38432) {
        throw 'STOCK_QUANT_HOST_BROKER_FAKE_E2E_PORT_INVALID'
    }
    Write-E2eAuthorization -ArtifactHash $artifactHash `
        -DatabasePort $fakePort

    $resident = Start-StockQuantTestResidentBroker `
        -ExpectedCommit $ExpectedCommit -LogDirectory $testRoot
    $requestId = New-StockQuantHostBrokerRequestId
    $created = [DateTimeOffset]::UtcNow
    $values = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = $requestId
        'operation' = 'RUN_FAKE_E2E'
        'git.commit' = $ExpectedCommit
        'jar.path' = $artifact
        'jar.sha256' = $artifactHash
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
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'REDUCED_RESEARCH_MANUAL_DAY001'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    Write-StockQuantHostBrokerRequest -Values $values | Out-Null
    $resultPath = Join-Path $paths.Results "$requestId.result.json"
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(30)
    $busyHeartbeats = [Collections.Generic.HashSet[string]]::new()
    while (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
        if ([DateTimeOffset]::UtcNow -ge $deadline) {
            throw 'STOCK_QUANT_HOST_BROKER_FAKE_E2E_RESULT_TIMEOUT'
        }
        if ($resident.Process.HasExited) {
            throw 'STOCK_QUANT_HOST_BROKER_FAKE_E2E_RESIDENT_EXITED'
        }
        try {
            $heartbeat = Read-StockQuantHostBrokerHeartbeat `
                -ExpectedGitCommit $ExpectedCommit
            if ([string]$heartbeat.state -eq 'BUSY') {
                [void]$busyHeartbeats.Add([string]$heartbeat.lastHeartbeat)
            }
        } catch {
            if ($_.Exception.Message -cne 'HOST_BROKER_NOT_RUNNING') {
                throw
            }
        }
        Start-Sleep -Milliseconds 250
    }
    if ($busyHeartbeats.Count -lt 2) {
        throw 'STOCK_QUANT_HOST_BROKER_BUSY_HEARTBEAT_NOT_REFRESHED'
    }
    $result = Get-Content -LiteralPath $resultPath -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($result.status -ne 'SUCCEEDED' -or
        $result.providerCallCount -ne 0 -or $result.retryCount -ne 0 -or
        $result.summary.fakeProviderCallCount -ne 9 -or
        $result.summary.temporaryPostgres -ne 'PASSED' -or
        $result.summary.outputAudit -ne 'PASSED' -or
        $result.summary.residualCount -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_FAKE_E2E_RESULT_INVALID'
    }
    Write-Output 'STOCK_QUANT_HOST_BROKER_PACKAGED_FAKE_E2E=PASS'
    Write-Output 'STOCK_QUANT_HOST_BROKER_FAKE_PROVIDER_CALLS=9'
    Write-Output 'STOCK_QUANT_HOST_BROKER_REAL_PROVIDER_CALLS=0'
    Write-Output 'STOCK_QUANT_HOST_BROKER_FAKE_E2E_RESIDUALS=0'
} finally {
    Pop-Location
    if ($null -ne $resident) {
        Stop-StockQuantTestResidentBroker -Resident $resident
        $resident = $null
    }
    if ($null -ne $requestId) {
        foreach ($directory in @($paths.Requests, $paths.Results)) {
            foreach ($generated in @(Get-ChildItem -LiteralPath $directory `
                    -File -Filter "$requestId.*" -ErrorAction SilentlyContinue)) {
                $full = [IO.Path]::GetFullPath($generated.FullName)
                $prefix = $directory.TrimEnd('\') + '\'
                if (-not $full.StartsWith(
                        $prefix, [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'STOCK_QUANT_HOST_BROKER_FAKE_E2E_CLEANUP_INVALID'
                }
                Remove-Item -LiteralPath $full -Force
            }
        }
    }
    if (Test-Path -LiteralPath $testRoot) {
        $resolved = [IO.Path]::GetFullPath($testRoot).TrimEnd('\')
        $prefix = $paths.TargetRoot.TrimEnd('\') + '\'
        if (-not $resolved.StartsWith(
                $prefix, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.Path]::GetFileName($resolved).StartsWith($testPrefix)) {
            throw 'STOCK_QUANT_HOST_BROKER_FAKE_E2E_CLEANUP_INVALID'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    foreach ($generated in @($artifact, $proof, "$artifact.original")) {
        if (Test-Path -LiteralPath $generated) {
            $full = [IO.Path]::GetFullPath($generated)
            $prefix = $paths.TargetRoot.TrimEnd('\') + '\'
            if (-not $full.StartsWith(
                    $prefix, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'STOCK_QUANT_HOST_BROKER_FAKE_E2E_CLEANUP_INVALID'
            }
            Remove-Item -LiteralPath $full -Force
        }
    }
}
