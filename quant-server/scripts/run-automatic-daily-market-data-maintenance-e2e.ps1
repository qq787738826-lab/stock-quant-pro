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
$brokerResults = Join-Path $target 'stock-quant-host-broker\results'
$tempBase = [IO.Path]::GetFullPath((Join-Path $env:TEMP `
    'stock-quant-pro-automatic-maintenance-tests')).TrimEnd('\', '/')
$prefix = 'stock-quant-automatic-maintenance-e2e-'
$root = Join-Path $tempBase ($prefix + [Guid]::NewGuid().ToString('N'))
$data = Join-Path $root 'data'
$log = Join-Path $root 'postgres.log'
$result1 = Join-Path $target `
    ('automatic-maintenance-e2e-' + [Guid]::NewGuid().ToString('N') +
        '.json')
$result2 = Join-Path $target `
    ('automatic-maintenance-idempotent-' +
        [Guid]::NewGuid().ToString('N') + '.json')
$result3 = Join-Path $target `
    ('automatic-maintenance-budget-' +
        [Guid]::NewGuid().ToString('N') + '.json')
$result4 = Join-Path $target `
    ('automatic-maintenance-partial-' +
        [Guid]::NewGuid().ToString('N') + '.json')
$port = 0
$started = $false
$generatedResults = [Collections.Generic.List[string]]::new()
$oldUrl = $env:STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_JDBC_URL
$oldUser = $env:STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_DB_USER
$oldPassword = $env:STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_DB_PASSWORD
$oldCommit = $env:STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_GIT_COMMIT
$e2eStartedAt = [DateTimeOffset]::UtcNow

function Scalar([string] $Sql) {
    $value = & "$pgBin\psql.exe" -X -q -A -t -h 127.0.0.1 `
        -p $port -U stock_quant_research -d stock_quant_research `
        -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_QUERY_FAILED'
    }
    return ($value | Select-Object -Last 1).Trim()
}

function New-TestRequestId([string] $Phase) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes(
            "$Phase|$ExpectedCommit|$([Guid]::NewGuid().ToString('N'))")
        $hash = ([BitConverter]::ToString(
            $sha.ComputeHash($bytes))).Replace('-', '')
        return "SQHB_20260831T083000Z_$($hash.Substring(0, 12))"
    } finally { $sha.Dispose() }
}

function Register-StageResults([object] $Value) {
    foreach ($pair in @(
        @([string]$Value.forwardRequestId,
            'mainboard-trade-cal-forward-increment'),
        @([string]$Value.initialAuditRequestId,
            'mainboard-catchup-readonly-audit'),
        @([string]$Value.finalAuditRequestId,
            'mainboard-catchup-readonly-audit'))) {
        if ($pair[0] -ne 'NONE') {
            $generatedResults.Add((Join-Path $brokerResults `
                "$($pair[0]).$($pair[1]).json"))
        }
    }
    foreach ($daily in @($Value.dailyResults)) {
        $generatedResults.Add((Join-Path $brokerResults `
            "$([string]$daily.requestId).mainboard-daily-increment.json"))
    }
}

function Run-Maintenance(
    [string] $Result,
    [string] $RequestId,
    [int] $CurrentLedger,
    [int] $MaximumProviderRequests
) {
    $output = @(& `
        "$PSScriptRoot\run-automatic-daily-market-data-maintenance.ps1" `
        -ResultFile $Result -RequestId $RequestId `
        -GitCommit $ExpectedCommit -TargetEndDate '2026-08-31' `
        -DatabasePort $port -CurrentLedger $CurrentLedger `
        -LedgerLimit 450 `
        -MaximumProviderRequests $MaximumProviderRequests `
        -ExecutionMode FAKE 2>&1 | ForEach-Object { [string]$_ })
    $exitCode = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $Result -PathType Leaf)) {
        $output | Write-Output
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_RESULT_MISSING'
    }
    $value = Get-Content -LiteralPath $Result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    Register-StageResults $value
    return [pscustomobject]@{ ExitCode = $exitCode; Value = $value }
}

