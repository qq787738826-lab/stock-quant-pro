[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ExpectedCommit
)

$ErrorActionPreference = 'Stop'
$pgBin = 'C:\Program Files\PostgreSQL\16\bin'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$prefix = 'stock-quant-rr-day001-e2e-'
$tempRoot = Join-Path $tempBase ($prefix + [Guid]::NewGuid().ToString('N'))
$dataDir = Join-Path $tempRoot 'data'
$serverLog = Join-Path $tempRoot 'postgres.log'
$artifact = Join-Path $repoRoot `
    'quant-server\target\quant-server-1.3.1-f1f-b2-runner.jar'
$proof = "$artifact.f1f-b2-proof.properties"
$runnerClass = 'com.stockquant.server.agent.marketfacts.' +
    'TushareReducedResearchManualRunner'
$started = $false
$port = 0

function Invoke-PsqlScalar([string] $Sql) {
    $value = & "$pgBin\psql.exe" -X -q -A -t `
        -h 127.0.0.1 -p $port -U stock_quant_research `
        -d stock_quant_research -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_POSTGRES_QUERY_FAILED'
    }
    return ($value | Select-Object -Last 1).Trim()
}

function Assert-Exact([object] $Actual, [object] $Expected, [string] $Code) {
    if ([string]$Actual -ne [string]$Expected) {
        throw $Code
    }
}

function Remove-ExactTempRoot([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $resolved = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($resolved).TrimEnd('\', '/') -ne $tempBase -or
        -not [IO.Path]::GetFileName($resolved).StartsWith($prefix)) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_TEMP_PATH_INVALID'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function Write-E2eAuthorization(
    [string] $Path,
    [string] $RunId,
    [string] $Mode,
    [string] $TradeDate,
    [DateTimeOffset] $IssuedAt,
    [DateTimeOffset] $ExpiresAt,
    [string] $ArtifactHash,
    [string] $DatabaseName = 'stock_quant_research'
) {
    $authorization = @(
        'authorization.status=E2E_DRY_RUN'
        'authorization.version=REDUCED_RESEARCH_DAY001_AUTHORIZATION_V1'
        "run.id=$RunId"
        "git.commit=$ExpectedCommit"
        "artifact.sha256=$ArtifactHash"
        "build.proof.path=$proof"
        'provider=TUSHARE'
        'security.symbol=600000'
        'security.exchange=SSE'
        "trade.date=$TradeDate"
        "day001.mode=$Mode"
        'endpoints=daily,adj_factor,trade_cal'
        'endpoint.daily.requests=1'
        'endpoint.adj_factor.requests=1'
        'endpoint.trade_cal.requests=1'
        'maximum.provider.requests=3'
        'retry.budget=0'
        'redirects=NEVER'
        'database.host=127.0.0.1'
        "database.port=$port"
        "database.name=$DatabaseName"
        'database.user=stock_quant_research'
        'database.ssl.mode=DISABLE_LOCAL_ONLY'
        'schema.name=tushare_research'
        "issued.at=$($IssuedAt.ToString('o'))"
        "expires.at=$($ExpiresAt.ToString('o'))"
        'purpose=3A_R3B_RR_DAY001'
        'execution.source=REDUCED_RESEARCH_MANUAL_DAY001'
        'user.approval.reference=NOT_APPLICABLE_E2E_DRY_RUN'
    ) -join "`n"
    [IO.File]::WriteAllText($Path, "$authorization`n",
        [Text.UTF8Encoding]::new($false))
}

