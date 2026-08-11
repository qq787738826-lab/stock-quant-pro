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
$target = Join-Path $repoRoot 'quant-server\target'
$prefix = 'stock-quant-m1-e2e-'
$root = Join-Path $target ($prefix + [Guid]::NewGuid().ToString('N'))
$data = Join-Path $root 'data'
$log = Join-Path $root 'postgres.log'
$artifact = Join-Path $target `
    'quant-server-1.3.1-m1-research-data-runner.jar'
$proof = "$artifact.f1f-b2-proof.properties"
$runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM1ResearchDataManualRunner'
$port = 0
$started = $false

function Scalar([string] $Sql) {
    $value = & "$pgBin\psql.exe" -X -q -A -t -h 127.0.0.1 `
        -p $port -U stock_quant_research -d stock_quant_research `
        -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) { throw 'TUSHARE_M1_E2E_QUERY_FAILED' }
    return ($value | Select-Object -Last 1).Trim()
}

function Exact([object] $Actual, [object] $Expected, [string] $Code) {
    if ([string]$Actual -ne [string]$Expected) { throw $Code }
}

function Write-Authorization(
    [string] $Path, [string] $RunId, [string] $Mode,
    [string] $Start, [string] $End, [string] $Anchor,
    [int] $CallsBefore, [string] $Hash
) {
    $issued = [DateTimeOffset]::UtcNow.AddSeconds(-2)
    $expires = $issued.AddMinutes(20)
    $lines = @(
        'authorization.status=E2E_DRY_RUN'
        'authorization.version=M1_RESEARCH_DATA_AUTHORIZATION_V1'
        "run.id=$RunId"
        "git.commit=$ExpectedCommit"
        "artifact.sha256=$Hash"
        "build.proof.path=$proof"
        'provider=TUSHARE'
        'securities=600000:SSE,000001:SZSE'
        "range.start=$Start"
        "range.end=$End"
        "anchor.trade.date=$Anchor"
        "mode=$Mode"
        'endpoints=daily,adj_factor,trade_cal'
        'endpoint.daily.requests=2'
        'endpoint.adj_factor.requests=2'
        'endpoint.trade_cal.requests=2'
        'maximum.provider.requests=6'
        'retry.budget=0'
        'redirects=NEVER'
        'provider.historical.baseline=34'
        'provider.stage.limit=30'
        'provider.cumulative.limit=64'
        "provider.stage.calls.before=$CallsBefore"
        'database.host=127.0.0.1'
        "database.port=$port"
        'database.name=stock_quant_research'
        'database.user=stock_quant_research'
        'database.ssl.mode=DISABLE_LOCAL_ONLY'
        'schema.name=tushare_research'
        "issued.at=$($issued.ToString('o'))"
        "expires.at=$($expires.ToString('o'))"
        'purpose=M1_RESEARCH_DATA_READY'
        'execution.source=M1_RESEARCH_DATA_MANUAL'
        'user.approval.reference=NOT_APPLICABLE_E2E_DRY_RUN'
    )
    [IO.File]::WriteAllText($Path, ($lines -join "`n") + "`n",
        [Text.UTF8Encoding]::new($false))
}

function Run(
    [string] $Authorization, [string] $Result,
    [int] $FailAt = -1
) {
    $old = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & java "-Dstockquant.m1.e2e.fail-at-call=$FailAt" `
            "-Dloader.main=$runner" -cp $artifact `
            'org.springframework.boot.loader.launch.PropertiesLauncher' `
            "--authorization-file=$Authorization" "--result-file=$Result" `
            2>&1 | ForEach-Object { [string]$_ } | Out-Host
        return $LASTEXITCODE
    } finally { $ErrorActionPreference = $old }
}

function Assert-Success(
    [string] $Result, [string] $Mode,
    [int] $Received, [int] $Appended, [int] $Idempotent,
    [int] $Raw, [int] $Calendar, [int] $Closed
) {
    $value = Get-Content -LiteralPath $Result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    Exact $value.status 'SUCCEEDED' 'TUSHARE_M1_E2E_STATUS_INVALID'
    Exact $value.mode $Mode 'TUSHARE_M1_E2E_MODE_INVALID'
    Exact $value.providerCallCount 6 'TUSHARE_M1_E2E_CALLS_INVALID'
    Exact $value.retryCount 0 'TUSHARE_M1_E2E_RETRY_INVALID'
    Exact $value.receivedFactCount $Received 'TUSHARE_M1_E2E_FACTS_INVALID'
    Exact $value.newObservationCount $Appended 'TUSHARE_M1_E2E_APPEND_INVALID'
    Exact $value.idempotentChainTailCount $Idempotent `
        'TUSHARE_M1_E2E_IDEMPOTENT_INVALID'
    Exact @($value.captureBatchIds).Count 2 `
        'TUSHARE_M1_E2E_BATCH_COUNT_INVALID'
    Exact $value.researchDataset.rawDailyCount $Raw `
        'TUSHARE_M1_E2E_RAW_INVALID'
    Exact $value.researchDataset.adjustmentFactorCount $Raw `
        'TUSHARE_M1_E2E_FACTOR_INVALID'
    Exact $value.researchDataset.calendarCount $Calendar `
        'TUSHARE_M1_E2E_CALENDAR_INVALID'
    Exact $value.researchDataset.closedDateCount $Closed `
        'TUSHARE_M1_E2E_CLOSED_INVALID'
    Exact $value.researchDataset.qfqBarCount $Raw `
        'TUSHARE_M1_E2E_QFQ_INVALID'
    if (-not $value.researchDataset.typedFactReadback -or
        -not $value.researchDataset.systemKnowledgeReadback -or
        -not $value.researchDataset.formulaOnlyQfq -or
        $value.researchDataset.fullQfqLineageClaimed -or
        -not $value.researchDataset.dataQuality -or
        -not $value.researchDataset.noFutureDataLeakage -or
        -not $value.researchDataset.m2Readable -or
        -not $value.outputAudit.clean -or $value.providerAutostart) {
        throw 'TUSHARE_M1_E2E_DATASET_INVALID'
    }
}

