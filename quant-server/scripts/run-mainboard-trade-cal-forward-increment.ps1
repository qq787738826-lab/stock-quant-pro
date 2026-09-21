[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ResultFile,
    [Parameter(Mandatory = $true)] [string] $ArtifactPath,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^MBTCFWD_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$')]
    [string] $ExecutionId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')] [string] $GitCommit,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^20[0-9]{2}-[0-9]{2}-[0-9]{2}$')]
    [string] $TargetEndDate,
    [Parameter(Mandatory = $true)] [ValidateRange(1, 65535)]
    [int] $DatabasePort,
    [Parameter(Mandatory = $true)] [ValidateSet(4)]
    [int] $MaximumProviderRequests,
    [Parameter(Mandatory = $true)] [ValidateSet(2)]
    [int] $NetworkRecoveryBudget,
    [ValidateSet('FAKE', 'FORMAL')] [string] $ExecutionMode = 'FAKE'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$expectedArtifact = Join-Path $target `
    'quant-server-1.3.1-mainboard-trade-cal-forward-increment-runner.jar'
$runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareMainboardTradeCalendarForwardIncrementManualRunner'

function Get-SafeFailureReason {
    param([object[]] $Lines, [string] $Fallback)
    $prefix = 'MAINBOARD_TRADE_CAL_FORWARD_FAILURE_REASON='
    $matches = @($Lines | Where-Object {
        $null -ne $_ -and ([string]$_).StartsWith($prefix)
    })
    if ($matches.Count -ne 1) { return $Fallback }
    $value = ([string]$matches[0]).Substring($prefix.Length)
    if ($value -notmatch '^[A-Z][A-Z0-9_]{3,127}$') { return $Fallback }
    return $value
}

Push-Location $repoRoot
try {
    $artifact = [IO.Path]::GetFullPath($ArtifactPath)
    $result = [IO.Path]::GetFullPath($ResultFile)
    $targetDate = [datetime]::ParseExact($TargetEndDate, 'yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture)
    if (-not $artifact.Equals($expectedArtifact,
            [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath "$artifact.f1f-b2-proof.properties" `
            -PathType Leaf) -or
        -not $result.StartsWith($target + '\',
            [StringComparison]::OrdinalIgnoreCase) -or
        $result -split '[\/]' -contains '.ai' -or
        ($ExecutionMode -eq 'FORMAL' -and $DatabasePort -ne 38432) -or
        ($ExecutionMode -eq 'FORMAL' -and
            $targetDate.Date -gt [DateTime]::Today) -or
        ($ExecutionMode -eq 'FAKE' -and $DatabasePort -eq 38432)) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_PATH_OR_MODE_INVALID'
    }
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& java "-Dloader.main=$runner" -cp $artifact `
            'org.springframework.boot.loader.launch.PropertiesLauncher' `
            "--result-file=$result" "--execution-id=$ExecutionId" `
            "--git-commit=$GitCommit" `
            "--target-end-date=$TargetEndDate" `
            "--database-port=$DatabasePort" `
            "--maximum-provider-requests=$MaximumProviderRequests" `
            "--network-recovery-budget=$NetworkRecoveryBudget" `
            "--execution-mode=$ExecutionMode" 2>&1 |
            ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if (-not (Test-Path -LiteralPath $result -PathType Leaf)) {
        $reason = Get-SafeFailureReason -Lines $output `
            -Fallback 'MAINBOARD_TRADE_CAL_FORWARD_RESULT_MISSING'
        Write-Output "MAINBOARD_TRADE_CAL_FORWARD_FAILURE_REASON=$reason"
        exit 20
    }
    $value = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($exitCode -ne 0) {
        $reason = if ([string]$value.failureReason -match
                '^[A-Z][A-Z0-9_]{3,127}$') {
            [string]$value.failureReason
        } else { 'MAINBOARD_TRADE_CAL_FORWARD_EXECUTION_FAILED' }
        Write-Output "MAINBOARD_TRADE_CAL_FORWARD_FAILURE_REASON=$reason"
        exit 20
    }
    [int]$calls = [int]$value.tushareProviderCallCount
    [int]$sseCalls = [int]$value.sseTradeCalendarProviderCallCount
    [int]$szseCalls = [int]$value.szseTradeCalendarProviderCallCount
    [int]$recoveries = [int]$value.retryCount
    $noOp = $value.action -eq 'NO_OP' -and $calls -eq 0 -and
        $sseCalls -eq 0 -and $szseCalls -eq 0 -and $recoveries -eq 0
    $captured = $value.action -eq 'APPENDED' -and
        $calls -eq 2 + $recoveries -and
        $calls -eq $sseCalls + $szseCalls -and
        $sseCalls -in @(1, 2, 3) -and $szseCalls -in @(1, 2, 3)
    if ($value.schemaVersion -ne
            'MAINBOARD_TRADE_CAL_FORWARD_INCREMENT_RESULT_V1' -or
        $value.status -ne 'SUCCEEDED' -or -not ($noOp -or $captured) -or
        [string]$value.requestedEndDate -ne $TargetEndDate -or
        $calls -gt $MaximumProviderRequests -or
        $recoveries -gt $NetworkRecoveryBudget -or
        [int]$value.dailyProviderCallCount -ne 0 -or
        [int]$value.adjustmentFactorProviderCallCount -ne 0 -or
        [int]$value.stockBasicProviderCallCount -ne 0 -or
        [int]$value.modelCallCount -ne 0 -or
        [int]$value.duplicateCount -ne 0 -or
        -not $value.continuousCoverage -or
        -not $value.knownAtValid -or
        -not $value.firstObservedAtValid -or
        -not $value.sourceLineageValid -or
        -not $value.providerFieldsPreserved -or
        -not $value.existingFactsUnchanged -or
        -not $value.appendOnly -or -not $value.universeUnchanged -or
        -not $value.outputAuditClean -or -not $value.dataOnly -or
        $value.realTradingStarted -or
        [long]$value.researchSelectionRunsCreated -ne 0 -or
        [long]$value.shadowRunsCreated -ne 0 -or
        [long]$value.paperOrdersCreated -ne 0 -or
        [long]$value.evaluationRowsCreated -ne 0) {
        throw 'MAINBOARD_TRADE_CAL_FORWARD_RESULT_INVALID'
    }
    Write-Output `
        'MAINBOARD_TRADE_CAL_FORWARD_INCREMENT_AUTOMATION_STATUS=SUCCEEDED'
    Write-Output "MAINBOARD_TRADE_CAL_FORWARD_EXECUTION_ID=$ExecutionId"
    Write-Output "MAINBOARD_TRADE_CAL_FORWARD_RESULT=$result"
    exit 0
} catch {
    $reason = if ($_.Exception.Message -match
            '^[A-Z][A-Z0-9_]{3,127}$') {
        $_.Exception.Message
    } else { 'MAINBOARD_TRADE_CAL_FORWARD_AUTOMATION_FAILED' }
    Write-Output "MAINBOARD_TRADE_CAL_FORWARD_FAILURE_REASON=$reason"
    exit 20
} finally { Pop-Location }
