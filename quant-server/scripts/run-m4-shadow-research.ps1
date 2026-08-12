[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ResultFile,

    [Parameter(Mandatory = $true)]
    [string] $ArtifactPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^M4SHADOW_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$')]
    [string] $ExecutionId,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 65535)]
    [int] $DatabasePort,

    [ValidateSet('FAKE', 'FORMAL')]
    [string] $ExecutionMode = 'FAKE',

    [string] $Securities = '600000:SSE,000001:SZSE',

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^20[0-9]{2}-[0-9]{2}-[0-9]{2}$')]
    [string] $RangeStart,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^20[0-9]{2}-[0-9]{2}-[0-9]{2}$')]
    [string] $TradeDate,

    [ValidatePattern('^(NONE|20[0-9]{2}-[0-9]{2}-[0-9]{2})$')]
    [string] $NextTradeDate = 'NONE',

    [ValidateSet('CAPTURE', 'IDEMPOTENCY_VERIFICATION')]
    [string] $CaptureMode = 'CAPTURE',

    [ValidateSet('MANUAL', 'SCHEDULED', 'HISTORICAL_REPLAY')]
    [string] $TriggerMode = 'MANUAL',

    [ValidateScript({ $_ -gt [decimal]0.00 -and $_ -le [decimal]5.00 })]
    [decimal] $MaximumCostCny = [decimal]5.00
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$expectedArtifact = Join-Path $target `
    'quant-server-1.3.1-m4-shadow-research-runner.jar'
$runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM4ShadowResearchManualRunner'

function Safe-Reason([object[]] $Lines) {
    $prefix = 'M4_SHADOW_RESEARCH_FAILURE_REASON='
    $matches = @($Lines | Where-Object {
        $null -ne $_ -and ([string]$_).StartsWith($prefix)
    })
    if ($matches.Count -eq 1) {
        $value = ([string]$matches[0]).Substring($prefix.Length)
        if ($value -match '^[A-Z][A-Z0-9_]{3,127}$') { return $value }
    }
    return 'M4_SHADOW_RESEARCH_EXECUTION_FAILED'
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
        $result -split '[\/]' -contains '.ai' -or
        ($ExecutionMode -eq 'FORMAL' -and $DatabasePort -ne 38432) -or
        ($ExecutionMode -eq 'FAKE' -and $DatabasePort -eq 38432)) {
        throw 'M4_SHADOW_RESEARCH_PATH_OR_MODE_INVALID'
    }
    $savedErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& java "-Dloader.main=$runner" -cp $artifact `
            'org.springframework.boot.loader.launch.PropertiesLauncher' `
            "--result-file=$result" "--execution-id=$ExecutionId" `
            "--database-port=$DatabasePort" `
            "--execution-mode=$ExecutionMode" "--securities=$Securities" `
            "--range-start=$RangeStart" "--trade-date=$TradeDate" `
            "--next-trade-date=$NextTradeDate" `
            "--capture-mode=$CaptureMode" "--trigger-mode=$TriggerMode" `
            ("--maximum-cost-cny=" + $MaximumCostCny.ToString(
                [Globalization.CultureInfo]::InvariantCulture)) 2>&1 |
            ForEach-Object { [string]$_ })
        $runnerExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorAction
    }
    if ($runnerExitCode -ne 0) {
        $safeReason = Safe-Reason $output
        if (Test-Path -LiteralPath $result -PathType Leaf) {
            $failed = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
                ConvertFrom-Json
            if ($failed.schemaVersion -eq 'M4_SHADOW_RESEARCH_RESULT_V1' -and
                [string]$failed.failureReason -match
                    '^[A-Z][A-Z0-9_]{3,127}$') {
                $safeReason = [string]$failed.failureReason
            }
        }
        Write-Output "M4_SHADOW_RESEARCH_FAILURE_REASON=$safeReason"
        exit 20
    }
    if (-not (Test-Path -LiteralPath $result -PathType Leaf)) {
        throw 'M4_SHADOW_RESEARCH_RESULT_MISSING'
    }
    $value = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($value.schemaVersion -ne 'M4_SHADOW_RESEARCH_RESULT_V1' -or
        $value.status -ne 'SUCCEEDED' -or
        [int]$value.tushareProviderCallCount -ne 6 -or
        [int]$value.retryCount -ne 0 -or
        [int]$value.modelCallCount -ne 13 -or
        [int]$value.toolCallCount -ne 4 -or
        @($value.agentRoles | Sort-Object -Unique).Count -ne 7 -or
        -not $value.typedFactReadback -or
        -not $value.systemKnowledgeReadback -or
        -not $value.formulaOnlyQfq -or
        -not $value.noFutureDataLeakage -or
        -not $value.outputAuditClean -or
        -not $value.researchOnly -or $value.brokerConnected -or
        $value.realTradingStarted) {
        throw 'M4_SHADOW_RESEARCH_RESULT_INVALID'
    }
    Write-Output 'M4_SHADOW_RESEARCH_AUTOMATION_STATUS=SUCCEEDED'
    Write-Output "M4_SHADOW_RESEARCH_EXECUTION_ID=$ExecutionId"
    Write-Output "M4_SHADOW_RESEARCH_RESULT=$result"
    exit 0
} catch {
    $reason = if ($_.Exception.Message -match
            '^[A-Z][A-Z0-9_]{3,127}$') {
        $_.Exception.Message
    } else { 'M4_SHADOW_RESEARCH_AUTOMATION_FAILED' }
    Write-Output "M4_SHADOW_RESEARCH_FAILURE_REASON=$reason"
    exit 20
} finally { Pop-Location }
