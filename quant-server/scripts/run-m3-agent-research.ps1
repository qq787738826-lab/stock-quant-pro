[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ResultFile,

    [Parameter(Mandatory = $true)]
    [string] $ReportDirectory,

    [Parameter(Mandatory = $true)]
    [string] $ArtifactPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^M3SMOKE_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$')]
    [string] $ExecutionId,

    [ValidateSet('FAKE', 'OPENAI')]
    [string] $ModelMode = 'FAKE'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$expectedArtifact = Join-Path $target `
    'quant-server-1.3.1-m3-agent-research-runner.jar'
$expectedReports = Join-Path $target 'agent-research\reports'
$runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM3AgentResearchManualRunner'

function Safe-Reason([object[]] $Lines) {
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

Push-Location $repoRoot
try {
    $artifact = [IO.Path]::GetFullPath($ArtifactPath)
    $result = [IO.Path]::GetFullPath($ResultFile)
    $reports = [IO.Path]::GetFullPath($ReportDirectory).TrimEnd('\', '/')
    if (-not $artifact.Equals($expectedArtifact,
            [StringComparison]::OrdinalIgnoreCase) -or
        -not $reports.Equals($expectedReports,
            [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath "$artifact.f1f-b2-proof.properties" `
            -PathType Leaf) -or
        -not $result.StartsWith($target + '\',
            [StringComparison]::OrdinalIgnoreCase) -or
        $result -split '[\/]' -contains '.ai') {
        throw 'M3_AGENT_RESEARCH_PATH_INVALID'
    }
    $output = @(& java "-Dloader.main=$runner" -cp $artifact `
        'org.springframework.boot.loader.launch.PropertiesLauncher' `
        "--result-file=$result" "--report-directory=$reports" `
        "--execution-id=$ExecutionId" '--database-port=38432' `
        "--execution-mode=$(if ($ModelMode -eq 'OPENAI') {
            'FORMAL_LOCAL_OPENAI'
        } else { 'FORMAL_LOCAL' })" 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) {
        Write-Output "M3_AGENT_RESEARCH_FAILURE_REASON=$(Safe-Reason $output)"
        exit 20
    }
    if (-not (Test-Path -LiteralPath $result -PathType Leaf)) {
        throw 'M3_AGENT_RESEARCH_RESULT_MISSING'
    }
    $sanitized = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    $agentRuns = @($sanitized.research.agentRuns)
    $usage = $sanitized.research.totalModelUsage
    [decimal]$estimatedCost = [decimal]::Parse(
        [string]$usage.estimatedCostUsd,
        [Globalization.NumberStyles]::Number,
        [Globalization.CultureInfo]::InvariantCulture)
    $modelEligible = if ($ModelMode -eq 'OPENAI') {
        -not [bool]$sanitized.research.deterministic -and
        [int]$sanitized.research.modelCallCount -eq 13 -and
        [int]$usage.inputTokens -gt 0 -and
        [int]$usage.outputTokens -gt 0 -and
        $estimatedCost -gt 0 -and $estimatedCost -le [decimal]0.10 -and
        @($agentRuns | Where-Object {
            $_.modelProvider -ne 'OPENAI' -or
            $_.model -ne 'gpt-5-mini-2025-08-07'
        }).Count -eq 0
    } else {
        [bool]$sanitized.research.deterministic -and
        $estimatedCost -eq 0
    }
    if ($sanitized.schemaVersion -ne
            'M3_AGENT_RESEARCH_SMOKE_RESULT_V1' -or
        $sanitized.status -ne 'SUCCEEDED' -or
        [int]$sanitized.providerCallCount -ne 0 -or
        [int]$sanitized.databaseWriteCount -ne 0 -or
        -not $sanitized.databaseReadOnly -or
        -not $sanitized.databaseSnapshotUnchanged -or
        -not $sanitized.outputAudit.clean -or
        -not $sanitized.research.researchOnly -or
        $sanitized.research.providerCalled -or
        $sanitized.research.shadowStarted -or
        $sanitized.research.tradingStarted -or
        -not $modelEligible -or
        -not (Test-Path -LiteralPath $sanitized.reportFile -PathType Leaf)) {
        throw 'M3_AGENT_RESEARCH_RESULT_INVALID'
    }
    Write-Output 'M3_AGENT_RESEARCH_AUTOMATION_STATUS=SUCCEEDED'
    Write-Output "M3_AGENT_RESEARCH_EXECUTION_ID=$ExecutionId"
    Write-Output "M3_AGENT_RESEARCH_RESULT=$result"
    Write-Output "M3_AGENT_RESEARCH_REPORT=$($sanitized.reportFile)"
    exit 0
} catch {
    $reason = if ($_.Exception.Message -match
            '^[A-Z][A-Z0-9_]{7,127}$') {
        $_.Exception.Message
    } else { 'M3_AGENT_RESEARCH_AUTOMATION_FAILED' }
    Write-Output "M3_AGENT_RESEARCH_FAILURE_REASON=$reason"
    exit 20
} finally { Pop-Location }
