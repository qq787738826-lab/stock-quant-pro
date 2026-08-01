$ErrorActionPreference = 'Stop'
$pgBin = 'C:\Program Files\PostgreSQL\16\bin'
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$tempRoot = Join-Path $env:TEMP "stock-quant-f1f-b1-$suffix"
$dataDir = Join-Path $tempRoot 'data'
$port = Get-Random -Minimum 20000 -Maximum 45000
$oldUrl = $env:F1F_B1_POSTGRES_JDBC_URL
$oldUser = $env:F1F_B1_POSTGRES_USER
try {
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    & "$pgBin\initdb.exe" -D $dataDir -A trust -U postgres --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'F1F_B1_INITDB_FAILED' }
    $serverLog = Join-Path $tempRoot 'postgres.log'
    & "$pgBin\pg_ctl.exe" -D $dataDir -l $serverLog -o "-h 127.0.0.1 -p $port" -w start
    if ($LASTEXITCODE -ne 0) { throw 'F1F_B1_POSTGRES_START_FAILED' }
    & "$pgBin\psql.exe" -h 127.0.0.1 -p $port -U postgres -d postgres -v ON_ERROR_STOP=1 -c 'CREATE ROLE stock_quant_research LOGIN' | Out-Null
    & "$pgBin\createdb.exe" -h 127.0.0.1 -p $port -U postgres -O stock_quant_research stock_quant_research
    $env:F1F_B1_POSTGRES_JDBC_URL = "jdbc:postgresql://127.0.0.1:$port/stock_quant_research?currentSchema=tushare_research"
    $env:F1F_B1_POSTGRES_USER = 'stock_quant_research'
    mvn -pl quant-server -am '-Dtest=TushareControlledAcceptancePostgresTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw 'F1F_B1_POSTGRES_TEST_FAILED' }
} finally {
    $env:F1F_B1_POSTGRES_JDBC_URL = $oldUrl
    $env:F1F_B1_POSTGRES_USER = $oldUser
    if (Test-Path $dataDir) {
        & "$pgBin\pg_ctl.exe" -D $dataDir -m immediate -w stop 2>$null | Out-Null
    }
    if (Test-Path $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
    if (Test-Path $tempRoot) { throw 'F1F_B1_TEMP_DIRECTORY_REMAINS' }
}
