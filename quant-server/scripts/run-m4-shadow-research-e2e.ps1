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
    (Join-Path $env:TEMP 'stock-quant-pro-m4-tests')).TrimEnd('\', '/')
$prefix = 'stock-quant-m4-e2e-'
$root = Join-Path $tempBase ($prefix + [Guid]::NewGuid().ToString('N'))
$data = Join-Path $root 'data'
$log = Join-Path $root 'postgres.log'
$artifact = Join-Path $target `
    'quant-server-1.3.1-m4-shadow-research-runner.jar'
$result = Join-Path $target (
    'm4-shadow-e2e-' + [Guid]::NewGuid().ToString('N') + '.json')
$port = 0
$started = $false

function Exact([object] $Actual, [object] $Expected, [string] $Code) {
    if ([string]$Actual -ne [string]$Expected) { throw $Code }
}

function Scalar([string] $Sql) {
    $value = & "$pgBin\psql.exe" -X -q -A -t -h 127.0.0.1 `
        -p $port -U stock_quant_research -d stock_quant_research `
        -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) { throw 'M4_E2E_QUERY_FAILED' }
    return ($value | Select-Object -Last 1).Trim()
}

function Remove-Root {
    if (-not (Test-Path -LiteralPath $root)) { return }
    $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne $tempBase -or
        -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
        throw 'M4_E2E_CLEANUP_PATH_INVALID'
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
        throw 'M4_E2E_GIT_INVALID'
    }
    foreach ($compiled in @(
            (Join-Path $repoRoot 'quant-core\target\classes'),
            (Join-Path $repoRoot 'quant-core\target\test-classes'),
            (Join-Path $repoRoot 'quant-server\target\classes'),
            (Join-Path $repoRoot 'quant-server\target\test-classes'))) {
        if (Test-Path -LiteralPath $compiled) {
            Remove-Item -LiteralPath $compiled -Recurse -Force
        }
    }
    & "$repoRoot\mvnw.cmd" -pl quant-server -am '-DskipTests' compile
    if ($LASTEXITCODE -ne 0) { throw 'M4_E2E_FRESH_COMPILE_FAILED' }
    & "$PSScriptRoot\prepare-m4-shadow-research-build-proof.ps1" `
        -ExpectedCommit $ExpectedCommit -Mode E2E_DRY_RUN
    if ($LASTEXITCODE -ne 0) { throw 'M4_E2E_BUILD_FAILED' }

    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    if ($port -eq 38432) { throw 'M4_E2E_PORT_INVALID' }
    New-Item -ItemType Directory -Path $root | Out-Null
    & "$pgBin\initdb.exe" -D $data -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'M4_E2E_INITDB_FAILED' }
    $arguments = '-D "{0}" -l "{1}" -o "-h 127.0.0.1 -p {2}" -w start' `
        -f $data, $log, $port
    $process = Start-Process "$pgBin\pg_ctl.exe" -ArgumentList $arguments `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru
    try {
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw 'M4_E2E_POSTGRES_START_FAILED'
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
    if ($LASTEXITCODE -ne 0) { throw 'M4_E2E_DATABASE_SETUP_FAILED' }

    $executionId = 'M4SHADOW_20260812T010203Z_A1B2C3D4E5F6'
    & "$PSScriptRoot\run-m4-shadow-research.ps1" -ResultFile $result `
        -ArtifactPath $artifact -ExecutionId $executionId `
        -DatabasePort $port -ExecutionMode FAKE `
        -RangeStart '2025-01-02' -TradeDate '2025-01-10' `
        -NextTradeDate INTERNAL_CALENDAR -CalendarAdmission UNKNOWN `
        -CalendarHorizonEnd '2025-02-09' `
        -CaptureMode CAPTURE -TriggerMode HISTORICAL_REPLAY `
        -MaximumCostCny 5.00
    if ($LASTEXITCODE -ne 0) { throw 'M4_E2E_RUNNER_FAILED' }
    $value = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    Exact $value.status 'SUCCEEDED' 'M4_E2E_STATUS_INVALID'
    Exact $value.gitCommit $ExpectedCommit 'M4_E2E_COMMIT_INVALID'
    Exact $value.tushareProviderCallCount 8 'M4_E2E_TUSHARE_INVALID'
    Exact $value.retryCount 0 'M4_E2E_RETRY_INVALID'
    Exact $value.modelProviderRequestCount 0 'M4_E2E_MODEL_NETWORK_INVALID'
    Exact $value.modelCallCount 13 'M4_E2E_MODEL_CALLS_INVALID'
    Exact $value.toolCallCount 4 'M4_E2E_TOOL_CALLS_INVALID'
    Exact @($value.agentRoles | Sort-Object -Unique).Count 7 `
        'M4_E2E_AGENT_ROLES_INVALID'
    if (-not $value.deterministicFake -or -not $value.outputAuditClean -or
        -not $value.typedFactReadback -or
        -not $value.systemKnowledgeReadback -or
        -not $value.formulaOnlyQfq -or
        -not $value.noFutureDataLeakage -or -not $value.researchOnly -or
        $value.brokerConnected -or $value.realTradingStarted) {
        throw 'M4_E2E_RESULT_INVALID'
    }
    Exact (Scalar 'SELECT count(*) FROM shadow_research_runs WHERE status=''FROZEN''') 1 `
        'M4_E2E_FROZEN_INVALID'
    Exact (Scalar 'SELECT count(*) FROM shadow_research_runs WHERE status IN (''QUEUED'',''RUNNING'')') 0 `
        'M4_E2E_ACTIVE_REMAINS'
    Exact (Scalar 'SELECT count(*) FROM shadow_research_snapshots') 1 `
        'M4_E2E_SNAPSHOT_INVALID'
    Exact (Scalar 'SELECT count(*) FROM shadow_paper_fills') 0 `
        'M4_E2E_UNEXPECTED_FILL'
    Exact (Scalar "SELECT count(*) FROM shadow_research_runs WHERE paper_execution_time IS NOT NULL AND paper_execution_time::date>'2025-01-10'") 1 `
        'M4_E2E_INTERNAL_NEXT_OPEN_NOT_RESOLVED'
    Exact (Scalar 'SELECT count(*) FROM shadow_portfolio_snapshots WHERE run_id IS NULL') 1 `
        'M4_E2E_DAILY_MAINTENANCE_SNAPSHOT_MISSING'
    Exact (Scalar 'SELECT count(*) FROM shadow_paper_portfolios') 1 `
        'M4_E2E_PORTFOLIO_INVALID'
    Exact (Scalar "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success") `
        '1,2,3,4,5,6,7,8,9,10,11,12,13,15,16' `
        'M4_E2E_MAIN_HISTORY_INVALID'

    Write-Output 'M4_PACKAGED_FAKE_E2E=PASS'
    Write-Output 'M4_TEMP_POSTGRES_V1_V16=PASS'
    Write-Output 'M4_M1_M2_M3_M4_CHAIN=PASS'
    Write-Output 'M4_FAKE_TUSHARE_CALLS=8'
    Write-Output 'M4_REAL_TUSHARE_CALLS=0'
    Write-Output 'M4_REAL_BAILIAN_CALLS=0'
    Write-Output 'M4_ACTIVE_RUN_RESIDUALS=0'
} finally {
    Pop-Location
    if ($started -and (Test-Path -LiteralPath $data)) {
        & "$pgBin\pg_ctl.exe" -D $data -m immediate -w stop `
            2>$null | Out-Null
    }
    if (Test-Path -LiteralPath $result -PathType Leaf) {
        Remove-Item -LiteralPath $result -Force
    }
    Remove-Root
    if (Test-Path -LiteralPath $root) {
        throw 'M4_E2E_TEMP_DIRECTORY_REMAINS'
    }
    if ($port -gt 0 -and (Get-NetTCPConnection -LocalPort $port `
            -State Listen -ErrorAction SilentlyContinue)) {
        throw 'M4_E2E_TEMP_PORT_REMAINS'
    }
    Write-Output 'M4_TEMP_RESOURCE_RESIDUALS=0'
}
