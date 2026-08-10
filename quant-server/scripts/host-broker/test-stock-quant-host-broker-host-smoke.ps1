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

$paths = Initialize-StockQuantHostBrokerDirectories
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$credentialStatusScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\set-stock-quant-secrets.ps1'
$testPrefix = 'stock-quant-host-broker-host-smoke-'
$testRoot = Join-Path $paths.TargetRoot `
    ($testPrefix + [Guid]::NewGuid().ToString('N'))
$jar = Join-Path $testRoot 'host-smoke.jar'
$proof = "$jar.f1f-b2-proof.properties"
$authorization = Join-Path $testRoot 'host-smoke-authorization.properties'
$requestId = $null

if ($identity -match '(?i)CodexSandbox') {
    throw 'STOCK_QUANT_HOST_BROKER_REAL_USER_REQUIRED'
}

Push-Location $paths.RepositoryRoot
try {
    if ((git rev-parse HEAD).Trim() -ne $ExpectedCommit -or
        @(git status --porcelain=v1 --untracked-files=normal |
            Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' }).Count `
            -ne 0 -or @(git diff --cached --name-only).Count -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_HOST_SMOKE_GIT_INVALID'
    }
    if (@(Get-ChildItem -LiteralPath $paths.Requests -File `
            -Filter '*.request.properties').Count -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_HOST_SMOKE_QUEUE_NOT_EMPTY'
    }
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    [IO.File]::WriteAllBytes($jar,
        [Text.Encoding]::UTF8.GetBytes('HOST_SMOKE_NON_EXECUTABLE_JAR'))
    [IO.File]::WriteAllText($proof,
        "proof.mode=HOST_SMOKE`n", [Text.UTF8Encoding]::new($false))
    $artifactHash = ((Get-FileHash -LiteralPath $jar `
        -Algorithm SHA256).Hash).ToLowerInvariant()
    $issued = [DateTimeOffset]::UtcNow.AddSeconds(-2)
    $authorizationContent = @(
        'authorization.status=E2E_DRY_RUN'
        'authorization.version=REDUCED_RESEARCH_DAY001_AUTHORIZATION_V1'
        'run.id=RRDAY001_BROKER_HOST_SMOKE_0001'
        "git.commit=$ExpectedCommit"
        "artifact.sha256=$artifactHash"
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
    ) -join "`n"
    [IO.File]::WriteAllText($authorization,
        $authorizationContent + "`n", [Text.UTF8Encoding]::new($false))

    $credentialStatus = @(& $credentialStatusScript -Status 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0 -or $credentialStatus.Count -ne 3 -or
        $credentialStatus -notcontains `
            'StockQuant/ResearchDbPassword=PRESENT' -or
        $credentialStatus -notcontains 'StockQuant/TushareToken=PRESENT' -or
        $credentialStatus -notcontains 'STOCK_QUANT_CREDENTIALS_READY=True') {
        throw 'STOCK_QUANT_HOST_BROKER_HOST_CREDENTIAL_STATUS_INVALID'
    }

    $requestId = New-StockQuantHostBrokerRequestId
    $created = [DateTimeOffset]::UtcNow
    $values = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = $requestId
        'operation' = 'CHECK_CREDENTIAL_STATUS'
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
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'REDUCED_RESEARCH_MANUAL_DAY001'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    Write-StockQuantHostBrokerRequest -Values $values | Out-Null
    & $paths.BrokerScript | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_HOST_SMOKE_EXECUTION_FAILED'
    }
    $resultPath = Join-Path $paths.Results "$requestId.result.json"
    $result = Get-Content -LiteralPath $resultPath -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($result.status -ne 'SUCCEEDED' -or
        -not $result.summary.credentialsReady -or
        $result.providerCallCount -ne 0 -or $result.retryCount -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_HOST_SMOKE_RESULT_INVALID'
    }
    Write-Output 'STOCK_QUANT_HOST_BROKER_HOST_CREDENTIAL_STATUS=PASS'
    Write-Output "STOCK_QUANT_HOST_BROKER_HOST_ACCOUNT=$identity"
    Write-Output 'STOCK_QUANT_HOST_BROKER_CODEX_CLI_REQUIRED=false'
    Write-Output 'STOCK_QUANT_HOST_BROKER_HOST_SMOKE_PROVIDER_CALLS=0'
    Write-Output 'STOCK_QUANT_HOST_BROKER_HOST_SMOKE_PERMANENT_DATABASE_WRITES=0'
} finally {
    Pop-Location
    if ($null -ne $requestId) {
        foreach ($directory in @($paths.Requests, $paths.Results)) {
            foreach ($generated in @(Get-ChildItem -LiteralPath $directory `
                    -File -Filter "$requestId.*" -ErrorAction SilentlyContinue)) {
                $full = [IO.Path]::GetFullPath($generated.FullName)
                $prefix = $directory.TrimEnd('\') + '\'
                if (-not $full.StartsWith(
                        $prefix, [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'STOCK_QUANT_HOST_BROKER_HOST_SMOKE_CLEANUP_INVALID'
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
            throw 'STOCK_QUANT_HOST_BROKER_HOST_SMOKE_CLEANUP_INVALID'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