function Invoke-JavaRunner(
    [string] $Authorization,
    [string] $Result,
    [int] $FailAtCall = -1
) {
    & java "-Dstockquant.reduced-research.e2e.fail-at-call=$FailAtCall" `
        "-Dloader.main=$runnerClass" -cp $artifact `
        'org.springframework.boot.loader.launch.PropertiesLauncher' `
        "--authorization-file=$Authorization" "--result-file=$Result" |
        Out-Host
    return $LASTEXITCODE
}

function Assert-SuccessResult(
    [string] $Path,
    [string] $Mode,
    [int] $Appended,
    [int] $Idempotent
) {
    $result = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 |
        ConvertFrom-Json
    Assert-Exact $result.status 'SUCCEEDED' `
        'TUSHARE_REDUCED_RESEARCH_E2E_STATUS_INVALID'
    Assert-Exact $result.day001Mode $Mode `
        'TUSHARE_REDUCED_RESEARCH_E2E_MODE_INVALID'
    Assert-Exact $result.providerCallCount 3 `
        'TUSHARE_REDUCED_RESEARCH_E2E_PROVIDER_COUNT_INVALID'
    Assert-Exact $result.retryCount 0 `
        'TUSHARE_REDUCED_RESEARCH_E2E_RETRY_COUNT_INVALID'
    Assert-Exact $result.endpointCallCounts.daily 1 `
        'TUSHARE_REDUCED_RESEARCH_E2E_DAILY_COUNT_INVALID'
    Assert-Exact $result.endpointCallCounts.adj_factor 1 `
        'TUSHARE_REDUCED_RESEARCH_E2E_FACTOR_COUNT_INVALID'
    Assert-Exact $result.endpointCallCounts.trade_cal 1 `
        'TUSHARE_REDUCED_RESEARCH_E2E_CALENDAR_COUNT_INVALID'
    Assert-Exact $result.newObservationCount $Appended `
        'TUSHARE_REDUCED_RESEARCH_E2E_APPEND_COUNT_INVALID'
    Assert-Exact $result.existingChainTailCount $Idempotent `
        'TUSHARE_REDUCED_RESEARCH_E2E_IDEMPOTENT_COUNT_INVALID'
    Assert-Exact $result.typedFactReadback 'PASSED' `
        'TUSHARE_REDUCED_RESEARCH_E2E_TYPED_READBACK_INVALID'
    Assert-Exact $result.systemKnowledgeReadback 'PASSED' `
        'TUSHARE_REDUCED_RESEARCH_E2E_SYSTEM_KNOWLEDGE_INVALID'
    Assert-Exact $result.formulaOnlyQfq.result 'PASSED' `
        'TUSHARE_REDUCED_RESEARCH_E2E_QFQ_INVALID'
    Assert-Exact $result.formulaOnlyQfq.formulaOnly 'True' `
        'TUSHARE_REDUCED_RESEARCH_E2E_QFQ_SCOPE_INVALID'
    Assert-Exact $result.formulaOnlyQfq.persisted 'False' `
        'TUSHARE_REDUCED_RESEARCH_E2E_QFQ_PERSISTENCE_INVALID'
    Assert-Exact $result.outputAudit.clean 'True' `
        'TUSHARE_REDUCED_RESEARCH_E2E_OUTPUT_AUDIT_INVALID'
    Assert-Exact $result.passedAcceptanceStatusProduced 'False' `
        'TUSHARE_REDUCED_RESEARCH_E2E_ACCEPTANCE_STATUS_FORBIDDEN'
    Assert-Exact $result.operationalReadinessModified 'False' `
        'TUSHARE_REDUCED_RESEARCH_E2E_OPERATIONAL_MUTATION_FORBIDDEN'
}

Push-Location $repoRoot
try {
    if ($ExpectedCommit -notmatch '^[0-9a-f]{40}$' -or
        (git rev-parse HEAD).Trim() -ne $ExpectedCommit) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_BASELINE_INVALID'
    }
    $branch = (git branch --show-current).Trim()
    if ($branch -ne 'feature/1.4.0-agent-team' -and
        -not $branch.StartsWith('codex/')) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_BRANCH_INVALID'
    }
    $unexpected = @(git status --porcelain=v1 --untracked-files=normal |
        Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' })
    if ($unexpected.Count -ne 0 -or
        @(git diff --cached --name-only).Count -ne 0) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_WORKSPACE_NOT_CLEAN'
    }

    & "$PSScriptRoot\prepare-f1f-b2-build-proof.ps1" `
        -ExpectedCommit $ExpectedCommit -Mode E2E_DRY_RUN
    if ($LASTEXITCODE -ne 0 -or
        -not (Test-Path -LiteralPath $artifact) -or
        -not (Test-Path -LiteralPath $proof)) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_BUILD_FAILED'
    }
    $artifactHash = (Get-FileHash -LiteralPath $artifact `
        -Algorithm SHA256).Hash.ToLowerInvariant()

    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    if ($port -eq 38432) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_PERMANENT_PORT_FORBIDDEN'
    }
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    & "$pgBin\initdb.exe" -D $dataDir -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_INITDB_FAILED'
    }
    & "$pgBin\pg_ctl.exe" -D $dataDir -l $serverLog `
        -o "-h 127.0.0.1 -p $port" -w start
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_POSTGRES_START_FAILED'
    }
    $started = $true
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port -U postgres `
        -d postgres -v ON_ERROR_STOP=1 -c `
        'CREATE ROLE stock_quant_research LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_ROLE_CREATE_FAILED'
    }
    & "$pgBin\createdb.exe" -h 127.0.0.1 -p $port -U postgres `
        -O stock_quant_research stock_quant_research
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_DATABASE_CREATE_FAILED'
    }
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port `
        -U stock_quant_research -d stock_quant_research `
        -v ON_ERROR_STOP=1 -c `
        'CREATE SCHEMA tushare_research AUTHORIZATION stock_quant_research' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_SCHEMA_CREATE_FAILED'
    }
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port -U postgres `
        -d stock_quant_research -v ON_ERROR_STOP=1 -c `
        'REVOKE CREATE ON SCHEMA public FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM stock_quant_research; ALTER ROLE stock_quant_research IN DATABASE stock_quant_research SET search_path TO tushare_research' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_DATABASE_HARDEN_FAILED'
    }

    $issuedAt = [DateTimeOffset]::UtcNow.AddSeconds(-2)
    $expiresAt = $issuedAt.AddMinutes(20)
    $newAuthorization = Join-Path $tempRoot 'new-capture.properties'
    $newResult = Join-Path $tempRoot 'new-capture-result.json'
    Write-E2eAuthorization $newAuthorization 'RRDAY001_E2E_NEW_0001' `
        'NEW_CAPTURE' '2025-01-03' $issuedAt $expiresAt $artifactHash
    & "$PSScriptRoot\run-reduced-research-day001.ps1" `
        -AuthorizationFile $newAuthorization -ResultFile $newResult `
        -ArtifactPath $artifact
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_NEW_CAPTURE_FAILED'
    }
    Assert-SuccessResult $newResult 'NEW_CAPTURE' 3 0

    $idempotentAuthorization = Join-Path $tempRoot 'idempotent.properties'
    $idempotentResult = Join-Path $tempRoot 'idempotent-result.json'
    Write-E2eAuthorization $idempotentAuthorization `
        'RRDAY001_E2E_IDEMPOTENT_0002' 'IDEMPOTENCY_VERIFICATION' `
        '2025-01-03' $issuedAt $expiresAt $artifactHash
    & "$PSScriptRoot\run-reduced-research-day001.ps1" `
        -AuthorizationFile $idempotentAuthorization `
        -ResultFile $idempotentResult -ArtifactPath $artifact
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_IDEMPOTENT_FAILED'
    }
    Assert-SuccessResult $idempotentResult 'IDEMPOTENCY_VERIFICATION' 0 3

    $databaseFacts = Invoke-PsqlScalar @"
