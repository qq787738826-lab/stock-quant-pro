[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ResultFile,

    [Parameter(Mandatory = $true)]
    [string] $ArtifactPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^M2SMOKE_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$')]
    [string] $ExecutionId
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$expectedArtifact = Join-Path $target `
    'quant-server-1.3.1-m2-strategy-research-runner.jar'
$runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM2StrategyResearchManualRunner'

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

Push-Location $repoRoot
try {
    $artifact = [IO.Path]::GetFullPath($ArtifactPath)
    $result = [IO.Path]::GetFullPath($ResultFile)
    if (-not $artifact.Equals($expectedArtifact,
            [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath "$artifact.f1f-b2-proof.properties" `
            -PathType Leaf) -or
        -not $result.StartsWith($target + '\',
            [StringComparison]::OrdinalIgnoreCase) -or
        $result -split '[\/]' -contains '.ai') {
        throw 'M2_STRATEGY_RESEARCH_PATH_INVALID'
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
