[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ResultFile,
    [Parameter(Mandatory = $true)] [string] $ArtifactPath,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^MBINC_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$')]
    [string] $ExecutionId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')] [string] $GitCommit,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^20[0-9]{2}-[0-9]{2}-[0-9]{2}$')]
    [string] $TradeDate,
    [Parameter(Mandatory = $true)] [ValidateRange(1, 65535)]
    [int] $DatabasePort,
    [Parameter(Mandatory = $true)] [ValidateSet(2)]
    [int] $MaximumProviderRequests,
    [ValidateSet('FAKE', 'FORMAL')] [string] $ExecutionMode = 'FAKE'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$expectedArtifact = Join-Path $target `
    'quant-server-1.3.1-mainboard-daily-increment-runner.jar'
$runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareMainboardDailyIncrementManualRunner'

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
        throw 'MAINBOARD_DAILY_INCREMENT_PATH_OR_MODE_INVALID'
    }
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& java "-Dloader.main=$runner" -cp $artifact `
            'org.springframework.boot.loader.launch.PropertiesLauncher' `
            "--result-file=$result" "--execution-id=$ExecutionId" `
            "--git-commit=$GitCommit" "--trade-date=$TradeDate" `
            "--database-port=$DatabasePort" `
            "--maximum-provider-requests=$MaximumProviderRequests" `
            "--execution-mode=$ExecutionMode" 2>&1 |
            ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if (-not (Test-Path -LiteralPath $result -PathType Leaf)) {
        Write-Output 'MAINBOARD_DAILY_INCREMENT_FAILURE_REASON=MAINBOARD_DAILY_INCREMENT_RESULT_MISSING'
        exit 20
    }
    $value = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($exitCode -ne 0) {
        $reason = if ([string]$value.failureReason -match
                '^[A-Z][A-Z0-9_]{3,127}$') {
            [string]$value.failureReason
        } else { 'MAINBOARD_DAILY_INCREMENT_EXECUTION_FAILED' }
        Write-Output "MAINBOARD_DAILY_INCREMENT_FAILURE_REASON=$reason"
        exit 20
    }
    if ($value.schemaVersion -ne 'MAINBOARD_DAILY_INCREMENT_RESULT_V1' -or
        $value.status -ne 'SUCCEEDED' -or
        [int]$value.universeMemberCount -lt 1000 -or
        [int]$value.tushareProviderCallCount -notin @(0, 2) -or
        [int]$value.dailyProviderCallCount -ne
            $(if ([int]$value.tushareProviderCallCount -eq 2) { 1 } else { 0 }) -or
        [int]$value.adjustmentFactorProviderCallCount -ne
            $(if ([int]$value.tushareProviderCallCount -eq 2) { 1 } else { 0 }) -or
        [int]$value.retryCount -ne 0 -or
        [int]$value.modelCallCount -ne 0 -or
        -not $value.coverageComplete -or -not $value.knownAtValid -or
        -not $value.pitAdmissionPassed -or -not $value.universeUnchanged -or
        -not $value.outputAuditClean -or -not $value.dataOnly -or
        $value.realTradingStarted -or
        [long]$value.researchSelectionRunsCreated -ne 0 -or
        [long]$value.shadowRunsCreated -ne 0 -or
        [long]$value.paperOrdersCreated -ne 0 -or
        [long]$value.evaluationRowsCreated -ne 0) {
        throw 'MAINBOARD_DAILY_INCREMENT_RESULT_INVALID'
    }
    Write-Output 'MAINBOARD_DAILY_INCREMENT_AUTOMATION_STATUS=SUCCEEDED'
    Write-Output "MAINBOARD_DAILY_INCREMENT_EXECUTION_ID=$ExecutionId"
    Write-Output "MAINBOARD_DAILY_INCREMENT_RESULT=$result"
    exit 0
} catch {
    $reason = if ($_.Exception.Message -match
            '^[A-Z][A-Z0-9_]{3,127}$') {
        $_.Exception.Message
    } else { 'MAINBOARD_DAILY_INCREMENT_AUTOMATION_FAILED' }
    Write-Output "MAINBOARD_DAILY_INCREMENT_FAILURE_REASON=$reason"
    exit 20
} finally { Pop-Location }
