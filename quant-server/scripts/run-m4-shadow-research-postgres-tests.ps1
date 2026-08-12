[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$pgBin = 'C:\Program Files\PostgreSQL\16\bin'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$tempBase = [IO.Path]::GetFullPath(
    (Join-Path $env:TEMP 'stock-quant-pro-m4-tests')).TrimEnd('\', '/')
$prefix = 'stock-quant-m4-postgres-'
$root = Join-Path $tempBase ($prefix + [Guid]::NewGuid().ToString('N'))
$data = Join-Path $root 'data'
$log = Join-Path $root 'postgres.log'
$port = 0
$started = $false
$oldUrl = $env:STOCK_QUANT_M4_TEST_JDBC_URL
$oldUser = $env:STOCK_QUANT_M4_TEST_DB_USER
$oldPassword = $env:STOCK_QUANT_M4_TEST_DB_PASSWORD

try {
    New-Item -ItemType Directory -Path $tempBase -Force | Out-Null
    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    if ($port -eq 38432) { throw 'M4_POSTGRES_PORT_INVALID' }
    New-Item -ItemType Directory -Path $root | Out-Null
    & "$pgBin\initdb.exe" -D $data -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'M4_POSTGRES_INITDB_FAILED' }
    $arguments = '-D "{0}" -l "{1}" -o "-h 127.0.0.1 -p {2}" -w start' `
        -f $data, $log, $port
    $process = Start-Process "$pgBin\pg_ctl.exe" -ArgumentList $arguments `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru
    try {
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'M4_POSTGRES_START_FAILED' }
    } finally { $process.Dispose() }
    $started = $true
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port -U postgres `
        -d postgres -v ON_ERROR_STOP=1 `
        -c 'CREATE ROLE stock_quant_research LOGIN' | Out-Null
    & "$pgBin\createdb.exe" -h 127.0.0.1 -p $port -U postgres `
        -O stock_quant_research stock_quant_research
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port `
        -U stock_quant_research -d stock_quant_research `
        -v ON_ERROR_STOP=1 -c `
        'CREATE SCHEMA tushare_research AUTHORIZATION stock_quant_research' |
        Out-Null
    $env:STOCK_QUANT_M4_TEST_JDBC_URL =
        "jdbc:postgresql://127.0.0.1:$port/stock_quant_research?currentSchema=tushare_research"
    $env:STOCK_QUANT_M4_TEST_DB_USER = 'stock_quant_research'
    $env:STOCK_QUANT_M4_TEST_DB_PASSWORD = 'M4_TEMP_TEST_ONLY'
    # The resident broker heartbeat intentionally lives below target and can
    # keep that directory open. Remove only stale compiled output so Flyway
    # cannot see a migration deleted or renamed in source.
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
    if ($LASTEXITCODE -ne 0) { throw 'M4_POSTGRES_FRESH_COMPILE_FAILED' }
    & "$repoRoot\mvnw.cmd" -pl quant-server -am `
        '-Dtest=ShadowResearchRuntimePostgresTest,ShadowResearchTemporalTest,ShadowResearchSchedulerTest,PaperExecutionEngineTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw 'M4_POSTGRES_TEST_FAILED' }
    Write-Output 'M4_POSTGRES_INTEGRATION=PASS'
    Write-Output 'M4_POSTGRES_ACTIVE_RESIDUALS=0'
} finally {
    $env:STOCK_QUANT_M4_TEST_JDBC_URL = $oldUrl
    $env:STOCK_QUANT_M4_TEST_DB_USER = $oldUser
    $env:STOCK_QUANT_M4_TEST_DB_PASSWORD = $oldPassword
    if ($started -and (Test-Path -LiteralPath $data)) {
        & "$pgBin\pg_ctl.exe" -D $data -m immediate -w stop `
            2>$null | Out-Null
    }
    if (Test-Path -LiteralPath $root) {
        $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
        if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne $tempBase -or
            -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
            throw 'M4_POSTGRES_CLEANUP_INVALID'
        }
        Remove-Item -LiteralPath $full -Recurse -Force
    }
    if (Test-Path -LiteralPath $root) {
        throw 'M4_POSTGRES_TEMP_DIRECTORY_REMAINS'
    }
    Write-Output 'M4_POSTGRES_TEMP_RESIDUALS=0'
}
