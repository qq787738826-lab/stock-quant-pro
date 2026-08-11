[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedCommit,

    [switch] $IncludeM3
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$pgBin = 'C:\Program Files\PostgreSQL\16\bin'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = Join-Path $repoRoot 'quant-server\target'
$prefix = 'stock-quant-m2-e2e-'
$root = Join-Path $target ($prefix + [Guid]::NewGuid().ToString('N'))
$data = Join-Path $root 'data'
$log = Join-Path $root 'postgres.log'
$m1Artifact = Join-Path $target `
    'quant-server-1.3.1-m1-research-data-runner.jar'
$m1Proof = "$m1Artifact.f1f-b2-proof.properties"
$m2Artifact = Join-Path $target `
    'quant-server-1.3.1-m2-strategy-research-runner.jar'
$m2Proof = "$m2Artifact.f1f-b2-proof.properties"
$m3Artifact = Join-Path $target `
    'quant-server-1.3.1-m3-agent-research-runner.jar'
$m3Proof = "$m3Artifact.f1f-b2-proof.properties"
$m1Runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM1ResearchDataManualRunner'
$m2Runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM2StrategyResearchManualRunner'
$m3Runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM3AgentResearchManualRunner'
$port = 0
$started = $false

function Exact([object] $Actual, [object] $Expected, [string] $Code) {
    if ([string]$Actual -ne [string]$Expected) { throw $Code }
}

function Scalar([string] $Sql) {
    $value = & "$pgBin\psql.exe" -X -q -A -t -h 127.0.0.1 `
        -p $port -U stock_quant_research -d stock_quant_research `
        -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) { throw 'M2_E2E_QUERY_FAILED' }
    return ($value | Select-Object -Last 1).Trim()
}

function Write-M1Authorization(
    [string] $Path,
    [string] $RunId,
    [string] $Mode,
    [string] $Start,
    [string] $End,
    [string] $Anchor,
    [int] $CallsBefore,
    [string] $Hash
) {
    $issued = [DateTimeOffset]::UtcNow.AddSeconds(-2)
    $expires = $issued.AddMinutes(20)
    $lines = @(
        'authorization.status=E2E_DRY_RUN'
        'authorization.version=M1_RESEARCH_DATA_AUTHORIZATION_V1'
        "run.id=$RunId"
        "git.commit=$ExpectedCommit"
        "artifact.sha256=$Hash"
        "build.proof.path=$m1Proof"
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

function Run-M1([string] $Authorization, [string] $Result) {
    $old = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & java '-Dstockquant.m1.e2e.fail-at-call=-1' `
            "-Dloader.main=$m1Runner" -cp $m1Artifact `
            'org.springframework.boot.loader.launch.PropertiesLauncher' `
            "--authorization-file=$Authorization" "--result-file=$Result" `
            2>&1 | ForEach-Object { [string]$_ } | Out-Host
        return $LASTEXITCODE
    } finally { $ErrorActionPreference = $old }
}

function Assert-M1Success(
    [string] $Result,
    [int] $Received,
    [int] $Appended,
    [int] $Idempotent
) {
    $value = Get-Content -LiteralPath $Result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    Exact $value.status 'SUCCEEDED' 'M2_E2E_M1_STATUS_INVALID'
    Exact $value.providerCallCount 6 'M2_E2E_M1_CALLS_INVALID'
    Exact $value.retryCount 0 'M2_E2E_M1_RETRY_INVALID'
    Exact $value.receivedFactCount $Received 'M2_E2E_M1_FACTS_INVALID'
    Exact $value.newObservationCount $Appended 'M2_E2E_M1_APPEND_INVALID'
    Exact $value.idempotentChainTailCount $Idempotent `
        'M2_E2E_M1_IDEMPOTENT_INVALID'
    if (-not $value.researchDataset.typedFactReadback -or
        -not $value.researchDataset.systemKnowledgeReadback -or
        -not $value.researchDataset.formulaOnlyQfq -or
        -not $value.researchDataset.noFutureDataLeakage -or
        -not $value.researchDataset.m2Readable -or
        -not $value.outputAudit.clean) {
        throw 'M2_E2E_M1_DATASET_INVALID'
    }
}

