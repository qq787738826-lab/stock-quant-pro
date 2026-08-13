[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ResultFile,
    [Parameter(Mandatory = $true)] [string] $ArtifactPath,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^SELECTEXEC_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$')]
    [string] $ExecutionId,
    [Parameter(Mandatory = $true)] [long] $SelectionRunId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^SELECT_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$')]
    [string] $PublicRunId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')] [string] $GitCommit,
    [Parameter(Mandatory = $true)] [ValidateRange(1, 65535)]
    [int] $DatabasePort,
    [Parameter(Mandatory = $true)]
    [ValidateSet(0, 2, 52)]
    [int] $MaximumProviderRequests,
    [ValidateSet('FAKE', 'FORMAL')] [string] $ExecutionMode = 'FAKE',
    [ValidateScript({ $_ -gt [decimal]0 -and $_ -le [decimal]5 })]
    [decimal] $MaximumCostCny = [decimal]5
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$expectedArtifact = Join-Path $target `
    'quant-server-1.3.1-research-selection-runner.jar'
$runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareResearchSelectionManualRunner'

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
        $result -split '[\/]' -contains '.ai' -or
        ($ExecutionMode -eq 'FORMAL' -and $DatabasePort -ne 38432) -or
        ($ExecutionMode -eq 'FAKE' -and $DatabasePort -eq 38432)) {
        throw 'RESEARCH_SELECTION_PATH_OR_MODE_INVALID'
    }
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& java "-Dloader.main=$runner" -cp $artifact `
            'org.springframework.boot.loader.launch.PropertiesLauncher' `
            "--result-file=$result" "--execution-id=$ExecutionId" `
            "--selection-run-id=$SelectionRunId" `
            "--public-run-id=$PublicRunId" "--git-commit=$GitCommit" `
            "--database-port=$DatabasePort" `
            "--maximum-provider-requests=$MaximumProviderRequests" `
            "--execution-mode=$ExecutionMode" `
            ("--maximum-cost-cny=" + $MaximumCostCny.ToString(
                [Globalization.CultureInfo]::InvariantCulture)) 2>&1 |
            ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if ($exitCode -ne 0 -or
        -not (Test-Path -LiteralPath $result -PathType Leaf)) {
        $reason = 'RESEARCH_SELECTION_EXECUTION_FAILED'
        if (Test-Path -LiteralPath $result -PathType Leaf) {
            $failed = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
                ConvertFrom-Json
            if ([string]$failed.failureReason -match
                    '^[A-Z][A-Z0-9_]{3,127}$') {
                $reason = [string]$failed.failureReason
            }
        }
        Write-Output "RESEARCH_SELECTION_FAILURE_REASON=$reason"
        exit 20
    }
    $value = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($value.schemaVersion -ne 'RESEARCH_SELECTION_RUNNER_RESULT_V1' -or
        $value.status -ne 'SUCCEEDED' -or
        [int]$value.universeSize -ne 25 -or
        [int]$value.shortlistSize -ne 10 -or
        [int]$value.retryCount -ne 0 -or
        [int]$value.modelCallCount -ne 13 -or
        [int]$value.toolCallCount -ne 4 -or
        @($value.agentRoles | Sort-Object -Unique).Count -ne 7 -or
        -not $value.typedFactReadback -or
        -not $value.systemKnowledgeReadback -or
        -not $value.formulaOnlyQfq -or
        -not $value.noFutureDataLeakage -or
        -not $value.outputAuditClean -or
        -not $value.researchOnly -or $value.realTradingStarted) {
        throw 'RESEARCH_SELECTION_RESULT_INVALID'
    }
    Write-Output 'RESEARCH_SELECTION_AUTOMATION_STATUS=SUCCEEDED'
    Write-Output "RESEARCH_SELECTION_EXECUTION_ID=$ExecutionId"
    Write-Output "RESEARCH_SELECTION_RESULT=$result"
    exit 0
} catch {
    $reason = if ($_.Exception.Message -match
            '^[A-Z][A-Z0-9_]{3,127}$') {
        $_.Exception.Message
    } else { 'RESEARCH_SELECTION_AUTOMATION_FAILED' }
    Write-Output "RESEARCH_SELECTION_FAILURE_REASON=$reason"
    exit 20
} finally { Pop-Location }
