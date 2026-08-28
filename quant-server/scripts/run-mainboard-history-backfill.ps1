[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ResultFile,
    [Parameter(Mandatory = $true)] [string] $ArtifactPath,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^MBH250_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$')]
    [string] $ExecutionId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')] [string] $GitCommit,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^20[0-9]{2}-[0-9]{2}-[0-9]{2}$')]
    [string] $AnchorTradeDate,
    [Parameter(Mandatory = $true)] [ValidateSet(250)]
    [int] $TargetSessions,
    [Parameter(Mandatory = $true)] [ValidateRange(1, 249)]
    [int] $ExpectedMissingSessions,
    [Parameter(Mandatory = $true)] [ValidateRange(1, 65535)]
    [int] $DatabasePort,
    [Parameter(Mandatory = $true)] [ValidateRange(6, 503)]
    [int] $MaximumProviderRequests,
    [Parameter(Mandatory = $true)] [ValidateSet(4)]
    [int] $NetworkRecoveryBudget,
    [ValidateSet('FAKE', 'FORMAL')] [string] $ExecutionMode = 'FAKE'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$expectedArtifact = Join-Path $target `
    'quant-server-1.3.1-mainboard-history-backfill-runner.jar'
$runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareMainboardHistoryBackfillManualRunner'

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
        ($ExecutionMode -eq 'FAKE' -and $DatabasePort -eq 38432) -or
        $MaximumProviderRequests -ne
            $ExpectedMissingSessions * 2 + $NetworkRecoveryBudget) {
        throw 'MAINBOARD_HISTORY_BACKFILL_PATH_OR_MODE_INVALID'
    }
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& java "-Dloader.main=$runner" -cp $artifact `
            'org.springframework.boot.loader.launch.PropertiesLauncher' `
            "--result-file=$result" "--execution-id=$ExecutionId" `
            "--git-commit=$GitCommit" `
            "--anchor-trade-date=$AnchorTradeDate" `
            "--target-sessions=$TargetSessions" `
            "--expected-missing-sessions=$ExpectedMissingSessions" `
            "--database-port=$DatabasePort" `
            "--maximum-provider-requests=$MaximumProviderRequests" `
            "--network-recovery-budget=$NetworkRecoveryBudget" `
            "--execution-mode=$ExecutionMode" 2>&1 |
            ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if (-not (Test-Path -LiteralPath $result -PathType Leaf)) {
        Write-Output `
            'MAINBOARD_HISTORY_BACKFILL_FAILURE_REASON=MAINBOARD_HISTORY_BACKFILL_RESULT_MISSING'
        exit 20
    }
    $value = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($exitCode -ne 0) {
        $reason = if ([string]$value.failureReason -match
                '^[A-Z][A-Z0-9_]{3,127}$') {
            [string]$value.failureReason
        } else { 'MAINBOARD_HISTORY_BACKFILL_EXECUTION_FAILED' }
        Write-Output "MAINBOARD_HISTORY_BACKFILL_FAILURE_REASON=$reason"
        exit 20
    }
    if ($value.schemaVersion -ne
            'MAINBOARD_250_SESSION_HISTORY_BACKFILL_RESULT_V1' -or
        $value.status -ne 'SUCCEEDED' -or
        [int]$value.targetSessions -ne 250 -or
        [int]$value.finalCompleteSessions -ne 250 -or
        -not $value.milestone120Complete -or
        -not $value.final250Complete -or
        [int]$value.milestone120MissingCount -ne 0 -or
        [int]$value.final250MissingCount -ne 0 -or
        [int]$value.partialDateCount -ne 0 -or
        [int]$value.duplicateCount -ne 0 -or
        [int]$value.universeMemberCount -lt 1000 -or
        [int]$value.stockBasicProviderCallCount -ne 0 -or
        [int]$value.tradeCalendarProviderCallCount -ne 0 -or
        [int]$value.dailyProviderCallCount -lt
            $ExpectedMissingSessions -or
        [int]$value.adjustmentFactorProviderCallCount -lt
            $ExpectedMissingSessions -or
        [int]$value.retryCount -lt 0 -or
        [int]$value.retryCount -gt $NetworkRecoveryBudget -or
        [int]$value.tushareProviderCallCount -ne
            [int]$value.dailyProviderCallCount +
            [int]$value.adjustmentFactorProviderCallCount -or
        [int]$value.modelCallCount -ne 0 -or
        -not $value.knownAtValid -or -not $value.firstObservedAtValid -or
        $value.historicalResearchClassification -ne 'POST_HOC_RESEARCH' -or
        $value.pitClassification -ne 'PIT_PARTIAL' -or
        -not $value.universeUnchanged -or -not $value.outputAuditClean -or
        -not $value.dataOnly -or $value.realTradingStarted -or
        [long]$value.researchSelectionRunsCreated -ne 0 -or
        [long]$value.shadowRunsCreated -ne 0 -or
        [long]$value.paperOrdersCreated -ne 0 -or
        [long]$value.evaluationRowsCreated -ne 0) {
        throw 'MAINBOARD_HISTORY_BACKFILL_RESULT_INVALID'
    }
    Write-Output `
        'MAINBOARD_250_SESSION_HISTORY_BACKFILL_AUTOMATION_STATUS=SUCCEEDED'
    Write-Output "MAINBOARD_HISTORY_BACKFILL_EXECUTION_ID=$ExecutionId"
    Write-Output "MAINBOARD_HISTORY_BACKFILL_RESULT=$result"
    exit 0
} catch {
    $reason = if ($_.Exception.Message -match
            '^[A-Z][A-Z0-9_]{3,127}$') {
        $_.Exception.Message
    } else { 'MAINBOARD_HISTORY_BACKFILL_AUTOMATION_FAILED' }
    Write-Output "MAINBOARD_HISTORY_BACKFILL_FAILURE_REASON=$reason"
    exit 20
} finally { Pop-Location }