function Run-M2([string] $Result, [string] $ExecutionId) {
    $old = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & java "-Dloader.main=$m2Runner" -cp $m2Artifact `
            'org.springframework.boot.loader.launch.PropertiesLauncher' `
            "--result-file=$Result" "--execution-id=$ExecutionId" `
            "--database-port=$port" '--execution-mode=E2E_DRY_RUN' `
            2>&1 | ForEach-Object { [string]$_ } | Out-Host
        return $LASTEXITCODE
    } finally { $ErrorActionPreference = $old }
}

function Run-M3(
    [string] $Result,
    [string] $ReportDirectory,
    [string] $ExecutionId
) {
    $old = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & java "-Dloader.main=$m3Runner" -cp $m3Artifact `
            'org.springframework.boot.loader.launch.PropertiesLauncher' `
            "--result-file=$Result" `
            "--report-directory=$ReportDirectory" `
            "--execution-id=$ExecutionId" `
            "--database-port=$port" '--maximum-cost-cny=5.00' `
            '--execution-mode=E2E_DRY_RUN' `
            2>&1 | ForEach-Object { [string]$_ } | Out-Host
        return $LASTEXITCODE
    } finally { $ErrorActionPreference = $old }
}

function Remove-Root {
    if (-not (Test-Path -LiteralPath $root)) { return }
    $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne
            $target.TrimEnd('\', '/') -or
        -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
        throw 'M2_E2E_CLEANUP_PATH_INVALID'
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

Push-Location $repoRoot
try {
    if ((git rev-parse HEAD).Trim() -ne $ExpectedCommit -or
        @(git status --porcelain=v1 --untracked-files=normal |
            Where-Object {
                $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)'
            }).Count -ne 0 -or
        @(git diff --cached --name-only).Count -ne 0) {
        throw 'M2_E2E_GIT_INVALID'
    }
    & "$PSScriptRoot\prepare-m1-research-data-build-proof.ps1" `
        -ExpectedCommit $ExpectedCommit -Mode E2E_DRY_RUN
    if ($LASTEXITCODE -ne 0) { throw 'M2_E2E_M1_BUILD_FAILED' }
    $m1Hash = ((Get-FileHash -LiteralPath $m1Artifact `
        -Algorithm SHA256).Hash).ToLowerInvariant()

    $listener = [Net.Sockets.TcpListener]::new(
        [Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    if ($port -eq 38432) { throw 'M2_E2E_PORT_INVALID' }
    New-Item -ItemType Directory -Path $root | Out-Null
    & "$pgBin\initdb.exe" -D $data -A trust -U postgres `
        --no-locale --encoding=UTF8 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'M2_E2E_INITDB_FAILED' }
    $arguments = '-D "{0}" -l "{1}" -o "-h 127.0.0.1 -p {2}" -w start' `
        -f $data, $log, $port
    $process = Start-Process "$pgBin\pg_ctl.exe" `
        -ArgumentList $arguments -WorkingDirectory $root `
        -WindowStyle Hidden -PassThru
    try {
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw 'M2_E2E_POSTGRES_START_FAILED'
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
    if ($LASTEXITCODE -ne 0) { throw 'M2_E2E_DATABASE_SETUP_FAILED' }

    $auth1 = Join-Path $root 'capture-1.properties'
    $result1 = Join-Path $root 'capture-1.json'
    Write-M1Authorization $auth1 'M2_E2E_M1_CAPTURE_0001' 'CAPTURE' `
        '2025-01-02' '2025-01-06' '2025-01-06' 0 $m1Hash
    Exact (Run-M1 $auth1 $result1) 0 'M2_E2E_CAPTURE_1_FAILED'
    Assert-M1Success $result1 22 22 0

    $auth2 = Join-Path $root 'capture-2.properties'
    $result2 = Join-Path $root 'capture-2.json'
    Write-M1Authorization $auth2 'M2_E2E_M1_CAPTURE_0002' 'CAPTURE' `
        '2025-01-07' '2025-01-10' '2025-01-10' 6 $m1Hash
    Exact (Run-M1 $auth2 $result2) 0 'M2_E2E_CAPTURE_2_FAILED'
    Assert-M1Success $result2 24 24 0

    $auth3 = Join-Path $root 'idempotent.properties'
    $result3 = Join-Path $root 'idempotent.json'
    Write-M1Authorization $auth3 'M2_E2E_M1_IDEMPOTENT_0003' `
        'IDEMPOTENCY_VERIFICATION' '2025-01-02' '2025-01-10' `
        '2025-01-10' 12 $m1Hash
    Exact (Run-M1 $auth3 $result3) 0 'M2_E2E_IDEMPOTENT_FAILED'
    Assert-M1Success $result3 46 0 46
    Exact (Scalar 'SELECT count(*) FROM tushare_research.pit_market_fact_batches') `
        6 'M2_E2E_BATCH_COUNT_INVALID'
    Exact (Scalar 'SELECT count(*) FROM tushare_research.pit_market_fact_observations') `
        46 'M2_E2E_OBSERVATION_COUNT_INVALID'

    & "$PSScriptRoot\prepare-m2-strategy-research-build-proof.ps1" `
        -ExpectedCommit $ExpectedCommit -Mode E2E_DRY_RUN
    if ($LASTEXITCODE -ne 0) { throw 'M2_E2E_M2_BUILD_FAILED' }
    $m2Hash = ((Get-FileHash -LiteralPath $m2Artifact `
        -Algorithm SHA256).Hash).ToLowerInvariant()
    if ($m2Hash -notmatch '^[0-9a-f]{64}$') {
        throw 'M2_E2E_ARTIFACT_HASH_INVALID'
    }
    $before = Scalar `
        'SELECT count(*) FROM tushare_research.pit_market_fact_observations'
    $m2Result = Join-Path $root 'm2-strategy-research.json'
    $executionId = 'M2SMOKE_20260811T010203Z_A1B2C3D4E5F6'
    Exact (Run-M2 $m2Result $executionId) 0 'M2_E2E_RUNNER_FAILED'
    $m2 = Get-Content -LiteralPath $m2Result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    Exact $m2.schemaVersion 'M2_STRATEGY_RESEARCH_SMOKE_RESULT_V1' `
        'M2_E2E_RESULT_VERSION_INVALID'
    Exact $m2.status 'SUCCEEDED' 'M2_E2E_RESULT_STATUS_INVALID'
    Exact $m2.executionId $executionId 'M2_E2E_EXECUTION_ID_INVALID'
    Exact $m2.gitCommit $ExpectedCommit 'M2_E2E_GIT_BINDING_INVALID'
    Exact $m2.artifactSha256 $m2Hash 'M2_E2E_HASH_BINDING_INVALID'
    Exact $m2.providerCallCount 0 'M2_E2E_PROVIDER_CALLS_INVALID'
    Exact $m2.databaseWriteCount 0 'M2_E2E_DATABASE_WRITES_INVALID'
    Exact $m2.research.securityCount 2 'M2_E2E_SECURITIES_INVALID'
    Exact $m2.research.openSessionCount 7 'M2_E2E_SESSIONS_INVALID'
    Exact $m2.research.rawDailyCount 14 'M2_E2E_RAW_DAILY_INVALID'
    Exact $m2.research.adjustmentFactorCount 14 'M2_E2E_FACTOR_INVALID'
    Exact $m2.research.calendarCount 18 'M2_E2E_CALENDAR_INVALID'
    Exact $m2.research.qfqBarCount 14 'M2_E2E_QFQ_INVALID'
    if (-not $m2.databaseReadOnly -or
        -not $m2.databaseSnapshotUnchanged -or
        -not $m2.outputAudit.clean -or
        -not $m2.research.accountingInvariant -or
        -not $m2.research.lookAheadGuard -or
        -not $m2.research.deterministicReplay -or
        -not $m2.research.typedFactReadback -or
        -not $m2.research.systemKnowledgeReadback -or
        -not $m2.research.dataQuality -or
        -not $m2.research.noFutureDataLeakage) {
        throw 'M2_E2E_RESEARCH_RESULT_INVALID'
    }
    Exact (Scalar `
        'SELECT count(*) FROM tushare_research.pit_market_fact_observations') `
        $before 'M2_E2E_DATABASE_MUTATED'
    if ($IncludeM3) {
        & "$PSScriptRoot\prepare-m3-agent-research-build-proof.ps1" `
            -ExpectedCommit $ExpectedCommit -Mode E2E_DRY_RUN
        if ($LASTEXITCODE -ne 0) { throw 'M3_E2E_BUILD_FAILED' }
        $m3Hash = ((Get-FileHash -LiteralPath $m3Artifact `
            -Algorithm SHA256).Hash).ToLowerInvariant()
        if ($m3Hash -notmatch '^[0-9a-f]{64}$') {
            throw 'M3_E2E_ARTIFACT_HASH_INVALID'
        }
        $m3Result = Join-Path $root 'm3-agent-research.json'
        $m3Reports = Join-Path $root 'agent-research-reports'
        $m3ExecutionId = 'M3SMOKE_20260811T010203Z_A1B2C3D4E5F6'
        Exact (Run-M3 $m3Result $m3Reports $m3ExecutionId) 0 `
            'M3_E2E_RUNNER_FAILED'
        $m3 = Get-Content -LiteralPath $m3Result -Raw -Encoding UTF8 |
            ConvertFrom-Json
        Exact $m3.schemaVersion 'M3_AGENT_RESEARCH_SMOKE_RESULT_V1' `
            'M3_E2E_RESULT_VERSION_INVALID'
        Exact $m3.status 'SUCCEEDED' 'M3_E2E_RESULT_STATUS_INVALID'
        Exact $m3.executionId $m3ExecutionId `
            'M3_E2E_EXECUTION_ID_INVALID'
        Exact $m3.gitCommit $ExpectedCommit 'M3_E2E_GIT_BINDING_INVALID'
        Exact $m3.artifactSha256 $m3Hash 'M3_E2E_HASH_BINDING_INVALID'
        Exact $m3.providerCallCount 0 'M3_E2E_PROVIDER_CALLS_INVALID'
        Exact $m3.databaseWriteCount 0 'M3_E2E_DATABASE_WRITES_INVALID'
        Exact $m3.research.status 'INSUFFICIENT_EVIDENCE' `
            'M3_E2E_RESEARCH_STATUS_INVALID'
        Exact $m3.research.dataset.securityCount 2 `
            'M3_E2E_SECURITIES_INVALID'
        Exact $m3.research.dataset.openSessionCount 7 `
            'M3_E2E_SESSIONS_INVALID'
        Exact $m3.research.strategyExperiments.experiments.Count 4 `
            'M3_E2E_EXPERIMENTS_INVALID'
        Exact $m3.research.toolCallCount 4 'M3_E2E_TOOL_CALLS_INVALID'
        Exact $m3.research.modelCallCount 13 'M3_E2E_MODEL_CALLS_INVALID'
        Exact @($m3.research.agentRuns.agentRole | Sort-Object -Unique).Count 7 `
            'M3_E2E_AGENT_ROLES_INVALID'
        if (-not $m3.databaseReadOnly -or
            -not $m3.databaseSnapshotUnchanged -or
            -not $m3.outputAudit.clean -or
            -not $m3.research.dataset.typedFactReadback -or
            -not $m3.research.dataset.systemKnowledgeReadback -or
            -not $m3.research.dataset.dataQualityPassed -or
            -not $m3.research.dataset.noFutureDataLeakage -or
            -not $m3.research.criticReview.correctionApplied -or
            -not $m3.research.deterministic -or
            -not $m3.research.researchOnly -or
            $m3.research.providerCalled -or $m3.research.shadowStarted -or
            $m3.research.tradingStarted -or
            -not (Test-Path -LiteralPath $m3.reportFile -PathType Leaf)) {
            throw 'M3_E2E_RESEARCH_RESULT_INVALID'
        }
        $directReport = Get-Content -LiteralPath $m3.reportFile -Raw `
            -Encoding UTF8 | ConvertFrom-Json
        Exact $directReport.researchFingerprint `
            $m3.research.researchFingerprint `
            'M3_E2E_REPORT_FILE_MISMATCH'
        $compatibilityResult = Join-Path $root `
            'm3-agent-research-compatibility.json'
        $compatibilityExecutionId =
            'M2SMOKE_20260811T010204Z_B2C3D4E5F607'
        $oldErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & "$PSScriptRoot\run-m2-strategy-research.ps1" `
                -ResultFile $compatibilityResult `
                -ArtifactPath $m3Artifact `
                -ExecutionId $compatibilityExecutionId `
                -M3ExecutionMode E2E_DRY_RUN `
                -M3DatabasePort $port 2>&1 |
                ForEach-Object { [string]$_ } | Out-Host
            $compatibilityExitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $oldErrorAction }
        Exact $compatibilityExitCode 0 `
            'M3_E2E_BROKER_COMPATIBILITY_FAILED'
        $compatibility = Get-Content -LiteralPath $compatibilityResult `
            -Raw -Encoding UTF8 | ConvertFrom-Json
        Exact $compatibility.schemaVersion `
            'M2_STRATEGY_RESEARCH_SMOKE_RESULT_V1' `
            'M3_E2E_COMPATIBILITY_VERSION_INVALID'
        Exact $compatibility.status 'SUCCEEDED' `
            'M3_E2E_COMPATIBILITY_STATUS_INVALID'
        Exact $compatibility.executionId $compatibilityExecutionId `
            'M3_E2E_COMPATIBILITY_EXECUTION_INVALID'
        Exact $compatibility.research.contractVersion `
            'M2_M3_COMPATIBILITY_V1' `
            'M3_E2E_COMPATIBILITY_CONTRACT_INVALID'
        if (-not $compatibility.databaseReadOnly -or
            -not $compatibility.databaseSnapshotUnchanged -or
            -not $compatibility.outputAudit.clean -or
            [int]$compatibility.providerCallCount -ne 0 -or
            [int]$compatibility.databaseWriteCount -ne 0 -or
            -not (Test-Path -LiteralPath `
                "$compatibilityResult.m3.json" -PathType Leaf)) {
            throw 'M3_E2E_BROKER_COMPATIBILITY_INVALID'
        }
        Exact (Scalar `
            'SELECT count(*) FROM tushare_research.pit_market_fact_observations') `
            $before 'M3_E2E_DATABASE_MUTATED'
        Write-Output 'M3_AGENT_TEAM_PACKAGED_FAKE_E2E=PASS'
        Write-Output 'M3_M1_M2_TOOL_CHAIN=PASS'
        Write-Output 'M3_RESIDENT_BROKER_COMPATIBILITY=PASS'
        Write-Output 'M3_TEMP_POSTGRES=PASS'
        Write-Output 'M3_PROVIDER_CALLS=0'
        Write-Output 'M3_PERMANENT_DATABASE_WRITES=0'
    }
    Write-Output 'M2_PACKAGED_FAKE_E2E=PASS'
    Write-Output 'M2_TEMP_POSTGRES=PASS'
    Write-Output 'M2_M1_FAKE_PROVIDER_CALLS=18'
    Write-Output 'M2_STRATEGY_PROVIDER_CALLS=0'
    Write-Output 'M2_PERMANENT_DATABASE_WRITES=0'
} finally {
    Pop-Location
    if (($started -or (Test-Path (Join-Path $data 'postmaster.pid'))) -and
        (Test-Path -LiteralPath $data)) {
        & "$pgBin\pg_ctl.exe" -D $data -m immediate -w stop `
            2>$null | Out-Null
    }
    Remove-Root
    foreach ($file in @(
            $m1Artifact, $m1Proof, "$m1Artifact.original",
            $m2Artifact, $m2Proof, "$m2Artifact.original",
            $m3Artifact, $m3Proof, "$m3Artifact.original")) {
        if (Test-Path -LiteralPath $file) {
            Remove-Item -LiteralPath $file -Force
        }
    }
    if ($port -gt 0 -and (Get-NetTCPConnection -LocalPort $port `
            -State Listen -ErrorAction SilentlyContinue)) {
        throw 'M2_E2E_PORT_REMAINS'
    }
    if (Test-Path -LiteralPath $root) {
        throw 'M2_E2E_TEMP_REMAINS'
    }
    Write-Output 'M2_E2E_RESIDUALS=0'
}
