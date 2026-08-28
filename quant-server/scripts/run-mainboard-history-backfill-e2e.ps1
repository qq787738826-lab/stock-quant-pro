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
    (Join-Path $env:TEMP 'stock-quant-pro-backfill-tests')).TrimEnd('\', '/')
$prefix = 'stock-quant-mainboard-250-e2e-'
$root = Join-Path $tempBase ($prefix + [Guid]::NewGuid().ToString('N'))
$data = Join-Path $root 'data'
$log = Join-Path $root 'postgres.log'
$artifact = Join-Path $target `
    'quant-server-1.3.1-mainboard-history-backfill-runner.jar'
$result = Join-Path $target `
    ('mainboard-250-e2e-' + [Guid]::NewGuid().ToString('N') + '.json')
$port = 0
$started = $false

function Scalar([string] $Sql) {
    $value = & "$pgBin\psql.exe" -X -q -A -t -h 127.0.0.1 `
        -p $port -U stock_quant_research -d stock_quant_research `
        -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) {
        throw 'MAINBOARD_HISTORY_BACKFILL_E2E_QUERY_FAILED'
    }
    return ($value | Select-Object -Last 1).Trim()
}

function Remove-Root {
    if (-not (Test-Path -LiteralPath $root)) { return }
    $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne
            $tempBase -or
        -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
        throw 'MAINBOARD_HISTORY_BACKFILL_E2E_CLEANUP_PATH_INVALID'
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
        throw 'MAINBOARD_HISTORY_BACKFILL_E2E_GIT_INVALID'
    }
    & "$PSScriptRoot\prepare-mainboard-history-backfill-build-proof.ps1" `
        -ExpectedCommit $ExpectedCommit -Mode E2E_DRY_RUN
    if ($LASTEXITCODE -ne 0 -or
        -not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath `
            "$artifact.f1f-b2-proof.properties" -PathType Leaf)) {
        throw 'MAINBOARD_HISTORY_BACKFILL_E2E_BUILD_FAILED'
    }

    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    if ($port -eq 38432) {
        throw 'MAINBOARD_HISTORY_BACKFILL_E2E_PORT_INVALID'
    }
    New-Item -ItemType Directory -Path $root | Out-Null
    & "$pgBin\initdb.exe" -D $data -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'MAINBOARD_HISTORY_BACKFILL_E2E_INITDB_FAILED'
    }
    $arguments = '-D "{0}" -l "{1}" -o "-h 127.0.0.1 -p {2}" -w start' `
        -f $data, $log, $port
    $process = Start-Process "$pgBin\pg_ctl.exe" -ArgumentList $arguments `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru
    try {
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw 'MAINBOARD_HISTORY_BACKFILL_E2E_POSTGRES_START_FAILED'
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
        throw 'MAINBOARD_HISTORY_BACKFILL_E2E_DATABASE_SETUP_FAILED'
    }

    & "$PSScriptRoot\run-mainboard-history-backfill.ps1" `
        -ResultFile $result -ArtifactPath $artifact `
        -ExecutionId 'MBH250_20260828T020000Z_A1B2C3D4E5F6' `
        -GitCommit $ExpectedCommit -AnchorTradeDate '2026-08-27' `
        -TargetSessions 250 -ExpectedMissingSessions 190 `
        -DatabasePort $port -MaximumProviderRequests 384 `
        -NetworkRecoveryBudget 4 -ExecutionMode FAKE
    if ($LASTEXITCODE -ne 0) {
        throw 'MAINBOARD_HISTORY_BACKFILL_E2E_RUNNER_FAILED'
    }
    $value = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($value.status -ne 'SUCCEEDED' -or
        [int]$value.originalCompleteSessions -ne 60 -or
        [int]$value.expectedMissingSessions -ne 190 -or
        [int]$value.finalCompleteSessions -ne 250 -or
        @($value.completedTradeDates).Count -ne 190 -or
        [int]$value.tushareProviderCallCount -ne 380 -or
        [int]$value.dailyProviderCallCount -ne 190 -or
        [int]$value.adjustmentFactorProviderCallCount -ne 190 -or
        [int]$value.retryCount -ne 0 -or
        [int]$value.universeMemberCount -ne 3000 -or
        -not $value.milestone120Complete -or
        -not $value.final250Complete -or
        -not $value.knownAtValid -or
        -not $value.firstObservedAtValid -or
        $value.historicalResearchClassification -ne 'POST_HOC_RESEARCH' -or
        $value.pitClassification -ne 'PIT_PARTIAL' -or
        -not $value.outputAuditClean -or -not $value.dataOnly -or
        $value.realTradingStarted) {
        throw 'MAINBOARD_HISTORY_BACKFILL_E2E_RESULT_INVALID'
    }
    if ([int](Scalar 'SELECT count(*) FROM research_universe_members') -ne
            3000 -or
        [int](Scalar "SELECT count(DISTINCT fact_effective_at::date) FROM pit_market_fact_observations WHERE fact_type='RAW_DAILY_BAR'") -ne 250 -or
        [int](Scalar "SELECT count(DISTINCT fact_effective_at::date) FROM pit_market_fact_observations WHERE fact_type='ADJUSTMENT_FACTOR'") -ne 250 -or
        [int](Scalar "SELECT count(*) FROM research_selection_runs") -ne 0 -or
        [int](Scalar "SELECT count(*) FROM shadow_research_runs") -ne 0 -or
        [int](Scalar "SELECT count(*) FROM shadow_paper_orders") -ne 0) {
        throw 'MAINBOARD_HISTORY_BACKFILL_E2E_DATABASE_INVALID'
    }
    Write-Output 'MAINBOARD_HISTORY_BACKFILL_PACKAGED_FAKE_E2E=PASS'
    Write-Output 'MAINBOARD_HISTORY_BACKFILL_TEMP_POSTGRES_V1_V18=PASS'
    Write-Output 'MAINBOARD_HISTORY_BACKFILL_FAKE_UNIVERSE=3000'
    Write-Output 'MAINBOARD_HISTORY_BACKFILL_FAKE_COMPLETE_SESSIONS=250'
    Write-Output 'MAINBOARD_HISTORY_BACKFILL_FAKE_PROVIDER_ATTEMPTS=380'
    Write-Output 'MAINBOARD_HISTORY_BACKFILL_REAL_TUSHARE_CALLS=0'
    Write-Output 'MAINBOARD_HISTORY_BACKFILL_REAL_BAILIAN_CALLS=0'
    Write-Output 'MAINBOARD_HISTORY_BACKFILL_BUSINESS_RUNS_CREATED=0'
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
        throw 'MAINBOARD_HISTORY_BACKFILL_E2E_TEMP_DIRECTORY_REMAINS'
    }
    if ($port -gt 0 -and (Get-NetTCPConnection -LocalPort $port `
            -State Listen -ErrorAction SilentlyContinue)) {
        throw 'MAINBOARD_HISTORY_BACKFILL_E2E_TEMP_PORT_REMAINS'
    }
    Write-Output 'MAINBOARD_HISTORY_BACKFILL_TEMP_RESOURCE_RESIDUALS=0'
}
