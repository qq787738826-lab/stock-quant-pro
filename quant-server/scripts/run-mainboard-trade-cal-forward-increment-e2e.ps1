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
    (Join-Path $env:TEMP 'stock-quant-pro-forward-calendar-tests'))
$tempBase = $tempBase.TrimEnd('\', '/')
$prefix = 'stock-quant-forward-calendar-e2e-'
$root = Join-Path $tempBase ($prefix + [Guid]::NewGuid().ToString('N'))
$data = Join-Path $root 'data'
$log = Join-Path $root 'postgres.log'
$artifact = Join-Path $target `
    'quant-server-1.3.1-mainboard-trade-cal-forward-increment-runner.jar'
$result1 = Join-Path $target `
    ('forward-calendar-e2e-' + [Guid]::NewGuid().ToString('N') + '.json')
$result2 = Join-Path $target `
    ('forward-calendar-noop-' + [Guid]::NewGuid().ToString('N') + '.json')
$result3 = Join-Path $target `
    ('forward-calendar-earlier-' + [Guid]::NewGuid().ToString('N') + '.json')
$port = 0
$started = $false

function Scalar([string] $Sql) {
    $value = & "$pgBin\psql.exe" -X -q -A -t -h 127.0.0.1 `
        -p $port -U stock_quant_research -d stock_quant_research `
        -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_QUERY_FAILED'
    }
    return ($value | Select-Object -Last 1).Trim()
}

