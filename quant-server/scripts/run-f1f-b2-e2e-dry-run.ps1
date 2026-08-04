param(
    [Parameter(Mandatory = $true)] [string] $ExpectedCommit
)

$ErrorActionPreference = 'Stop'
$pgBin = 'C:\Program Files\PostgreSQL\16\bin'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$prefix = 'stock-quant-f1f-b2-e2e-'
$tempRoot = Join-Path $tempBase ($prefix + [Guid]::NewGuid().ToString('N'))
$dataDir = Join-Path $tempRoot 'data'
$serverLog = Join-Path $tempRoot 'postgres.log'
$authorizationFile = Join-Path $tempRoot 'e2e-authorization.properties'
$artifact = Join-Path $repoRoot `
    'quant-server\target\quant-server-1.3.1-f1f-b2-runner.jar'
$proof = "$artifact.f1f-b2-proof.properties"
$started = $false
$port = 0

function Invoke-PsqlScalar([string] $Database, [string] $User, [string] $Sql) {
    $value = & "$pgBin\psql.exe" -X -q -A -t `
        -h 127.0.0.1 -p $port -U $User -d $Database `
        -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) {
        throw 'F1F_B2_E2E_POSTGRES_QUERY_FAILED'
    }
    return ($value | Select-Object -Last 1).Trim()
}

function Assert-Exact([string] $Actual, [string] $Expected, [string] $Code) {
    if ($Actual -ne $Expected) { throw $Code }
}

function Remove-ExactTempRoot([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $resolved = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($resolved).TrimEnd('\', '/') -ne $tempBase -or
        -not [IO.Path]::GetFileName($resolved).StartsWith($prefix)) {
        throw 'F1F_B2_E2E_TEMP_PATH_INVALID'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

Push-Location $repoRoot
try {
    if ($ExpectedCommit -notmatch '^[0-9a-f]{40}$' -or
        (git rev-parse HEAD).Trim() -ne $ExpectedCommit) {
        throw 'F1F_B2_E2E_BASELINE_INVALID'
    }
    $branch = (git branch --show-current).Trim()
    if ($branch -ne 'feature/1.4.0-agent-team' -and
        -not $branch.StartsWith('codex/')) {
        throw 'F1F_B2_E2E_BRANCH_INVALID'
    }
    $unexpected = @(git status --porcelain=v1 --untracked-files=normal |
        Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' })
    if ($unexpected.Count -ne 0 -or
        @(git diff --cached --name-only).Count -ne 0) {
        throw 'F1F_B2_E2E_WORKSPACE_NOT_CLEAN'
    }

    & "$PSScriptRoot\prepare-f1f-b2-build-proof.ps1" `
        -ExpectedCommit $ExpectedCommit -Mode E2E_DRY_RUN
    if ($LASTEXITCODE -ne 0 -or
        -not (Test-Path -LiteralPath $artifact) -or
        -not (Test-Path -LiteralPath $proof)) {
        throw 'F1F_B2_E2E_BUILD_FAILED'
    }
    $artifactHash = (Get-FileHash -LiteralPath $artifact `
        -Algorithm SHA256).Hash.ToLowerInvariant()

    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    & "$pgBin\initdb.exe" -D $dataDir -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'F1F_B2_E2E_INITDB_FAILED' }
    & "$pgBin\pg_ctl.exe" -D $dataDir -l $serverLog `
        -o "-h 127.0.0.1 -p $port" -w start
    if ($LASTEXITCODE -ne 0) { throw 'F1F_B2_E2E_POSTGRES_START_FAILED' }
    $started = $true
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port -U postgres `
        -d postgres -v ON_ERROR_STOP=1 -c `
        'CREATE ROLE stock_quant_research LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'F1F_B2_E2E_ROLE_CREATE_FAILED' }
    & "$pgBin\createdb.exe" -h 127.0.0.1 -p $port -U postgres `
        -O stock_quant_research stock_quant_research
    if ($LASTEXITCODE -ne 0) { throw 'F1F_B2_E2E_DATABASE_CREATE_FAILED' }
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port `
        -U stock_quant_research -d stock_quant_research `
        -v ON_ERROR_STOP=1 -c `
        'CREATE SCHEMA tushare_research AUTHORIZATION stock_quant_research' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'F1F_B2_E2E_SCHEMA_CREATE_FAILED' }
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port -U postgres `
        -d stock_quant_research -v ON_ERROR_STOP=1 -c `
        'REVOKE CREATE ON SCHEMA public FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM stock_quant_research; ALTER ROLE stock_quant_research IN DATABASE stock_quant_research SET search_path TO tushare_research' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'F1F_B2_E2E_DATABASE_HARDEN_FAILED' }

    $acceptanceId = 'F1FB2_E2E_' +
        [DateTimeOffset]::UtcNow.ToString('yyyyMMddHHmmss') + '_' +
        [Guid]::NewGuid().ToString('N').Substring(0, 10).ToUpperInvariant()
    $issuedAt = [DateTimeOffset]::UtcNow.AddSeconds(-2)
    $expiresAt = $issuedAt.AddMinutes(30)
    $authorization = @(
        'authorization.status=E2E_DRY_RUN'
        'authorization.version=F1F_B2_AUTHORIZATION_V1'
        "acceptance.id=$acceptanceId"
        "git.commit=$ExpectedCommit"
        "artifact.sha256=$artifactHash"
        "build.proof.path=$proof"
        'provider.code=TUSHARE_PRO'
        'security.symbol=600000'
        'security.exchange=SSE'
        'trade.date=2025-01-03'
        'endpoints=daily,adj_factor,trade_cal'
        'maximum.provider.requests=3'
        'retry.budget=0'
        'database.host=127.0.0.1'
        'database.name=stock_quant_research'
        'database.user=stock_quant_research'
        "database.port=$port"
        'database.ssl.mode=DISABLE_LOCAL_ONLY'
        'schema.name=tushare_research'
        'base.schema.version=13'
        'governance.schema.version=14'
        "issued.at=$($issuedAt.ToString('o'))"
        "expires.at=$($expiresAt.ToString('o'))"
        'purpose=F1F_B2_E2E_DRY_RUN'
        'execution.source=TEST'
        'user.approval.reference=NOT_APPLICABLE_E2E_DRY_RUN'
    ) -join "`n"
    [IO.File]::WriteAllText($authorizationFile, "$authorization`n",
        [Text.UTF8Encoding]::new($false))

    & "$PSScriptRoot\run-f1f-b2-controlled-acceptance.ps1" `
        -AuthorizationFile $authorizationFile
    $runnerExit = $LASTEXITCODE
    if ($runnerExit -ne 0) {
        $executionTable = Invoke-PsqlScalar stock_quant_research `
            stock_quant_research `
            "SELECT to_regclass('tushare_research.tushare_controlled_acceptance_execution') IS NOT NULL"
        Write-Output "F1F_B2_E2E_FAILURE_EXECUTION_TABLE=$executionTable"
        if ($executionTable -eq 't') {
            $failure = Invoke-PsqlScalar stock_quant_research `
                stock_quant_research @"
