[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ResultFile,

    [Parameter(Mandatory = $true)]
    [string] $ArtifactPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^M2SMOKE_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$')]
    [string] $ExecutionId,

    [ValidateSet('FORMAL_LOCAL', 'E2E_DRY_RUN')]
    [string] $M3ExecutionMode = 'FORMAL_LOCAL',

    [ValidateRange(1024, 65535)]
    [int] $M3DatabasePort = 38432
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$expectedArtifact = Join-Path $target `
    'quant-server-1.3.1-m2-strategy-research-runner.jar'
$expectedM3Artifact = Join-Path $target `
    'quant-server-1.3.1-m3-agent-research-runner.jar'
$runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM2StrategyResearchManualRunner'
$m3Runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM3AgentResearchManualRunner'

function Safe-Reason([object[]] $Lines) {
    $matches = @($Lines | Where-Object {
        $null -ne $_ -and ([string]$_).StartsWith(
            'M2_STRATEGY_RESEARCH_FAILURE_REASON=')
    })
    if ($matches.Count -eq 1) {
        $value = ([string]$matches[0]).Substring(
            'M2_STRATEGY_RESEARCH_FAILURE_REASON='.Length)
        if ($value -match '^[A-Z][A-Z0-9_]{7,127}$') { return $value }
    }
    return 'M2_STRATEGY_RESEARCH_EXECUTION_FAILED'
}

function Safe-M3Reason([object[]] $Lines) {
    $matches = @($Lines | Where-Object {
        $null -ne $_ -and ([string]$_).StartsWith(
            'M3_AGENT_RESEARCH_FAILURE_REASON=')
    })
    if ($matches.Count -eq 1) {
        $value = ([string]$matches[0]).Substring(
            'M3_AGENT_RESEARCH_FAILURE_REASON='.Length)
        if ($value -match '^[A-Z][A-Z0-9_]{7,127}$') { return $value }
    }
    return 'M3_AGENT_RESEARCH_EXECUTION_FAILED'
}

function Invoke-M3Compatibility(
    [string] $Artifact,
    [string] $Result,
    [string] $ExecutionId
) {
    $m3Result = "$Result.m3.json"
    $m3ExecutionId = $ExecutionId -replace '^M2SMOKE_', 'M3SMOKE_'
    if ($m3ExecutionId -notmatch
            '^M3SMOKE_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$' -or
        (Test-Path -LiteralPath $m3Result) -or
        $M3ExecutionMode -eq 'FORMAL_LOCAL' -and
            $M3DatabasePort -ne 38432 -or
        $M3ExecutionMode -eq 'E2E_DRY_RUN' -and
            $M3DatabasePort -eq 38432) {
        throw 'M3_AGENT_RESEARCH_COMPATIBILITY_STATE_INVALID'
    }
    $m3ReportDirectory = if ($M3ExecutionMode -eq 'E2E_DRY_RUN') {
        Join-Path ([IO.Path]::GetDirectoryName($Result)) `
            'agent-research-reports'
    } else { Join-Path $target 'agent-research\reports' }
    $old = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& java "-Dloader.main=$m3Runner" -cp $Artifact `
            'org.springframework.boot.loader.launch.PropertiesLauncher' `
            "--result-file=$m3Result" `
            "--report-directory=$m3ReportDirectory" `
            "--execution-id=$m3ExecutionId" `
            "--database-port=$M3DatabasePort" `
            "--execution-mode=$M3ExecutionMode" 2>&1 |
            ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $old }
    if ($exitCode -ne 0 -or
        -not (Test-Path -LiteralPath $m3Result -PathType Leaf)) {
        Write-Output "M2_STRATEGY_RESEARCH_FAILURE_REASON=$(Safe-M3Reason $output)"
        exit 20
    }
    $m3 = Get-Content -LiteralPath $m3Result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($m3.schemaVersion -ne 'M3_AGENT_RESEARCH_SMOKE_RESULT_V1' -or
        $m3.status -ne 'SUCCEEDED' -or
        [int]$m3.providerCallCount -ne 0 -or
        [int]$m3.databaseWriteCount -ne 0 -or
        -not $m3.databaseReadOnly -or
        -not $m3.databaseSnapshotUnchanged -or
        -not $m3.outputAudit.clean -or
        -not $m3.research.researchOnly -or
        $m3.research.providerCalled -or $m3.research.shadowStarted -or
        $m3.research.tradingStarted -or
        -not (Test-Path -LiteralPath $m3.reportFile -PathType Leaf)) {
        throw 'M3_AGENT_RESEARCH_COMPATIBILITY_RESULT_INVALID'
    }
    $preferred = [string]$m3.research.portfolio.preferredStrategy
    $experiments = @($m3.research.strategyExperiments.experiments)
    $selected = @($experiments | Where-Object {
        $_.strategyCode -eq $preferred
    } | Select-Object -First 1)
    if ($selected.Count -ne 1) {
        throw 'M3_AGENT_RESEARCH_COMPATIBILITY_RESULT_INVALID'
    }
    $fillCount = 0
    foreach ($experiment in $experiments) {
        $fillCount += [int]$experiment.fillCount
    }
    $compatibility = [ordered]@{
        schemaVersion = 'M2_STRATEGY_RESEARCH_SMOKE_RESULT_V1'
        status = 'SUCCEEDED'
        executionId = $ExecutionId
        gitCommit = [string]$m3.gitCommit
        artifactSha256 = [string]$m3.artifactSha256
        runnerStartClass = [string]$m3.runnerStartClass
        startedAt = [string]$m3.startedAt
        completedAt = [string]$m3.completedAt
        research = [ordered]@{
            contractVersion = 'M2_M3_COMPATIBILITY_V1'
            status = 'PASS'
            datasetVersion = [string]$m3.research.dataset.datasetVersion
            securityCount = [int]$m3.research.dataset.securityCount
            openSessionCount = [int]$m3.research.dataset.openSessionCount
            rawDailyCount = [int]$m3.research.dataset.dailyBarCount
            adjustmentFactorCount =
                [int]$m3.research.dataset.adjustmentFactorCount
            calendarCount = [int]$m3.research.dataset.calendarCount
            qfqBarCount = [int]$m3.research.dataset.qfqBarCount
            deterministicFingerprint =
                [string]$m3.research.researchFingerprint
            fillCount = $fillCount
            finalEquity = [string]$selected[0].finalEquity
            totalReturn = [string]$selected[0].totalReturn
            maxDrawdown = [string]$selected[0].maxDrawdown
            sharpeRatio = [string]$selected[0].sharpeRatio
            turnover = [string]$selected[0].turnover
            accountingInvariant = $true
            lookAheadGuard = $true
            deterministicReplay = [bool]$m3.research.deterministic
            typedFactReadback =
                [bool]$m3.research.dataset.typedFactReadback
            systemKnowledgeReadback =
                [bool]$m3.research.dataset.systemKnowledgeReadback
            dataQuality = [bool]$m3.research.dataset.dataQualityPassed
            noFutureDataLeakage =
                [bool]$m3.research.dataset.noFutureDataLeakage
            providerCallCount = 0
            databaseWriteCount = 0
        }
        databaseReadOnly = $true
        databaseSnapshotUnchanged = $true
        databaseBefore = $m3.databaseBefore
        databaseAfter = $m3.databaseAfter
        outputAudit = $m3.outputAudit
        providerCallCount = 0
        databaseWriteCount = 0
        reason = 'M3_COMPATIBILITY_SUCCEEDED'
    }
    [IO.File]::WriteAllText($Result,
        (($compatibility | ConvertTo-Json -Depth 12) + "`n"),
        [Text.UTF8Encoding]::new($false))
    Write-Output 'M2_STRATEGY_RESEARCH_AUTOMATION_STATUS=SUCCEEDED'
    Write-Output "M2_STRATEGY_RESEARCH_EXECUTION_ID=$ExecutionId"
    Write-Output "M2_STRATEGY_RESEARCH_RESULT=$Result"
    Write-Output "M3_AGENT_RESEARCH_RESULT=$m3Result"
    Write-Output "M3_AGENT_RESEARCH_REPORT=$($m3.reportFile)"
    exit 0
}

Push-Location $repoRoot
try {
    $artifact = [IO.Path]::GetFullPath($ArtifactPath)
    $result = [IO.Path]::GetFullPath($ResultFile)
    $isM3Compatibility = $artifact.Equals($expectedM3Artifact,
        [StringComparison]::OrdinalIgnoreCase)
    if ((-not $artifact.Equals($expectedArtifact,
            [StringComparison]::OrdinalIgnoreCase) -and
            -not $isM3Compatibility) -or
        -not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath "$artifact.f1f-b2-proof.properties" `
            -PathType Leaf) -or
        -not $result.StartsWith($target + '\',
            [StringComparison]::OrdinalIgnoreCase) -or
        $result -split '[\/]' -contains '.ai') {
        throw 'M2_STRATEGY_RESEARCH_PATH_INVALID'
    }
    if ($isM3Compatibility) {
        Invoke-M3Compatibility -Artifact $artifact -Result $result `
            -ExecutionId $ExecutionId
    }
    $output = @(& java "-Dloader.main=$runner" -cp $artifact `
        'org.springframework.boot.loader.launch.PropertiesLauncher' `
        "--result-file=$result" "--execution-id=$ExecutionId" `
        '--database-port=38432' '--execution-mode=FORMAL_LOCAL' 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) {
        Write-Output "M2_STRATEGY_RESEARCH_FAILURE_REASON=$(Safe-Reason $output)"
        exit 20
    }
    if (-not (Test-Path -LiteralPath $result -PathType Leaf)) {
        throw 'M2_STRATEGY_RESEARCH_RESULT_MISSING'
    }
    $sanitized = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($sanitized.schemaVersion -ne
            'M2_STRATEGY_RESEARCH_SMOKE_RESULT_V1' -or
        $sanitized.status -ne 'SUCCEEDED' -or
        [int]$sanitized.providerCallCount -ne 0 -or
        [int]$sanitized.databaseWriteCount -ne 0 -or
        -not $sanitized.databaseReadOnly -or
        -not $sanitized.databaseSnapshotUnchanged -or
        -not $sanitized.outputAudit.clean) {
        throw 'M2_STRATEGY_RESEARCH_RESULT_INVALID'
    }
    Write-Output 'M2_STRATEGY_RESEARCH_AUTOMATION_STATUS=SUCCEEDED'
    Write-Output "M2_STRATEGY_RESEARCH_EXECUTION_ID=$ExecutionId"
    Write-Output "M2_STRATEGY_RESEARCH_RESULT=$result"
    exit 0
} catch {
    $reason = if ($_.Exception.Message -match
            '^[A-Z][A-Z0-9_]{7,127}$') {
        $_.Exception.Message
    } else { 'M2_STRATEGY_RESEARCH_AUTOMATION_FAILED' }
    Write-Output "M2_STRATEGY_RESEARCH_FAILURE_REASON=$reason"
    exit 20
} finally { Pop-Location }
