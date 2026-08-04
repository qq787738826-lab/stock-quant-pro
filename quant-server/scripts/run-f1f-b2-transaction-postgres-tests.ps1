$ErrorActionPreference = 'Stop'

$pgBin = 'C:\Program Files\PostgreSQL\16\bin'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$tempBase = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$tempRoot = Join-Path $tempBase "stock-quant-f1f-b2-tx-$suffix"
$dataDir = Join-Path $tempRoot 'data'
$listener = [Net.Sockets.TcpListener]::new(
    [Net.IPAddress]::Loopback,
    0)
$listener.Start()
$port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()
$oldUrl = $env:F1F_B1_POSTGRES_JDBC_URL
$oldUser = $env:F1F_B1_POSTGRES_USER
$started = $false

if (-not (Test-Path -LiteralPath $tempBase)) {
    New-Item -ItemType Directory -Path $tempBase | Out-Null
}

try {
    $resolved = [IO.Path]::GetFullPath($tempRoot)
    if (-not $resolved.StartsWith(
            $tempBase,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'F1F_B2_TRANSACTION_TEMP_PATH_INVALID'
    }
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    & "$pgBin\initdb.exe" -D $dataDir -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'F1F_B2_TRANSACTION_INITDB_FAILED'
    }
    $serverLog = Join-Path $tempRoot 'postgres.log'
    & "$pgBin\pg_ctl.exe" -D $dataDir -l $serverLog `
        -o "-h 127.0.0.1 -p $port" -w start
    if ($LASTEXITCODE -ne 0) {
        throw 'F1F_B2_TRANSACTION_POSTGRES_START_FAILED'
    }
    $started = $true
    & "$pgBin\psql.exe" -h 127.0.0.1 -p $port -U postgres `
        -d postgres -v ON_ERROR_STOP=1 `
        -c 'CREATE ROLE stock_quant_research LOGIN' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'F1F_B2_TRANSACTION_ROLE_CREATE_FAILED'
    }
    & "$pgBin\createdb.exe" -h 127.0.0.1 -p $port -U postgres `
        -O stock_quant_research stock_quant_research
    if ($LASTEXITCODE -ne 0) {
        throw 'F1F_B2_TRANSACTION_DATABASE_CREATE_FAILED'
    }
    $env:F1F_B1_POSTGRES_JDBC_URL =
        "jdbc:postgresql://127.0.0.1:$port/" `
        + 'stock_quant_research?currentSchema=tushare_research'
    $env:F1F_B1_POSTGRES_USER = 'stock_quant_research'
    $testSpec = 'TushareControlledAcceptancePostgresTest#' `
        + 'manuallyAssembledRunnerPathStartsDedicatedCaptureTransaction' `
        + '+wrongTransactionManagerIsRejectedBeforePersistence' `
        + '+thirdTypedFactFailureRollsBackManualCapture'
    & "$PSScriptRoot\..\..\mvnw.cmd" -pl quant-server -am `
        "-Dtest=$testSpec" `
        '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw 'F1F_B2_TRANSACTION_POSTGRES_TEST_FAILED'
    }
    Write-Output 'F1F_B2_TRANSACTION_POSTGRES_TESTS=PASS'
    Write-Output "F1F_B2_TRANSACTION_POSTGRES_PORT=$port"
} finally {
    $env:F1F_B1_POSTGRES_JDBC_URL = $oldUrl
    $env:F1F_B1_POSTGRES_USER = $oldUser
    if ($started -and (Test-Path -LiteralPath $dataDir)) {
        & "$pgBin\pg_ctl.exe" -D $dataDir -m immediate -w stop `
            2>$null | Out-Null
    }
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
    if (Test-Path -LiteralPath $tempRoot) {
        throw 'F1F_B2_TRANSACTION_TEMP_DIRECTORY_REMAINS'
    }
    $listenerRemaining = Get-NetTCPConnection `
        -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($listenerRemaining) {
        throw 'F1F_B2_TRANSACTION_TEMP_PORT_REMAINS'
    }
    Write-Output 'F1F_B2_TRANSACTION_TEMP_RESIDUALS=0'
}
