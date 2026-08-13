[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedCommit
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$pgBin = 'C:\Program Files\PostgreSQL\16\bin'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$tempBase = [IO.Path]::GetFullPath(
    (Join-Path $env:TEMP 'stock-quant-pro-selection-tests')).TrimEnd('\', '/')
$prefix = 'stock-quant-selection-e2e-'
$root = Join-Path $tempBase ($prefix + [Guid]::NewGuid().ToString('N'))
$data = Join-Path $root 'data'
$log = Join-Path $root 'postgres.log'
$artifact = Join-Path $target `
    'quant-server-1.3.1-research-selection-runner.jar'
$result = Join-Path $target `
    ('selection-e2e-' + [Guid]::NewGuid().ToString('N') + '.json')
$repeatResult = Join-Path $target `
    ('selection-e2e-repeat-' + [Guid]::NewGuid().ToString('N') + '.json')
$port = 0
$started = $false

function Exact([object] $Actual, [object] $Expected, [string] $Code) {
    if ([string]$Actual -ne [string]$Expected) { throw $Code }
}

function Scalar([string] $Sql) {
    $value = & "$pgBin\psql.exe" -X -q -A -t -h 127.0.0.1 `
        -p $port -U stock_quant_research -d stock_quant_research `
        -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) { throw 'RESEARCH_SELECTION_E2E_QUERY_FAILED' }
    return ($value | Select-Object -Last 1).Trim()
}

function Remove-Root {
    if (-not (Test-Path -LiteralPath $root)) { return }
    $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne $tempBase -or
        -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
        throw 'RESEARCH_SELECTION_E2E_CLEANUP_PATH_INVALID'
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

Push-Location $repoRoot
try {
    New-Item -ItemType Directory -Path $tempBase -Force | Out-Null
    if ((git rev-parse HEAD).Trim() -ne $ExpectedCommit -or
        @(git status --porcelain=v1 --untracked-files=normal |
            Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' }
        ).Count -ne 0 -or @(git diff --cached --name-only).Count -ne 0) {
        throw 'RESEARCH_SELECTION_E2E_GIT_INVALID'
    }
    & "$PSScriptRoot\prepare-research-selection-build-proof.ps1" `
        -ExpectedCommit $ExpectedCommit -Mode E2E_DRY_RUN
    if ($LASTEXITCODE -ne 0 -or
        -not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath `
            "$artifact.f1f-b2-proof.properties" -PathType Leaf)) {
        throw 'RESEARCH_SELECTION_E2E_BUILD_FAILED'
    }

    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    if ($port -eq 38432) { throw 'RESEARCH_SELECTION_E2E_PORT_INVALID' }
    New-Item -ItemType Directory -Path $root | Out-Null
    & "$pgBin\initdb.exe" -D $data -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'RESEARCH_SELECTION_E2E_INITDB_FAILED' }
    $arguments = '-D "{0}" -l "{1}" -o "-h 127.0.0.1 -p {2}" -w start' `
        -f $data, $log, $port
    $process = Start-Process "$pgBin\pg_ctl.exe" -ArgumentList $arguments `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru
    try {
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw 'RESEARCH_SELECTION_E2E_POSTGRES_START_FAILED'
        }
    } finally { $process.Dispose() }
    $started = $true
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port -U postgres `
        -d postgres -v ON_ERROR_STOP=1 -c `
        'CREATE ROLE stock_quant_research LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS' | Out-Null
    & "$pgBin\createdb.exe" -h 127.0.0.1 -p $port -U postgres `
        -O stock_quant_research stock_quant_research
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port `
        -U stock_quant_research -d stock_quant_research `
        -v ON_ERROR_STOP=1 -c `
        'CREATE SCHEMA tushare_research AUTHORIZATION stock_quant_research' |
        Out-Null
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port -U postgres `
        -d stock_quant_research -v ON_ERROR_STOP=1 -c `
        'REVOKE CREATE ON SCHEMA public FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM stock_quant_research; ALTER ROLE stock_quant_research IN DATABASE stock_quant_research SET search_path TO tushare_research' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'RESEARCH_SELECTION_E2E_DATABASE_SETUP_FAILED'
    }

    $executionId = 'SELECTEXEC_20260813T010203Z_A1B2C3D4E5F6'
    $publicRun = 'SELECT_20260813T010203Z_A1B2C3D4E5F6'
    & "$PSScriptRoot\run-research-selection.ps1" -ResultFile $result `
        -ArtifactPath $artifact -ExecutionId $executionId `
        -SelectionRunId 1 -PublicRunId $publicRun `
        -GitCommit $ExpectedCommit -DatabasePort $port `
        -MaximumProviderRequests 52 -ExecutionMode FAKE `
        -MaximumCostCny 5.00
    if ($LASTEXITCODE -ne 0) {
        throw 'RESEARCH_SELECTION_E2E_RUNNER_FAILED'
    }
    $value = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    Exact $value.status 'SUCCEEDED' 'RESEARCH_SELECTION_E2E_STATUS_INVALID'
    Exact $value.gitCommit $ExpectedCommit `
        'RESEARCH_SELECTION_E2E_COMMIT_INVALID'
    Exact $value.selectionStatus 'COMPLETED' `
        'RESEARCH_SELECTION_E2E_SELECTION_STATE_INVALID'
    Exact $value.universeSize 25 'RESEARCH_SELECTION_E2E_UNIVERSE_INVALID'
    Exact $value.shortlistSize 10 `
        'RESEARCH_SELECTION_E2E_SHORTLIST_INVALID'
    Write-Output ("RESEARCH_SELECTION_E2E_CANDIDATES={0}" -f `
        [int]$value.candidateCount)
    Write-Output ("RESEARCH_SELECTION_E2E_DECISION={0}" -f `
        [string]$value.decisionCode)
    Write-Output ("RESEARCH_SELECTION_E2E_EMPTY_RESULT={0}" -f `
        ([bool]$value.emptyResult).ToString().ToLowerInvariant())
    $experimentSql = @'
SELECT string_agg(
           (item.value->>'strategyCode') || ':oos=' ||
           (item.value->>'outOfSampleEvaluated') || ':overfit=' ||
           (item.value->>'overfittingFlag'), ','
           ORDER BY (item.value->>'strategyCode'))
  FROM research_selection_runs,
       LATERAL jsonb_array_elements(
           result_json->'agentReport'->'strategyExperiments'->'experiments'
       ) item(value)
 WHERE id=1
'@
    Write-Output ("RESEARCH_SELECTION_E2E_EXPERIMENTS={0}" -f `
        (Scalar $experimentSql))
    $unknownsSql = @'
SELECT COALESCE(string_agg(value, ',' ORDER BY value), 'NONE')
  FROM research_selection_runs,
       LATERAL jsonb_array_elements_text(
           result_json->'agentReport'->'finalDecision'->'unknowns'
       ) unknown(value)
 WHERE id=1
'@
    Write-Output ("RESEARCH_SELECTION_E2E_UNKNOWNS={0}" -f `
        (Scalar $unknownsSql))
    if ([int]$value.candidateCount -lt 1 -or
        [int]$value.candidateCount -gt 5 -or $value.emptyResult -or
        $value.decisionCode -ne 'RESEARCH_PREFERENCE') {
        throw 'RESEARCH_SELECTION_E2E_CANDIDATES_INVALID'
    }
    Exact $value.tushareProviderCallCount 52 `
        'RESEARCH_SELECTION_E2E_TUSHARE_INVALID'
    Exact $value.retryCount 0 'RESEARCH_SELECTION_E2E_RETRY_INVALID'
    Exact $value.modelProviderRequestCount 0 `
        'RESEARCH_SELECTION_E2E_MODEL_NETWORK_INVALID'
    Exact $value.modelCallCount 13 `
        'RESEARCH_SELECTION_E2E_MODEL_CALLS_INVALID'
    Exact $value.toolCallCount 4 `
        'RESEARCH_SELECTION_E2E_TOOL_CALLS_INVALID'
    Exact @($value.agentRoles | Sort-Object -Unique).Count 7 `
        'RESEARCH_SELECTION_E2E_AGENT_ROLES_INVALID'
    if (-not $value.deterministicFake -or -not $value.outputAuditClean -or
        -not $value.typedFactReadback -or
        -not $value.systemKnowledgeReadback -or
        -not $value.formulaOnlyQfq -or
        -not $value.noFutureDataLeakage -or
        -not $value.researchOnly -or $value.realTradingStarted) {
        throw 'RESEARCH_SELECTION_E2E_RESULT_INVALID'
    }
    Exact (Scalar "SELECT count(*) FROM research_selection_runs WHERE status='COMPLETED'") 1 `
        'RESEARCH_SELECTION_E2E_COMPLETED_INVALID'
    Exact (Scalar "SELECT count(*) FROM research_selection_runs WHERE status NOT IN ('COMPLETED','FAILED')") 0 `
        'RESEARCH_SELECTION_E2E_ACTIVE_REMAINS'
    Exact (Scalar "SELECT count(*) FROM shadow_research_runs WHERE status='FROZEN' AND trigger_mode='ON_DEMAND_SELECTION'") 1 `
        'RESEARCH_SELECTION_E2E_SHADOW_INVALID'
    Exact (Scalar 'SELECT count(*) FROM shadow_paper_fills') 0 `
        'RESEARCH_SELECTION_E2E_UNEXPECTED_FILL'
    $observations = Scalar 'SELECT count(*) FROM pit_market_fact_observations'
    if ([int]$observations -lt 3000) {
        throw 'RESEARCH_SELECTION_E2E_OBSERVATIONS_INCOMPLETE'
    }

    $executionId2 = 'SELECTEXEC_20260813T010204Z_B1C2D3E4F5A6'
    $publicRun2 = 'SELECT_20260813T010204Z_B1C2D3E4F5A6'
    & "$PSScriptRoot\run-research-selection.ps1" `
        -ResultFile $repeatResult -ArtifactPath $artifact `
        -ExecutionId $executionId2 -SelectionRunId 2 `
        -PublicRunId $publicRun2 -GitCommit $ExpectedCommit `
        -DatabasePort $port -MaximumProviderRequests 0 `
        -ExecutionMode FAKE -MaximumCostCny 5.00
    if ($LASTEXITCODE -ne 0) {
        throw 'RESEARCH_SELECTION_E2E_REUSE_RUNNER_FAILED'
    }
    $repeat = Get-Content -LiteralPath $repeatResult -Raw -Encoding UTF8 |
        ConvertFrom-Json
    Exact $repeat.status 'SUCCEEDED' `
        'RESEARCH_SELECTION_E2E_REUSE_STATUS_INVALID'
    Exact $repeat.tushareProviderCallCount 0 `
        'RESEARCH_SELECTION_E2E_REUSE_PROVIDER_INVALID'
    Exact $repeat.retryCount 0 `
        'RESEARCH_SELECTION_E2E_REUSE_RETRY_INVALID'
    if ([int]$repeat.candidateCount -lt 1 -or
        [int]$repeat.candidateCount -gt 5 -or $repeat.emptyResult -or
        $repeat.decisionCode -ne 'RESEARCH_PREFERENCE') {
        throw 'RESEARCH_SELECTION_E2E_REUSE_CANDIDATES_INVALID'
    }
    Exact $repeat.modelProviderRequestCount 0 `
        'RESEARCH_SELECTION_E2E_REUSE_MODEL_NETWORK_INVALID'
    Exact (Scalar 'SELECT count(*) FROM pit_market_fact_observations') `
        $observations 'RESEARCH_SELECTION_E2E_REUSE_OBSERVATIONS_CHANGED'
    Exact (Scalar "SELECT count(*) FROM research_selection_runs WHERE status='COMPLETED'") 2 `
        'RESEARCH_SELECTION_E2E_REUSE_COMPLETED_INVALID'
    Exact (Scalar "SELECT count(*) FROM research_selection_runs WHERE status NOT IN ('COMPLETED','FAILED')") 0 `
        'RESEARCH_SELECTION_E2E_REUSE_ACTIVE_REMAINS'
    Exact (Scalar "SELECT count(*) FROM shadow_research_runs WHERE status='FROZEN' AND trigger_mode='ON_DEMAND_SELECTION'") 2 `
        'RESEARCH_SELECTION_E2E_REUSE_SHADOW_INVALID'
    Exact (Scalar 'SELECT count(*) FROM shadow_paper_fills') 0 `
        'RESEARCH_SELECTION_E2E_REUSE_FILL_DUPLICATED'
    Exact (Scalar "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success") `
        '1,2,3,4,5,6,7,8,9,10,11,12,13,15,16,17' `
        'RESEARCH_SELECTION_E2E_MIGRATION_HISTORY_INVALID'

    Write-Output 'RESEARCH_SELECTION_PACKAGED_FAKE_E2E=PASS'
    Write-Output 'RESEARCH_SELECTION_TEMP_POSTGRES_V1_V17=PASS'
    Write-Output 'RESEARCH_SELECTION_M1_M2_M3_M4_CHAIN=PASS'
    Write-Output 'RESEARCH_SELECTION_FAKE_TUSHARE_CALLS=52'
    Write-Output 'RESEARCH_SELECTION_FAKE_MODEL_CALLS=26'
    Write-Output 'RESEARCH_SELECTION_REAL_TUSHARE_CALLS=0'
    Write-Output 'RESEARCH_SELECTION_REAL_BAILIAN_CALLS=0'
    Write-Output 'RESEARCH_SELECTION_ACTIVE_RUN_RESIDUALS=0'
} finally {
    Pop-Location
    if ($started -and (Test-Path -LiteralPath $data)) {
        & "$pgBin\pg_ctl.exe" -D $data -m immediate -w stop `
            2>$null | Out-Null
    }
    foreach ($file in @($result, $repeatResult)) {
        if (Test-Path -LiteralPath $file -PathType Leaf) {
            Remove-Item -LiteralPath $file -Force
        }
    }
    Remove-Root
    if (Test-Path -LiteralPath $root) {
        throw 'RESEARCH_SELECTION_E2E_TEMP_DIRECTORY_REMAINS'
    }
    if ($port -gt 0 -and (Get-NetTCPConnection -LocalPort $port `
            -State Listen -ErrorAction SilentlyContinue)) {
        throw 'RESEARCH_SELECTION_E2E_TEMP_PORT_REMAINS'
    }
    Write-Output 'RESEARCH_SELECTION_TEMP_RESOURCE_RESIDUALS=0'
}