function Remove-Root {
    if (-not (Test-Path -LiteralPath $root)) { return }
    $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne
            $tempBase -or
        -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_CLEANUP_PATH_INVALID'
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

function Run-Forward(
    [string] $Result,
    [string] $ExecutionId,
    [string] $TargetDate
) {
    & "$PSScriptRoot\run-mainboard-trade-cal-forward-increment.ps1" `
        -ResultFile $Result -ArtifactPath $artifact `
        -ExecutionId $ExecutionId -GitCommit $ExpectedCommit `
        -TargetEndDate $TargetDate `
        -DatabasePort $port -MaximumProviderRequests 4 `
        -NetworkRecoveryBudget 2 -ExecutionMode FAKE
    if ($LASTEXITCODE -ne 0) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_RUNNER_FAILED'
    }
    return Get-Content -LiteralPath $Result -Raw -Encoding UTF8 |
        ConvertFrom-Json
}

Push-Location $repoRoot
try {
    New-Item -ItemType Directory -Path $tempBase -Force | Out-Null
    if ((git rev-parse HEAD).Trim() -ne $ExpectedCommit -or
        @(git status --porcelain=v1 --untracked-files=normal |
            Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' }
        ).Count -ne 0 -or @(git diff --cached --name-only).Count -ne 0) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_GIT_INVALID'
    }
    & "$PSScriptRoot\prepare-mainboard-trade-cal-forward-increment-build-proof.ps1" `
        -ExpectedCommit $ExpectedCommit -Mode E2E_DRY_RUN
    if ($LASTEXITCODE -ne 0 -or
        -not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath `
            "$artifact.f1f-b2-proof.properties" -PathType Leaf)) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_BUILD_FAILED'
    }

    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    if ($port -eq 38432) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_PORT_INVALID'
    }
    New-Item -ItemType Directory -Path $root | Out-Null
    & "$pgBin\initdb.exe" -D $data -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_INITDB_FAILED'
    }
    $arguments = '-D "{0}" -l "{1}" -o "-h 127.0.0.1 -p {2}" -w start' `
        -f $data, $log, $port
    $process = Start-Process "$pgBin\pg_ctl.exe" -ArgumentList $arguments `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru
    try {
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_POSTGRES_START_FAILED'
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
        throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_DATABASE_SETUP_FAILED'
    }

    $first = Run-Forward -Result $result1 `
        -ExecutionId 'MBTCFWD_20260917T120000Z_A1B2C3D4E5F6' `
        -TargetDate '2026-09-17'
    $oldFingerprint = Scalar @'
SELECT md5(string_agg(o.id::text || ':' || o.canonical_content_hash,
                      ',' ORDER BY o.id))
  FROM pit_market_fact_observations o
  JOIN trading_calendar_facts_v1 c ON c.observation_id=o.id
 WHERE c.calendar_date <= DATE '2026-08-27'
'@
    if ($first.status -ne 'SUCCEEDED' -or $first.action -ne 'APPENDED' -or
        [string]$first.currentSseMaxCalDate -ne '2026-08-27' -or
        [string]$first.currentSzseMaxCalDate -ne '2026-08-27' -or
        [string]$first.startDate -ne '2026-08-28' -or
        [string]$first.finalSseMaxCalDate -ne '2026-09-17' -or
        [string]$first.finalSzseMaxCalDate -ne '2026-09-17' -or
        [int]$first.rangeCalendarDateCount -ne 21 -or
        [int]$first.tushareProviderCallCount -ne 2 -or
        [int]$first.sseTradeCalendarProviderCallCount -ne 1 -or
        [int]$first.szseTradeCalendarProviderCallCount -ne 1 -or
        [int]$first.appendedObservationCount -ne 42 -or
        [int]$first.idempotentChainTailHits -ne 0 -or
        [int]$first.duplicateCount -ne 0 -or
        [int]$first.universeMemberCount -ne 3000) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_FIRST_RESULT_INVALID'
    }

    $second = Run-Forward -Result $result2 `
        -ExecutionId 'MBTCFWD_20260917T120100Z_B1C2D3E4F5A6' `
        -TargetDate '2026-09-17'
    $earlier = Run-Forward -Result $result3 `
        -ExecutionId 'MBTCFWD_20260917T120200Z_C1D2E3F4A5B6' `
        -TargetDate '2026-09-10'
    foreach ($noop in @($second, $earlier)) {
        if ($noop.status -ne 'SUCCEEDED' -or $noop.action -ne 'NO_OP' -or
            [int]$noop.tushareProviderCallCount -ne 0 -or
            [int]$noop.sseTradeCalendarProviderCallCount -ne 0 -or
            [int]$noop.szseTradeCalendarProviderCallCount -ne 0 -or
            [int]$noop.appendedObservationCount -ne 0) {
            throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_NOOP_INVALID'
        }
    }

    $newRows = Scalar @'
SELECT count(*)
  FROM pit_market_fact_observations o
  JOIN trading_calendar_facts_v1 c ON c.observation_id=o.id
 WHERE c.calendar_date BETWEEN DATE '2026-08-28' AND DATE '2026-09-17'
   AND o.raw_payload_json->>'endpoint' = 'trade_cal'
   AND o.raw_payload_json->'providerRow' ? 'cal_date'
   AND o.raw_payload_json->'providerRow' ? 'is_open'
   AND o.raw_payload_json->'providerRow' ? 'pretrade_date'
'@
    $duplicate = Scalar @'
SELECT count(*) FROM (
    SELECT exchange, calendar_date
      FROM trading_calendar_facts_v1
     GROUP BY exchange, calendar_date
    HAVING count(*) > 1
) duplicate_rows
'@
    if ([int]$newRows -ne 42 -or [int]$duplicate -ne 0 -or
        (Scalar "SELECT max(calendar_date)::text FROM trading_calendar_facts_v1 WHERE exchange='SSE'") -ne '2026-09-17' -or
        (Scalar "SELECT max(calendar_date)::text FROM trading_calendar_facts_v1 WHERE exchange='SZSE'") -ne '2026-09-17' -or
        (Scalar @'
SELECT md5(string_agg(o.id::text || ':' || o.canonical_content_hash,
                      ',' ORDER BY o.id))
  FROM pit_market_fact_observations o
  JOIN trading_calendar_facts_v1 c ON c.observation_id=o.id
 WHERE c.calendar_date <= DATE '2026-08-27'
'@) -ne $oldFingerprint -or
        [int](Scalar 'SELECT count(*) FROM raw_daily_bar_facts_v2') -ne 0 -or
        [int](Scalar 'SELECT count(*) FROM adjustment_factor_facts_v1') -ne 0 -or
        [int](Scalar 'SELECT count(*) FROM research_selection_runs') -ne 0 -or
        [int](Scalar 'SELECT count(*) FROM shadow_research_runs') -ne 0 -or
        [int](Scalar 'SELECT count(*) FROM shadow_paper_orders') -ne 0) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_DATABASE_INVALID'
    }

    $env:STOCK_QUANT_FORWARD_TEST_JDBC_URL =
        "jdbc:postgresql://127.0.0.1:$port/stock_quant_research"
    $env:STOCK_QUANT_FORWARD_TEST_DB_USER = 'stock_quant_research'
    $env:STOCK_QUANT_FORWARD_TEST_DB_PASSWORD = 'LOCAL_TRUST_ONLY'
    try {
        & .\mvnw.cmd -o -pl quant-server -am `
            '-Dtest=MainboardTradeCalendarForwardIncrementPostgresTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' test
        if ($LASTEXITCODE -ne 0) {
            throw 'MAINBOARD_TRADE_CAL_FORWARD_FAILURE_ATOMICITY_TEST_FAILED'
        }
    } finally {
        Remove-Item Env:STOCK_QUANT_FORWARD_TEST_JDBC_URL `
            -ErrorAction SilentlyContinue
        Remove-Item Env:STOCK_QUANT_FORWARD_TEST_DB_USER `
            -ErrorAction SilentlyContinue
        Remove-Item Env:STOCK_QUANT_FORWARD_TEST_DB_PASSWORD `
            -ErrorAction SilentlyContinue
    }
    Write-Output 'MAINBOARD_TRADE_CAL_FORWARD_PACKAGED_FAKE_E2E=PASS'
    Write-Output 'MAINBOARD_TRADE_CAL_FORWARD_TEMP_POSTGRES_V1_V18=PASS'
    Write-Output 'MAINBOARD_TRADE_CAL_FORWARD_FAKE_SSE_CALLS=1'
    Write-Output 'MAINBOARD_TRADE_CAL_FORWARD_FAKE_SZSE_CALLS=1'
    Write-Output 'MAINBOARD_TRADE_CAL_FORWARD_REPEAT_NOOP_CALLS=0'
    Write-Output 'MAINBOARD_TRADE_CAL_FORWARD_EARLIER_NOOP_CALLS=0'
    Write-Output 'MAINBOARD_TRADE_CAL_FORWARD_SINGLE_SIDE_FAILURE_ATOMIC=PASS'
    Write-Output 'MAINBOARD_TRADE_CAL_FORWARD_REAL_TUSHARE_CALLS=0'
    Write-Output 'MAINBOARD_TRADE_CAL_FORWARD_REAL_BAILIAN_CALLS=0'
} finally {
    Pop-Location
    if ($started -and (Test-Path -LiteralPath $data)) {
        & "$pgBin\pg_ctl.exe" -D $data -m immediate -w stop `
            2>$null | Out-Null
    }
    foreach ($result in @($result1, $result2, $result3)) {
        if (Test-Path -LiteralPath $result -PathType Leaf) {
            Remove-Item -LiteralPath $result -Force
        }
    }
    Remove-Root
    if (Test-Path -LiteralPath $root) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_TEMP_DIRECTORY_REMAINS'
    }
    if ($port -gt 0 -and (Get-NetTCPConnection -LocalPort $port `
            -State Listen -ErrorAction SilentlyContinue)) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_E2E_TEMP_PORT_REMAINS'
    }
    Write-Output 'MAINBOARD_TRADE_CAL_FORWARD_TEMP_RESOURCE_RESIDUALS=0'
}