SELECT status || '|' || COALESCE(failure_stage, '') || '|' ||
       COALESCE(safe_failure_reason, '') || '|' || provider_call_count || '|' ||
       retry_count || '|' || (finalized_at IS NOT NULL)
  FROM tushare_research.tushare_controlled_acceptance_execution
 WHERE acceptance_id='$acceptanceId'
"@
            Write-Output "F1F_B2_E2E_FAILURE_RECORD=$failure"
        } else {
            $baseState = Invoke-PsqlScalar stock_quant_research `
                stock_quant_research @"
SELECT current_database() || '|' || current_user || '|' ||
       COALESCE(current_schema(), '') || '|' || current_setting('search_path') || '|' ||
       (SELECT string_agg(version, ',' ORDER BY installed_rank)
          FROM tushare_research.flyway_schema_history WHERE success) || '|' ||
       (SELECT count(*) FROM tushare_research.flyway_schema_history
         WHERE NOT success) || '|' ||
       pg_get_userbyid((SELECT datdba FROM pg_database
                         WHERE datname=current_database())) || '|' ||
       pg_get_userbyid((SELECT nspowner FROM pg_namespace
                         WHERE nspname='tushare_research'))
"@
            Write-Output "F1F_B2_E2E_FAILURE_BASE_STATE=$baseState"
        }
        throw 'F1F_B2_E2E_RUNNER_FAILED'
    }

    $execution = Invoke-PsqlScalar stock_quant_research stock_quant_research @"
SELECT status || '|' || execution_source || '|' || provider_call_count || '|' ||
       retry_count || '|' || (capture_batch_id IS NOT NULL) || '|' ||
       (evidence_summary_json IS NOT NULL) || '|' ||
       (evidence_digest IS NOT NULL) || '|' || (finalized_at IS NOT NULL)
  FROM tushare_research.tushare_controlled_acceptance_execution
 WHERE acceptance_id='$acceptanceId'
"@
    Assert-Exact $execution `
        'SUCCEEDED_CANDIDATE|TEST|3|0|true|true|true|true' `
        'F1F_B2_E2E_EXECUTION_INVALID'
    $history = Invoke-PsqlScalar stock_quant_research stock_quant_research @"
SELECT string_agg(to_status, '>' ORDER BY transition_id)
  FROM tushare_research.tushare_controlled_acceptance_transition
 WHERE acceptance_id='$acceptanceId'
"@
    Assert-Exact $history 'AUTHORIZED>RESERVED>RUNNING>SUCCEEDED_CANDIDATE' `
        'F1F_B2_E2E_STATE_CHAIN_INVALID'
    $facts = Invoke-PsqlScalar stock_quant_research stock_quant_research @"
WITH target AS (
  SELECT capture_batch_id AS batch_id
    FROM tushare_research.tushare_controlled_acceptance_execution
   WHERE acceptance_id='$acceptanceId'
), observations AS (
  SELECT o.id FROM tushare_research.pit_market_fact_observations o, target t
   WHERE o.batch_id=t.batch_id
)
SELECT (SELECT count(*) FROM observations) || '|' ||
       (SELECT count(*) FROM tushare_research.raw_daily_bar_facts_v2 f
         WHERE f.observation_id IN (SELECT id FROM observations)) || '|' ||
       (SELECT count(*) FROM tushare_research.adjustment_factor_facts_v1 f
         WHERE f.observation_id IN (SELECT id FROM observations)) || '|' ||
       (SELECT count(*) FROM tushare_research.trading_calendar_facts_v1 f
         WHERE f.observation_id IN (SELECT id FROM observations))
"@
    Assert-Exact $facts '3|1|1|1' 'F1F_B2_E2E_FACT_READBACK_INVALID'
    $evidence = Invoke-PsqlScalar stock_quant_research stock_quant_research @"
SELECT (evidence_summary_json->'outputAudit'->>'captureComplete') || '|' ||
       (evidence_summary_json->'outputAudit'->>'clean') || '|' ||
       (evidence_summary_json->>'formulaOnlyQfqValid') || '|' ||
       (evidence_summary_json->'endpointCallCounts'->>'DAILY') || '|' ||
       (evidence_summary_json->'endpointCallCounts'->>'ADJ_FACTOR') || '|' ||
       (evidence_summary_json->'endpointCallCounts'->>'TRADE_CAL') || '|' ||
       (evidence_summary_json->'databaseReadback'->>'committedReadbackVerified')
  FROM tushare_research.tushare_controlled_acceptance_execution
 WHERE acceptance_id='$acceptanceId'
"@
    Assert-Exact $evidence 'true|true|true|1|1|1|true' `
        'F1F_B2_E2E_EVIDENCE_INVALID'
    $residualRunning = Invoke-PsqlScalar stock_quant_research stock_quant_research `
        "SELECT count(*) FROM tushare_research.tushare_controlled_acceptance_execution WHERE status='RUNNING' OR finalized_at IS NULL"
    Assert-Exact $residualRunning '0' 'F1F_B2_E2E_RUNNING_RESIDUAL'
    $passed = Invoke-PsqlScalar stock_quant_research stock_quant_research `
        "SELECT count(*) FROM tushare_research.tushare_controlled_acceptance_execution WHERE status='PASSED'"
    Assert-Exact $passed '0' 'F1F_B2_E2E_REAL_PASS_FORBIDDEN'

    Write-Output 'F1F_B2_E2E_DRY_RUN=PASS'
    Write-Output "F1F_B2_E2E_ACCEPTANCE_ID=$acceptanceId"
    Write-Output 'F1F_B2_E2E_STATE_CHAIN=AUTHORIZED>RESERVED>RUNNING>SUCCEEDED_CANDIDATE'
    Write-Output 'F1F_B2_E2E_FAKE_PROVIDER_CALLS=3'
    Write-Output 'F1F_B2_E2E_TYPED_FACTS=1/1/1'
    Write-Output 'F1F_B2_E2E_OUTPUT_AUDIT=PASS'
    Write-Output "F1F_B2_E2E_PORT=$port"
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
        throw 'F1F_B2_E2E_TEMP_PORT_REMAINS'
    }
    if (Test-Path -LiteralPath $tempRoot) {
        throw 'F1F_B2_E2E_TEMP_DIRECTORY_REMAINS'
    }
    Write-Output 'F1F_B2_E2E_TEMP_RESIDUALS=0'
}
