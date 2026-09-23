[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ResultFile,
    [Parameter(Mandatory = $true)] [string] $ArtifactPath,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^MBAUDIT_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$')]
    [string] $ExecutionId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')] [string] $GitCommit,
    [Parameter(Mandatory = $true)] [ValidateRange(1, 65535)]
    [int] $DatabasePort,
    [Parameter(Mandatory = $true)] [ValidateRange(0, 1000000)]
    [int] $CurrentLedger,
    [Parameter(Mandatory = $true)] [ValidateRange(1, 1000000)]
    [int] $LedgerLimit,
    [ValidateSet('FAKE', 'FORMAL')] [string] $ExecutionMode = 'FAKE'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$expectedArtifact = Join-Path $target `
    'quant-server-1.3.1-mainboard-catchup-readonly-audit-runner.jar'
$runner = 'com.stockquant.server.agent.marketfacts.' +
    'TushareMainboardCatchupReadonlyAuditManualRunner'

Push-Location $repoRoot
try {
    $artifact = [IO.Path]::GetFullPath($ArtifactPath)
    $result = [IO.Path]::GetFullPath($ResultFile)
    $requestId = $ExecutionId -replace '^MBAUDIT_', 'SQHB_'
    $formalResult = Join-Path $target `
        "stock-quant-host-broker\results\$requestId.mainboard-catchup-readonly-audit.json"
    if ($ExecutionMode -eq 'FORMAL') {
        $heartbeatReader = Get-Command Read-StockQuantHostBrokerHeartbeat `
            -ErrorAction SilentlyContinue
        if ($null -eq $heartbeatReader) {
            throw 'MAINBOARD_CATCHUP_AUDIT_BROKER_CONTEXT_REQUIRED'
        }
        try {
            $heartbeat = Read-StockQuantHostBrokerHeartbeat `
                -ExpectedGitCommit $GitCommit
        } catch {
            throw 'MAINBOARD_CATCHUP_AUDIT_BROKER_CONTEXT_REQUIRED'
        }
        if ([int]$heartbeat.processId -ne $PID -or
            [string]$heartbeat.state -ne 'BUSY') {
            throw 'MAINBOARD_CATCHUP_AUDIT_BROKER_CONTEXT_REQUIRED'
        }
    }
    if (-not $artifact.Equals($expectedArtifact,
            [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath "$artifact.f1f-b2-proof.properties" `
            -PathType Leaf) -or
        -not $result.StartsWith($target + '\',
            [StringComparison]::OrdinalIgnoreCase) -or
        $result -split '[\/]' -contains '.ai' -or
        ($ExecutionMode -eq 'FORMAL' -and -not $result.Equals(
            [IO.Path]::GetFullPath($formalResult),
            [StringComparison]::OrdinalIgnoreCase)) -or
        $CurrentLedger -gt $LedgerLimit -or
        ($ExecutionMode -eq 'FORMAL' -and $DatabasePort -ne 38432) -or
        ($ExecutionMode -eq 'FAKE' -and $DatabasePort -eq 38432)) {
        throw 'MAINBOARD_CATCHUP_AUDIT_PATH_OR_MODE_INVALID'
    }
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& java "-Dloader.main=$runner" -cp $artifact `
            'org.springframework.boot.loader.launch.PropertiesLauncher' `
            "--result-file=$result" "--execution-id=$ExecutionId" `
            "--git-commit=$GitCommit" "--database-port=$DatabasePort" `
            "--current-ledger=$CurrentLedger" `
            "--ledger-limit=$LedgerLimit" `
            "--execution-mode=$ExecutionMode" 2>&1 |
            ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if (-not (Test-Path -LiteralPath $result -PathType Leaf)) {
        Write-Output 'MAINBOARD_CATCHUP_READONLY_AUDIT_FAILURE_REASON=MAINBOARD_CATCHUP_AUDIT_RESULT_MISSING'
        exit 20
    }
    $value = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($exitCode -ne 0) {
        $reason = if ([string]$value.failureReason -match
                '^[A-Z][A-Z0-9_]{3,127}$') {
            [string]$value.failureReason
        } else { 'MAINBOARD_CATCHUP_AUDIT_EXECUTION_FAILED' }
        Write-Output `
            "MAINBOARD_CATCHUP_READONLY_AUDIT_FAILURE_REASON=$reason"
        exit 20
    }
    if ($value.schemaVersion -ne
            'MAINBOARD_CATCHUP_READONLY_AUDIT_RESULT_V1' -or
        $value.status -ne 'SUCCEEDED' -or
        [int]$value.universeMemberCount -lt 1000 -or
        [int]$value.missingTradeDateCount -ne
            @($value.missingTradeDates).Count -or
        [int]$value.partialTradeDateCount -ne
            @($value.partialTradeDates).Count -or
        [int]$value.completeTradeDateCount -ne
            @($value.completeTradeDates).Count -or
        [int]$value.plannedBaseCalls -ne
            2 * [int]$value.missingTradeDateCount -or
        [int]$value.networkRecoveryBudget -ne 0 -or
        [int]$value.currentLedger -ne $CurrentLedger -or
        [int]$value.ledgerLimit -ne $LedgerLimit -or
        [int]$value.projectedWorstCaseLedger -ne
            $CurrentLedger + [int]$value.plannedBaseCalls -or
        [int]$value.tushareProviderCallCount -ne 0 -or
        [int]$value.dailyProviderCallCount -ne 0 -or
        [int]$value.adjustmentFactorProviderCallCount -ne 0 -or
        [int]$value.tradeCalendarProviderCallCount -ne 0 -or
        [int]$value.stockBasicProviderCallCount -ne 0 -or
        [int]$value.retryCount -ne 0 -or
        [int]$value.modelCallCount -ne 0 -or
        [long]$value.databaseRowsWritten -ne 0 -or
        [long]$value.researchSelectionRunsCreated -ne 0 -or
        [long]$value.shadowRunsCreated -ne 0 -or
        [long]$value.paperOrdersCreated -ne 0 -or
        [long]$value.evaluationRowsCreated -ne 0 -or
        -not $value.readOnlyTransaction -or
        -not $value.outputAuditClean -or -not $value.dataOnly -or
        $value.realTradingStarted) {
        throw 'MAINBOARD_CATCHUP_AUDIT_RESULT_INVALID'
    }
    Write-Output `
        'MAINBOARD_CATCHUP_READONLY_AUDIT_AUTOMATION_STATUS=SUCCEEDED'
    Write-Output "MAINBOARD_CATCHUP_READONLY_AUDIT_EXECUTION_ID=$ExecutionId"
    Write-Output "MAINBOARD_CATCHUP_READONLY_AUDIT_RESULT=$result"
    exit 0
} catch {
    $reason = if ($_.Exception.Message -match
            '^[A-Z][A-Z0-9_]{3,127}$') {
        $_.Exception.Message
    } else { 'MAINBOARD_CATCHUP_AUDIT_AUTOMATION_FAILED' }
    Write-Output "MAINBOARD_CATCHUP_READONLY_AUDIT_FAILURE_REASON=$reason"
    exit 20
} finally { Pop-Location }
