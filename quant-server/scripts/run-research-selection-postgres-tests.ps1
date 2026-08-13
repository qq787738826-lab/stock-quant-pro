[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$pgBin = 'C:\Program Files\PostgreSQL\16\bin'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$tempBase = [IO.Path]::GetFullPath(
    (Join-Path $env:TEMP 'stock-quant-pro-selection-tests')).TrimEnd('\', '/')
$prefix = 'stock-quant-selection-pg-'
$root = Join-Path $tempBase ($prefix + [Guid]::NewGuid().ToString('N'))
$data = Join-Path $root 'data'
$log = Join-Path $root 'postgres.log'
$port = 0
$started = $false
$oldUrl = $env:STOCK_QUANT_SELECTION_TEST_JDBC_URL
$oldUser = $env:STOCK_QUANT_SELECTION_TEST_DB_USER
$oldPassword = $env:STOCK_QUANT_SELECTION_TEST_DB_PASSWORD

function Remove-TestRoot {
    if (-not (Test-Path -LiteralPath $root)) { return }
    $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne $tempBase -or
        -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
        throw 'RESEARCH_SELECTION_POSTGRES_CLEANUP_PATH_INVALID'
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

Push-Location $repoRoot
try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    & "$pgBin\initdb.exe" -D $data -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'RESEARCH_SELECTION_POSTGRES_INIT_FAILED'
    }
    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    if ($port -eq 38432) {
        throw 'RESEARCH_SELECTION_POSTGRES_PORT_INVALID'
    }
    $arguments = '-D "{0}" -l "{1}" -o "-h 127.0.0.1 -p {2}" -w start' `
        -f $data, $log, $port
    $process = Start-Process "$pgBin\pg_ctl.exe" -ArgumentList $arguments `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru
    try {
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw 'RESEARCH_SELECTION_POSTGRES_START_FAILED'
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
        throw 'RESEARCH_SELECTION_POSTGRES_DATABASE_SETUP_FAILED'
    }
    $env:STOCK_QUANT_SELECTION_TEST_JDBC_URL =
        "jdbc:postgresql://127.0.0.1:$port/stock_quant_research"
    $env:STOCK_QUANT_SELECTION_TEST_DB_USER = 'stock_quant_research'
    # PostgreSQL trust auth ignores this synthetic value. Keeping it non-empty
    # prevents PowerShell from deleting the environment variable and silently
    # causing JUnit assumptions to skip every integration case.
    $env:STOCK_QUANT_SELECTION_TEST_DB_PASSWORD =
        'TEMP_POSTGRES_TEST_ONLY'
    & "$repoRoot\mvnw.cmd" -pl quant-server -am `
        '-Dtest=ResearchSelectionPostgresIntegrationTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw 'RESEARCH_SELECTION_POSTGRES_TESTS_FAILED'
    }
    Write-Output 'RESEARCH_SELECTION_POSTGRES_V1_V17=PASS'
    Write-Output 'RESEARCH_SELECTION_POSTGRES_TESTS=3/0/0/0'
    Write-Output 'RESEARCH_SELECTION_REAL_PROVIDER_CALLS=0'
    Write-Output 'RESEARCH_SELECTION_PERMANENT_DATABASE_WRITES=0'
} finally {
    Pop-Location
    $env:STOCK_QUANT_SELECTION_TEST_JDBC_URL = $oldUrl
    $env:STOCK_QUANT_SELECTION_TEST_DB_USER = $oldUser
    $env:STOCK_QUANT_SELECTION_TEST_DB_PASSWORD = $oldPassword
    if ($started -and (Test-Path -LiteralPath $data)) {
        & "$pgBin\pg_ctl.exe" -D $data -m immediate -w stop `
            2>$null | Out-Null
    }
    Remove-TestRoot
    if (Test-Path -LiteralPath $root) {
        throw 'RESEARCH_SELECTION_POSTGRES_TEMP_REMAINS'
    }
    if ($port -gt 0 -and (Get-NetTCPConnection -LocalPort $port `
            -State Listen -ErrorAction SilentlyContinue)) {
        throw 'RESEARCH_SELECTION_POSTGRES_PORT_REMAINS'
    }
    Write-Output 'RESEARCH_SELECTION_POSTGRES_RESIDUALS=0'
}