SELECT (SELECT count(*) FROM tushare_research.pit_market_fact_batches) || '|' ||
       (SELECT count(*) FROM tushare_research.pit_market_fact_observations) || '|' ||
       (SELECT count(*) FROM tushare_research.raw_daily_bar_facts_v2) || '|' ||
       (SELECT count(*) FROM tushare_research.adjustment_factor_facts_v1) || '|' ||
       (SELECT count(*) FROM tushare_research.trading_calendar_facts_v1)
"@
    Assert-Exact $databaseFacts '2|3|1|1|1' `
        'TUSHARE_REDUCED_RESEARCH_E2E_DATABASE_FACTS_INVALID'
    $governanceAbsent = Invoke-PsqlScalar @"
SELECT (to_regclass('tushare_research.tushare_controlled_acceptance_execution') IS NULL)
   AND (to_regclass('tushare_research.flyway_controlled_acceptance_history') IS NULL)
"@
    Assert-Exact $governanceAbsent 't' `
        'TUSHARE_REDUCED_RESEARCH_E2E_GOVERNANCE_TABLE_CREATED'

    $beforeFailedBatch = Invoke-PsqlScalar `
        'SELECT count(*) FROM tushare_research.pit_market_fact_batches'
    $providerFailureAuthorization = Join-Path $tempRoot 'provider-failure.properties'
    $providerFailureResult = Join-Path $tempRoot 'provider-failure-result.json'
    Write-E2eAuthorization $providerFailureAuthorization `
        'RRDAY001_E2E_PROVIDER_FAIL_0003' 'NEW_CAPTURE' '2025-01-06' `
        $issuedAt $expiresAt $artifactHash
    $providerFailureExit = Invoke-JavaRunner $providerFailureAuthorization `
        $providerFailureResult 3
    Assert-Exact $providerFailureExit 20 `
        'TUSHARE_REDUCED_RESEARCH_E2E_PROVIDER_FAILURE_EXIT_INVALID'
    $providerFailure = Get-Content -LiteralPath $providerFailureResult `
        -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Exact $providerFailure.status 'FAILED_VALIDATION' `
        'TUSHARE_REDUCED_RESEARCH_E2E_PROVIDER_FAILURE_STATUS_INVALID'
    Assert-Exact $providerFailure.providerCallCount 3 `
        'TUSHARE_REDUCED_RESEARCH_E2E_PROVIDER_FAILURE_CALLS_INVALID'
    Assert-Exact $providerFailure.retryCount 0 `
        'TUSHARE_REDUCED_RESEARCH_E2E_PROVIDER_FAILURE_RETRIES_INVALID'
    Assert-Exact (Invoke-PsqlScalar `
        'SELECT count(*) FROM tushare_research.pit_market_fact_batches') `
        $beforeFailedBatch `
        'TUSHARE_REDUCED_RESEARCH_E2E_PROVIDER_FAILURE_WROTE_BATCH'

    $badBuildAuthorization = Join-Path $tempRoot 'bad-build.properties'
    Write-E2eAuthorization $badBuildAuthorization 'RRDAY001_E2E_BAD_BUILD_0004' `
        'NEW_CAPTURE' '2025-01-07' $issuedAt $expiresAt `
        (('b' * 64) -join '')
    Assert-Exact (Invoke-JavaRunner $badBuildAuthorization `
        (Join-Path $tempRoot 'bad-build-result.json')) 20 `
        'TUSHARE_REDUCED_RESEARCH_E2E_BAD_BUILD_NOT_REJECTED'

    $expiredAuthorization = Join-Path $tempRoot 'expired.properties'
    Write-E2eAuthorization $expiredAuthorization 'RRDAY001_E2E_EXPIRED_0005' `
        'NEW_CAPTURE' '2025-01-07' ($issuedAt.AddMinutes(-30)) `
        ($issuedAt.AddSeconds(-1)) $artifactHash
    Assert-Exact (Invoke-JavaRunner $expiredAuthorization `
        (Join-Path $tempRoot 'expired-result.json')) 20 `
        'TUSHARE_REDUCED_RESEARCH_E2E_EXPIRED_NOT_REJECTED'

    $wrongDatabaseAuthorization = Join-Path $tempRoot 'wrong-database.properties'
    Write-E2eAuthorization $wrongDatabaseAuthorization `
        'RRDAY001_E2E_WRONG_DB_0006' 'NEW_CAPTURE' '2025-01-07' `
        $issuedAt $expiresAt $artifactHash 'stock_quant'
    Assert-Exact (Invoke-JavaRunner $wrongDatabaseAuthorization `
        (Join-Path $tempRoot 'wrong-database-result.json')) 20 `
        'TUSHARE_REDUCED_RESEARCH_E2E_WRONG_DATABASE_NOT_REJECTED'

    Assert-Exact (Invoke-JavaRunner (Join-Path $tempRoot 'missing.properties') `
        (Join-Path $tempRoot 'missing-result.json')) 20 `
        'TUSHARE_REDUCED_RESEARCH_E2E_MISSING_AUTH_NOT_REJECTED'
    Assert-Exact (Invoke-PsqlScalar `
        'SELECT count(*) FROM tushare_research.pit_market_fact_batches') 2 `
        'TUSHARE_REDUCED_RESEARCH_E2E_PRE_PROVIDER_FAILURE_WROTE_BATCH'

    Write-Output 'TUSHARE_REDUCED_RESEARCH_DAY001_E2E=PASS'
    Write-Output 'TUSHARE_REDUCED_RESEARCH_DAY001_FAKE_PROVIDER_CALLS=3/3/3'
    Write-Output 'TUSHARE_REDUCED_RESEARCH_DAY001_RETRIES=0/0/0'
    Write-Output 'TUSHARE_REDUCED_RESEARCH_DAY001_MODES=NEW_CAPTURE/IDEMPOTENCY_VERIFICATION'
    Write-Output 'TUSHARE_REDUCED_RESEARCH_DAY001_TYPED_FACTS=1/1/1'
    Write-Output 'TUSHARE_REDUCED_RESEARCH_DAY001_OUTPUT_AUDIT=PASS'
    Write-Output 'TUSHARE_REDUCED_RESEARCH_DAY001_GOVERNANCE_MUTATIONS=0'
    Write-Output 'TUSHARE_REDUCED_RESEARCH_DAY001_REAL_PROVIDER_CALLS=0'
} finally {
    Pop-Location
    if ($started -and (Test-Path -LiteralPath $dataDir)) {
        & "$pgBin\pg_ctl.exe" -D $dataDir -m immediate -w stop `
            2>$null | Out-Null
    }
    Remove-ExactTempRoot $tempRoot
    foreach ($generated in @($artifact, $proof, "$artifact.original")) {
        if (Test-Path -LiteralPath $generated) {
            Remove-Item -LiteralPath $generated -Force
        }
    }
    if ($port -gt 0 -and (Get-NetTCPConnection -LocalPort $port `
            -State Listen -ErrorAction SilentlyContinue)) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_TEMP_PORT_REMAINS'
    }
    if (Test-Path -LiteralPath $tempRoot) {
        throw 'TUSHARE_REDUCED_RESEARCH_E2E_TEMP_DIRECTORY_REMAINS'
    }
    Write-Output 'TUSHARE_REDUCED_RESEARCH_DAY001_E2E_RESIDUALS=0'
}
