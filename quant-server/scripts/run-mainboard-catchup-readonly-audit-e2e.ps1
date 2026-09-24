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
$tempBase = [IO.Path]::GetFullPath((Join-Path $env:TEMP `
    'stock-quant-pro-catchup-audit-tests')).TrimEnd('\', '/')
$prefix = 'stock-quant-catchup-audit-e2e-'
$root = Join-Path $tempBase ($prefix + [Guid]::NewGuid().ToString('N'))
$data = Join-Path $root 'data'
$log = Join-Path $root 'postgres.log'
$beforeDump = Join-Path $root 'before.sql'
$afterDump = Join-Path $root 'after.sql'
$artifact = Join-Path $target `
    'quant-server-1.3.1-mainboard-catchup-readonly-audit-runner.jar'
$result = Join-Path $target `
    ('catchup-audit-e2e-' + [Guid]::NewGuid().ToString('N') + '.json')
$port = 0
$started = $false
$oldUrl = $env:STOCK_QUANT_CATCHUP_AUDIT_TEST_JDBC_URL
$oldUser = $env:STOCK_QUANT_CATCHUP_AUDIT_TEST_DB_USER
$oldPassword = $env:STOCK_QUANT_CATCHUP_AUDIT_TEST_DB_PASSWORD

function Remove-TestRoot {
    if (-not (Test-Path -LiteralPath $root)) { return }
    $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne
            $tempBase -or
        -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_CLEANUP_PATH_INVALID'
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

function Dump-Database([string] $Path) {
    & "$pgBin\pg_dump.exe" -h 127.0.0.1 -p $port `
        -U stock_quant_research -d stock_quant_research `
        --data-only --no-owner --no-privileges `
        --restrict-key=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef `
        --schema=tushare_research --file=$Path
    if ($LASTEXITCODE -ne 0 -or
        -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_DUMP_FAILED'
    }
}

Push-Location $repoRoot
try {
    New-Item -ItemType Directory -Path $tempBase -Force | Out-Null
    $unexpected = @(git status --porcelain=v1 --untracked-files=normal |
        Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' })
    if ((git rev-parse HEAD).Trim() -ne $ExpectedCommit -or
        $unexpected.Count -ne 0 -or
        @(git diff --cached --name-only).Count -ne 0) {
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_GIT_INVALID'
    }
    & "$PSScriptRoot\prepare-mainboard-catchup-readonly-audit-build-proof.ps1" `
        -ExpectedCommit $ExpectedCommit -Mode E2E_DRY_RUN
    if ($LASTEXITCODE -ne 0 -or
        -not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath `
            "$artifact.f1f-b2-proof.properties" -PathType Leaf)) {
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_BUILD_FAILED'
    }

    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    if ($port -eq 38432) {
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_PORT_INVALID'
    }
    New-Item -ItemType Directory -Path $root | Out-Null
    & "$pgBin\initdb.exe" -D $data -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_INITDB_FAILED'
    }
    $arguments = '-D "{0}" -l "{1}" -o "-h 127.0.0.1 -p {2}" -w start' `
        -f $data, $log, $port
    $process = Start-Process "$pgBin\pg_ctl.exe" -ArgumentList $arguments `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru
    try {
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw 'MAINBOARD_CATCHUP_AUDIT_E2E_POSTGRES_START_FAILED'
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
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_DATABASE_SETUP_FAILED'
    }

    $env:STOCK_QUANT_CATCHUP_AUDIT_TEST_JDBC_URL =
        "jdbc:postgresql://127.0.0.1:$port/stock_quant_research"
    $env:STOCK_QUANT_CATCHUP_AUDIT_TEST_DB_USER = 'stock_quant_research'
    $env:STOCK_QUANT_CATCHUP_AUDIT_TEST_DB_PASSWORD =
        'TEMP_POSTGRES_TEST_ONLY'
    & .\mvnw.cmd -o -pl quant-server -am `
        '-Dtest=MainboardCatchupReadonlyAuditPostgresTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_POSTGRES_TEST_FAILED'
    }

    Dump-Database $beforeDump
    $runnerOutput = @(& `
        "$PSScriptRoot\run-mainboard-catchup-readonly-audit.ps1" `
        -ResultFile $result -ArtifactPath $artifact `
        -ExecutionId 'MBAUDIT_20260921T120000Z_A1B2C3D4E5F6' `
        -GitCommit $ExpectedCommit -DatabasePort $port `
        -CurrentLedger 377 -LedgerLimit 450 -ExecutionMode FAKE 2>&1 |
        ForEach-Object { [string]$_ })
    $runnerExitCode = $LASTEXITCODE
    if ($runnerExitCode -ne 0) {
        $runnerOutput | Write-Output
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_RUNNER_FAILED'
    }
    $audit = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($audit.status -ne 'SUCCEEDED' -or
        [string]$audit.calendarMaxSse -ne '2026-09-21' -or
        [string]$audit.calendarMaxSzse -ne '2026-09-21' -or
        [string]$audit.latestCommonCompletedOpenTradeDate -ne
            '2026-09-21' -or
        [string]$audit.latestCompleteTradeDate -ne '2026-08-27' -or
        @($audit.commonOpenTradeDates).Count -ne 17 -or
        [int]$audit.missingTradeDateCount -ne 14 -or
        [int]$audit.partialTradeDateCount -ne 2 -or
        [int]$audit.completeTradeDateCount -ne 1 -or
        @($audit.partialTradeDates) -notcontains '2026-09-01' -or
        @($audit.partialTradeDates) -notcontains '2026-09-02' -or
        @($audit.completeTradeDates) -notcontains '2026-08-31' -or
        [int]$audit.plannedBaseCalls -ne 28 -or
        [int]$audit.networkRecoveryBudget -ne 0 -or
        [int]$audit.currentLedger -ne 377 -or
        [int]$audit.projectedWorstCaseLedger -ne 405 -or
        -not $audit.projectedWithinLimit -or
        [int]$audit.tushareProviderCallCount -ne 0 -or
        [int]$audit.modelCallCount -ne 0 -or
        [long]$audit.databaseRowsWritten -ne 0 -or
        -not $audit.readOnlyTransaction -or
        -not $audit.outputAuditClean -or -not $audit.deterministicFake -or
        -not $audit.dataOnly -or $audit.realTradingStarted) {
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_RESULT_INVALID'
    }
    Dump-Database $afterDump
    if ((Get-FileHash -LiteralPath $beforeDump -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $afterDump -Algorithm SHA256).Hash) {
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_DATABASE_MUTATED'
    }

    Write-Output 'MAINBOARD_CATCHUP_AUDIT_PACKAGED_FAKE_E2E=PASS'
    Write-Output 'MAINBOARD_CATCHUP_AUDIT_TEMP_POSTGRES_V1_V18=PASS'
    Write-Output 'MAINBOARD_CATCHUP_AUDIT_COMPLETE_PARTIAL_MISSING=PASS'
    Write-Output 'MAINBOARD_CATCHUP_AUDIT_READ_ONLY=PASS'
    Write-Output 'MAINBOARD_CATCHUP_AUDIT_BUILD_PROOF=PASS'
    Write-Output 'MAINBOARD_CATCHUP_AUDIT_REAL_TUSHARE_CALLS=0'
    Write-Output 'MAINBOARD_CATCHUP_AUDIT_REAL_BAILIAN_CALLS=0'
} finally {
    Pop-Location
    $env:STOCK_QUANT_CATCHUP_AUDIT_TEST_JDBC_URL = $oldUrl
    $env:STOCK_QUANT_CATCHUP_AUDIT_TEST_DB_USER = $oldUser
    $env:STOCK_QUANT_CATCHUP_AUDIT_TEST_DB_PASSWORD = $oldPassword
    if ($started -and (Test-Path -LiteralPath $data)) {
        & "$pgBin\pg_ctl.exe" -D $data -m immediate -w stop `
            2>$null | Out-Null
    }
    if (Test-Path -LiteralPath $result -PathType Leaf) {
        Remove-Item -LiteralPath $result -Force
    }
    Remove-TestRoot
    if (Test-Path -LiteralPath $root) {
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_TEMP_DIRECTORY_REMAINS'
    }
    if ($port -gt 0 -and (Get-NetTCPConnection -LocalPort $port `
            -State Listen -ErrorAction SilentlyContinue)) {
        throw 'MAINBOARD_CATCHUP_AUDIT_E2E_TEMP_PORT_REMAINS'
    }
    Write-Output 'MAINBOARD_CATCHUP_AUDIT_TEMP_RESOURCE_RESIDUALS=0'
}