function Remove-Root {
    if (-not (Test-Path -LiteralPath $root)) { return }
    $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne
            $target.TrimEnd('\', '/') -or
        -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
        throw 'TUSHARE_M1_E2E_CLEANUP_PATH_INVALID'
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

Push-Location $repoRoot
try {
    if ((git rev-parse HEAD).Trim() -ne $ExpectedCommit -or
        @(git status --porcelain=v1 --untracked-files=normal |
            Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' }).Count `
            -ne 0 -or @(git diff --cached --name-only).Count -ne 0) {
        throw 'TUSHARE_M1_E2E_GIT_INVALID'
    }
    & "$PSScriptRoot\prepare-m1-research-data-build-proof.ps1" `
        -ExpectedCommit $ExpectedCommit -Mode E2E_DRY_RUN
    if ($LASTEXITCODE -ne 0) { throw 'TUSHARE_M1_E2E_BUILD_FAILED' }
    $hash = ((Get-FileHash -LiteralPath $artifact `
        -Algorithm SHA256).Hash).ToLowerInvariant()
    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    if ($port -eq 38432) { throw 'TUSHARE_M1_E2E_PORT_INVALID' }
    New-Item -ItemType Directory -Path $root | Out-Null
    & "$pgBin\initdb.exe" -D $data -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'TUSHARE_M1_E2E_INITDB_FAILED' }
    $args = '-D "{0}" -l "{1}" -o "-h 127.0.0.1 -p {2}" -w start' `
        -f $data, $log, $port
    $process = Start-Process "$pgBin\pg_ctl.exe" -ArgumentList $args `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru
    try { $process.WaitForExit(); if ($process.ExitCode -ne 0) {
        throw 'TUSHARE_M1_E2E_POSTGRES_START_FAILED' } } finally {
        $process.Dispose() }
    $started = $true
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port -U postgres `
        -d postgres -v ON_ERROR_STOP=1 -c `
        'CREATE ROLE stock_quant_research LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS' | Out-Null
    & "$pgBin\createdb.exe" -h 127.0.0.1 -p $port -U postgres `
        -O stock_quant_research stock_quant_research
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port -U stock_quant_research `
        -d stock_quant_research -v ON_ERROR_STOP=1 -c `
        'CREATE SCHEMA tushare_research AUTHORIZATION stock_quant_research' | Out-Null
    & "$pgBin\psql.exe" -X -q -h 127.0.0.1 -p $port -U postgres `
        -d stock_quant_research -v ON_ERROR_STOP=1 -c `
        'REVOKE CREATE ON SCHEMA public FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM stock_quant_research; ALTER ROLE stock_quant_research IN DATABASE stock_quant_research SET search_path TO tushare_research' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'TUSHARE_M1_E2E_DATABASE_SETUP_FAILED' }

    $auth1 = Join-Path $root 'capture-1.properties'
    $result1 = Join-Path $root 'capture-1.json'
    Write-Authorization $auth1 'M1_E2E_CAPTURE_0001' 'CAPTURE' `
        '2025-01-02' '2025-01-06' '2025-01-06' 0 $hash
    Exact (Run $auth1 $result1) 0 'TUSHARE_M1_E2E_CAPTURE_1_FAILED'
    Assert-Success $result1 'CAPTURE' 22 22 0 6 10 4

    $auth2 = Join-Path $root 'capture-2.properties'
    $result2 = Join-Path $root 'capture-2.json'
    Write-Authorization $auth2 'M1_E2E_CAPTURE_0002' 'CAPTURE' `
        '2025-01-07' '2025-01-10' '2025-01-10' 6 $hash
    Exact (Run $auth2 $result2) 0 'TUSHARE_M1_E2E_CAPTURE_2_FAILED'
    Assert-Success $result2 'CAPTURE' 24 24 0 8 8 0

    $auth3 = Join-Path $root 'idempotent.properties'
    $result3 = Join-Path $root 'idempotent.json'
    Write-Authorization $auth3 'M1_E2E_IDEMPOTENT_0003' `
        'IDEMPOTENCY_VERIFICATION' '2025-01-02' '2025-01-10' `
        '2025-01-10' 12 $hash
    Exact (Run $auth3 $result3) 0 'TUSHARE_M1_E2E_IDEMPOTENT_FAILED'
    Assert-Success $result3 'IDEMPOTENCY_VERIFICATION' 46 0 46 14 18 4
    Exact (Scalar "SELECT count(*) FROM tushare_research.pit_market_fact_batches") `
        6 'TUSHARE_M1_E2E_BATCHES_INVALID'
    Exact (Scalar "SELECT count(*) FROM tushare_research.pit_market_fact_observations") `
        46 'TUSHARE_M1_E2E_OBSERVATIONS_INVALID'

    $before = Scalar `
        "SELECT count(*) FROM tushare_research.pit_market_fact_batches"
    $failAuth = Join-Path $root 'failure.properties'
    $failResult = Join-Path $root 'failure.json'
    Write-Authorization $failAuth 'M1_E2E_FAILURE_0004' 'CAPTURE' `
        '2025-01-13' '2025-01-14' '2025-01-14' 18 $hash
    Exact (Run $failAuth $failResult 6) 20 `
        'TUSHARE_M1_E2E_FAILURE_EXIT_INVALID'
    $failed = Get-Content -LiteralPath $failResult -Raw -Encoding UTF8 |
        ConvertFrom-Json
    Exact $failed.providerCallCount 6 'TUSHARE_M1_E2E_FAILURE_CALLS_INVALID'
    Exact $failed.retryCount 0 'TUSHARE_M1_E2E_FAILURE_RETRY_INVALID'
    Exact (Scalar "SELECT count(*) FROM tushare_research.pit_market_fact_batches") `
        $before 'TUSHARE_M1_E2E_FAILURE_PERSISTED'
    Write-Output 'TUSHARE_M1_PACKAGED_FAKE_E2E=PASS'
    Write-Output 'TUSHARE_M1_FAKE_PROVIDER_CALLS=24'
    Write-Output 'TUSHARE_M1_TEMP_POSTGRES=PASS'
    Write-Output 'TUSHARE_M1_REAL_PROVIDER_CALLS=0'
} finally {
    Pop-Location
    if (($started -or (Test-Path (Join-Path $data 'postmaster.pid'))) -and
        (Test-Path -LiteralPath $data)) {
        & "$pgBin\pg_ctl.exe" -D $data -m immediate -w stop `
            2>$null | Out-Null
    }
    Remove-Root
    foreach ($file in @($artifact, $proof, "$artifact.original")) {
        if (Test-Path -LiteralPath $file) {
            Remove-Item -LiteralPath $file -Force
        }
    }
    if ($port -gt 0 -and (Get-NetTCPConnection -LocalPort $port `
            -State Listen -ErrorAction SilentlyContinue)) {
        throw 'TUSHARE_M1_E2E_PORT_REMAINS'
    }
    if (Test-Path -LiteralPath $root) {
        throw 'TUSHARE_M1_E2E_TEMP_REMAINS'
    }
    Write-Output 'TUSHARE_M1_E2E_RESIDUALS=0'
}