function Remove-TestRoot {
    if (-not (Test-Path -LiteralPath $root)) { return }
    $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne
            $tempBase -or
        -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_CLEANUP_PATH_INVALID'
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

Push-Location $repoRoot
try {
    New-Item -ItemType Directory -Path $tempBase -Force | Out-Null
    $unexpected = @(git status --porcelain=v1 --untracked-files=normal |
        Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' })
    if ((git rev-parse HEAD).Trim() -ne $ExpectedCommit -or
        $unexpected.Count -ne 0 -or
        @(git diff --cached --name-only).Count -ne 0) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_GIT_INVALID'
    }
    foreach ($build in @(
        'prepare-mainboard-trade-cal-forward-increment-build-proof.ps1',
        'prepare-mainboard-catchup-readonly-audit-build-proof.ps1',
        'prepare-mainboard-daily-increment-build-proof.ps1')) {
        & (Join-Path $PSScriptRoot $build) -ExpectedCommit $ExpectedCommit `
            -Mode E2E_DRY_RUN
        if ($LASTEXITCODE -ne 0) {
            throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_BUILD_FAILED'
        }
    }

    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    if ($port -eq 38432) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_PORT_INVALID'
    }
    New-Item -ItemType Directory -Path $root | Out-Null
    & "$pgBin\initdb.exe" -D $data -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_INITDB_FAILED'
    }
    $arguments = '-D "{0}" -l "{1}" -o "-h 127.0.0.1 -p {2}" -w start' `
        -f $data, $log, $port
    $process = Start-Process "$pgBin\pg_ctl.exe" `
        -ArgumentList $arguments -WorkingDirectory $root `
        -WindowStyle Hidden -PassThru
    try {
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_POSTGRES_START_FAILED'
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
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_DATABASE_SETUP_FAILED'
    }

    $env:STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_JDBC_URL =
        "jdbc:postgresql://127.0.0.1:$port/stock_quant_research"
    $env:STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_DB_USER =
        'stock_quant_research'
    $env:STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_DB_PASSWORD =
        'TEMP_POSTGRES_TEST_ONLY'
    $env:STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_GIT_COMMIT =
        $ExpectedCommit
    & .\mvnw.cmd -o -pl quant-server -am `
        '-Dtest=AutomaticMarketDataMaintenancePostgresFixtureTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_FIXTURE_FAILED'
    }

    $first = Run-Maintenance -Result $result1 `
        -RequestId (New-TestRequestId 'FIRST') -CurrentLedger 0 `
        -MaximumProviderRequests 450
    $value = $first.Value
    if ($first.ExitCode -ne 0 -or $value.status -ne 'SUCCEEDED' -or
        -not $value.readyForShadow -or
        [string]$value.initialLatestCompleteTradeDate -ne '2026-08-27' -or
        [string]$value.finalLatestCompleteTradeDate -ne '2026-08-31' -or
        [string]$value.finalLatestCommonCompletedOpenTradeDate -ne
            '2026-08-31' -or
        @($value.initialMissingTradeDates).Count -ne 2 -or
        @($value.initialPartialTradeDates).Count -ne 0 -or
        @($value.finalMissingTradeDates).Count -ne 0 -or
        @($value.finalPartialTradeDates).Count -ne 0 -or
        @($value.dailyResults).Count -ne 2 -or
        [int]$value.tradeCalendarProviderCallCount -ne 2 -or
        [int]$value.dailyProviderCallCount -ne 2 -or
        [int]$value.adjustmentFactorProviderCallCount -ne 2 -or
        [int]$value.tushareProviderCallCount -ne 6 -or
        [int]$value.finalLedger -ne 6 -or
        [int]$value.duplicateCount -ne 0 -or
        [int]$value.modelCallCount -ne 0 -or
        -not $value.outputAuditClean -or $value.realTradingStarted) {
        [pscustomobject]@{
            status = [string]$value.status
            failureReason = [string]$value.failureReason
            readyForShadow = [bool]$value.readyForShadow
            initialLatestCompleteTradeDate =
                [string]$value.initialLatestCompleteTradeDate
            finalLatestCompleteTradeDate =
                [string]$value.finalLatestCompleteTradeDate
            finalLatestCommonCompletedOpenTradeDate =
                [string]$value.finalLatestCommonCompletedOpenTradeDate
            initialMissingTradeDateCount =
                @($value.initialMissingTradeDates).Count
            initialPartialTradeDateCount =
                @($value.initialPartialTradeDates).Count
            finalMissingTradeDateCount =
                @($value.finalMissingTradeDates).Count
            finalPartialTradeDateCount =
                @($value.finalPartialTradeDates).Count
            dailyResultCount = @($value.dailyResults).Count
            tradeCalendarProviderCallCount =
                [int]$value.tradeCalendarProviderCallCount
            dailyProviderCallCount = [int]$value.dailyProviderCallCount
            adjustmentFactorProviderCallCount =
                [int]$value.adjustmentFactorProviderCallCount
            tushareProviderCallCount =
                [int]$value.tushareProviderCallCount
            duplicateCount = [int]$value.duplicateCount
        } | ConvertTo-Json -Compress | Write-Output
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_FIRST_INVALID'
    }

    $second = Run-Maintenance -Result $result2 `
        -RequestId (New-TestRequestId 'SECOND') -CurrentLedger 6 `
        -MaximumProviderRequests 444
    if ($second.ExitCode -ne 0 -or
        $second.Value.status -ne 'SUCCEEDED' -or
        -not $second.Value.readyForShadow -or
        [int]$second.Value.tushareProviderCallCount -ne 0 -or
        @($second.Value.initialMissingTradeDates).Count -ne 0 -or
        @($second.Value.dailyResults).Count -ne 0 -or
        [int]$second.Value.finalLedger -ne 6) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_IDEMPOTENCY_INVALID'
    }

    $budget = Run-Maintenance -Result $result3 `
        -RequestId (New-TestRequestId 'BUDGET') -CurrentLedger 449 `
        -MaximumProviderRequests 1
    if ($budget.ExitCode -eq 0 -or $budget.Value.status -ne 'FAILED' -or
        $budget.Value.readyForShadow -or
        [string]$budget.Value.failureReason -ne
            'AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_EXHAUSTED' -or
        [int]$budget.Value.tushareProviderCallCount -ne 0 -or
        [int]$budget.Value.finalLedger -ne 449) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_BUDGET_INVALID'
    }

    $partialGap = Scalar @'
BEGIN;
ALTER TABLE tushare_research.adjustment_factor_facts_v1
    DISABLE TRIGGER USER;
DELETE FROM tushare_research.adjustment_factor_facts_v1
 WHERE observation_id = (
     SELECT observation_id
       FROM tushare_research.adjustment_factor_facts_v1
      WHERE factor_effective_trade_date = DATE '2026-08-31'
      ORDER BY observation_id
      LIMIT 1
 );
ALTER TABLE tushare_research.adjustment_factor_facts_v1
    ENABLE TRIGGER USER;
COMMIT;
SELECT (
    (SELECT count(*)
       FROM tushare_research.raw_daily_bar_facts_v2
      WHERE trade_date = DATE '2026-08-31')
    -
    (SELECT count(*)
       FROM tushare_research.adjustment_factor_facts_v1
      WHERE factor_effective_trade_date = DATE '2026-08-31')
)::text;
'@
    if ([int]$partialGap -ne 1) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_PARTIAL_FIXTURE_INVALID'
    }
    $partial = Run-Maintenance -Result $result4 `
        -RequestId (New-TestRequestId 'PARTIAL') -CurrentLedger 6 `
        -MaximumProviderRequests 444
    if ($partial.ExitCode -eq 0 -or
        $partial.Value.status -ne 'FAILED' -or
        $partial.Value.readyForShadow -or
        [string]$partial.Value.failureReason -ne
            'AUTOMATIC_MARKET_DATA_MAINTENANCE_PARTIAL_DATE_PRESENT' -or
        @($partial.Value.initialPartialTradeDates).Count -ne 1 -or
        [string]$partial.Value.initialPartialTradeDates[0] -ne
            '2026-08-31' -or
        @($partial.Value.initialMissingTradeDates).Count -ne 0 -or
        @($partial.Value.dailyResults).Count -ne 0 -or
        [string]$partial.Value.finalAuditRequestId -ne 'NONE' -or
        [int]$partial.Value.tushareProviderCallCount -ne 0 -or
        [int]$partial.Value.finalLedger -ne 6) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_PARTIAL_INVALID'
    }

    if ((Scalar "SELECT max(version::integer)::text FROM flyway_schema_history WHERE success") -ne '18' -or
        [int](Scalar 'SELECT count(*) FROM research_selection_runs') -ne 0 -or
        [int](Scalar 'SELECT count(*) FROM shadow_research_runs') -ne 0 -or
        [int](Scalar 'SELECT count(*) FROM shadow_paper_orders') -ne 0 -or
        [int](Scalar @'
SELECT count(*) FROM (
    SELECT trade_date, symbol, exchange FROM raw_daily_bar_facts_v2
     GROUP BY trade_date, symbol, exchange HAVING count(*) > 1
    UNION ALL
    SELECT factor_effective_trade_date, symbol, factor_type
      FROM adjustment_factor_facts_v1
     GROUP BY factor_effective_trade_date, symbol, factor_type
    HAVING count(*) > 1
) duplicate_rows
'@) -ne 0) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_DATABASE_INVALID'
    }

    $elapsedSeconds = [Math]::Round(
        ([DateTimeOffset]::UtcNow - $e2eStartedAt).TotalSeconds, 3)
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_TOTAL_SECONDS=$elapsedSeconds"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_FIRST_FORWARD_CALLS=$([int]$value.tradeCalendarProviderCallCount)"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_FIRST_PROVIDER_CALLS=$([int]$value.tushareProviderCallCount)"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_FIRST_AUDIT_LATEST_COMPLETE=$([string]$value.initialLatestCompleteTradeDate)"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_FIRST_AUDIT_MISSING=$(@($value.initialMissingTradeDates) -join ',')"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_DAILY_DATES=$(@($value.completedTradeDates) -join ',')"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_FINAL_AUDIT_LATEST_COMPLETE=$([string]$value.finalLatestCompleteTradeDate)"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_FINAL_AUDIT_LATEST_COMMON=$([string]$value.finalLatestCommonCompletedOpenTradeDate)"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_FINAL_AUDIT_MISSING=$(@($value.finalMissingTradeDates).Count)"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_FINAL_AUDIT_PARTIAL=$(@($value.finalPartialTradeDates).Count)"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_FINAL_AUDIT_DUPLICATE=$([int]$value.duplicateCount)"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_SHADOW_READY=$([bool]$value.readyForShadow)"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_SECOND_PROVIDER_CALLS=$([int]$second.Value.tushareProviderCallCount)"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_PROVIDER_CALLS=$([int]$budget.Value.tushareProviderCallCount)"
    Write-Output "AUTOMATIC_MARKET_DATA_MAINTENANCE_PARTIAL_PROVIDER_CALLS=$([int]$partial.Value.tushareProviderCallCount)"
    Write-Output 'AUTOMATIC_MARKET_DATA_MAINTENANCE_FAKE_E2E=PASS'
    Write-Output 'AUTOMATIC_MARKET_DATA_MAINTENANCE_TEMP_POSTGRES_V1_V18=PASS'
    Write-Output 'AUTOMATIC_MARKET_DATA_MAINTENANCE_FORWARD_AUDIT_DAILY_FINAL_AUDIT=PASS'
    Write-Output 'AUTOMATIC_MARKET_DATA_MAINTENANCE_IDEMPOTENT_ZERO_CALL_REPEAT=PASS'
    Write-Output 'AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_FAIL_CLOSED=PASS'
    Write-Output 'AUTOMATIC_MARKET_DATA_MAINTENANCE_PARTIAL_FAIL_CLOSED=PASS'
    Write-Output 'AUTOMATIC_MARKET_DATA_MAINTENANCE_SELECTION_SHADOW_PAPER_CREATED=0'
    Write-Output 'AUTOMATIC_MARKET_DATA_MAINTENANCE_REAL_TUSHARE_CALLS=0'
    Write-Output 'AUTOMATIC_MARKET_DATA_MAINTENANCE_REAL_BAILIAN_CALLS=0'
} finally {
    Pop-Location
    $env:STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_JDBC_URL = $oldUrl
    $env:STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_DB_USER = $oldUser
    $env:STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_DB_PASSWORD = $oldPassword
    $env:STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_GIT_COMMIT = $oldCommit
    if ($started -and (Test-Path -LiteralPath $data)) {
        & "$pgBin\pg_ctl.exe" -D $data -m immediate -w stop `
            2>$null | Out-Null
    }
    foreach ($path in @($result1, $result2, $result3, $result4) +
            @($generatedResults)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $full = [IO.Path]::GetFullPath($path)
            if (-not $full.StartsWith($target + '\',
                    [StringComparison]::OrdinalIgnoreCase)) {
                throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_RESULT_PATH_INVALID'
            }
            Remove-Item -LiteralPath $full -Force
        }
    }
    Remove-TestRoot
    if (Test-Path -LiteralPath $root) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_TEMP_DIRECTORY_REMAINS'
    }
    if ($port -gt 0 -and (Get-NetTCPConnection -LocalPort $port `
            -State Listen -ErrorAction SilentlyContinue)) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_E2E_TEMP_PORT_REMAINS'
    }
    Write-Output 'AUTOMATIC_MARKET_DATA_MAINTENANCE_TEMP_RESOURCE_RESIDUALS=0'
}
