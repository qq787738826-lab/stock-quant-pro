$ErrorActionPreference = 'Stop'
$pgBin = 'C:\Program Files\PostgreSQL\16\bin'
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$tempRoot = Join-Path $env:TEMP "stock-quant-f1f-b2-dbprep-$suffix"
$dataDir = Join-Path $tempRoot 'data'
$port = Get-Random -Minimum 20000 -Maximum 45000
$oldPort = $env:F1F_B2_DBPREP_POSTGRES_PORT
try {
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    & "$pgBin\initdb.exe" -D $dataDir -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'F1F_B2_DBPREP_INITDB_FAILED' }
    $serverLog = Join-Path $tempRoot 'postgres.log'
    & "$pgBin\pg_ctl.exe" -D $dataDir -l $serverLog `
        -o "-h 127.0.0.1 -p $port" -w start
    if ($LASTEXITCODE -ne 0) { throw 'F1F_B2_DBPREP_POSTGRES_START_FAILED' }
    $env:F1F_B2_DBPREP_POSTGRES_PORT = $port.ToString()
    & .\mvnw.cmd -pl quant-server -am `
        '-Dtest=TushareControlledAcceptanceDatabasePreparationPostgresTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw 'F1F_B2_DBPREP_POSTGRES_TEST_FAILED' }
} finally {
    $env:F1F_B2_DBPREP_POSTGRES_PORT = $oldPort
    if (Test-Path -LiteralPath $dataDir) {
        & "$pgBin\pg_ctl.exe" -D $dataDir -m immediate -w stop `
            2>$null | Out-Null
    }
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
    if (Test-Path -LiteralPath $tempRoot) {
        throw 'F1F_B2_DBPREP_TEMP_DIRECTORY_REMAINS'
    }
}
