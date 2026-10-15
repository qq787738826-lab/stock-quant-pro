[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ResultFile,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$')]
    [string] $RequestId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')] [string] $GitCommit,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^20[0-9]{2}-[0-9]{2}-[0-9]{2}$')]
    [string] $TargetEndDate,
    [Parameter(Mandatory = $true)] [ValidateRange(1, 65535)]
    [int] $DatabasePort,
    [Parameter(Mandatory = $true)] [ValidateRange(0, 1000000)]
    [int] $CurrentLedger,
    [Parameter(Mandatory = $true)] [ValidateRange(1, 1000000)]
    [int] $LedgerLimit,
    [Parameter(Mandatory = $true)] [ValidateRange(0, 1000000)]
    [int] $MaximumProviderRequests,
    [ValidateSet('FAKE', 'FORMAL')] [string] $ExecutionMode = 'FAKE'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
$brokerResults = Join-Path $target 'stock-quant-host-broker\results'
$forwardArtifact = Join-Path $target `
    'quant-server-1.3.1-mainboard-trade-cal-forward-increment-runner.jar'
$auditArtifact = Join-Path $target `
    'quant-server-1.3.1-mainboard-catchup-readonly-audit-runner.jar'
$dailyArtifact = Join-Path $target `
    'quant-server-1.3.1-mainboard-daily-increment-runner.jar'
$forwardScript = Join-Path $PSScriptRoot `
    'run-mainboard-trade-cal-forward-increment.ps1'
$auditScript = Join-Path $PSScriptRoot `
    'run-mainboard-catchup-readonly-audit.ps1'
$dailyScript = Join-Path $PSScriptRoot `
    'run-mainboard-daily-increment.ps1'
$operationChain = @(
    'MAINBOARD_TRADE_CAL_FORWARD_INCREMENT',
    'MAINBOARD_CATCHUP_READONLY_AUDIT',
    'MAINBOARD_DAILY_INCREMENT',
    'MAINBOARD_CATCHUP_READONLY_AUDIT'
)
$startedAt = [DateTimeOffset]::UtcNow
$completedAt = $startedAt
$status = 'FAILED'
$readyForShadow = $false
$failureReason = 'AUTOMATIC_MARKET_DATA_MAINTENANCE_RUNNING'
$forwardRequestId = 'NONE'
$initialAuditRequestId = 'NONE'
$finalAuditRequestId = 'NONE'
$forwardAction = 'NOT_RUN'
$calendarBeforeSse = $null
$calendarBeforeSzse = $null
$calendarAfterSse = $null
$calendarAfterSzse = $null
$latestCommon = $null
$initialLatestComplete = $null
$finalLatestComplete = $null
$initialMissing = @()
$initialPartial = @()
$finalMissing = @()
$finalPartial = @()
$dailyResults = @()
$providerCalls = 0
$calendarCalls = 0
$dailyCalls = 0
$factorCalls = 0
$retryCount = 0
$duplicateCount = 0
$result = $null
$temporary = $null
$resultPathValidated = $false

function Safe-Code([object] $ErrorValue, [string] $Fallback) {
    $message = if ($ErrorValue -is [System.Management.Automation.ErrorRecord]) {
        [string]$ErrorValue.Exception.Message
    } elseif ($ErrorValue -is [Exception]) {
        [string]$ErrorValue.Message
    } else { [string]$ErrorValue }
    if ($message -match '^[A-Z][A-Z0-9_]{3,127}$') { return $message }
    return $Fallback
}

function Derived-RequestId([string] $Phase) {
    if ($Phase -notmatch '^[A-Z0-9_-]{3,64}$' -or
        $RequestId -notmatch '^SQHB_([0-9]{8}T[0-9]{6}Z)_[A-F0-9]{12}$') {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_PHASE_INVALID'
    }
    $timestamp = $Matches[1]
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes("$RequestId|$Phase")
        $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace(
            '-', '')
        return "SQHB_${timestamp}_$($hash.Substring(0, 12))"
    } finally { $sha.Dispose() }
}

function Stage-ResultPath([string] $Id, [string] $Suffix) {
    return Join-Path $brokerResults "$Id.$Suffix.json"
}

function Read-StageResult([string] $Path, [string] $FailureCode) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw $FailureCode
    }
    $file = Get-Item -LiteralPath $Path
    if ($file.Length -lt 2 -or $file.Length -gt 1048576) {
        throw $FailureCode
    }
    try {
        return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 |
            ConvertFrom-Json
    } catch { throw $FailureCode }
}

function Add-ProviderAccounting([object] $Value) {
    $script:providerCalls += [int]$Value.tushareProviderCallCount
    $script:retryCount += [int]$Value.retryCount
    $properties = @($Value.PSObject.Properties.Name)
    if ($properties -contains 'tradeCalendarProviderCallCount') {
        $script:calendarCalls +=
            [int]$Value.tradeCalendarProviderCallCount
    } elseif ($properties -contains
            'sseTradeCalendarProviderCallCount' -and
        $properties -contains 'szseTradeCalendarProviderCallCount') {
        $script:calendarCalls +=
            [int]$Value.sseTradeCalendarProviderCallCount +
            [int]$Value.szseTradeCalendarProviderCallCount
    }
    if ($properties -contains 'dailyProviderCallCount') {
        $script:dailyCalls += [int]$Value.dailyProviderCallCount
    }
    if ($properties -contains 'adjustmentFactorProviderCallCount') {
        $script:factorCalls +=
            [int]$Value.adjustmentFactorProviderCallCount
    }
}

function Assert-ArtifactBuildProof([string] $Artifact) {
    $proof = "$Artifact.f1f-b2-proof.properties"
    if (-not (Test-Path -LiteralPath $Artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath $proof -PathType Leaf)) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_BUILD_PROOF_MISSING'
    }
    $hash = ((Get-FileHash -LiteralPath $Artifact -Algorithm SHA256).Hash
        ).ToLowerInvariant()
    $lines = @(Get-Content -LiteralPath $proof -Encoding UTF8)
    $allowedModes = if ($ExecutionMode -eq 'FAKE') {
        @('build.mode=E2E_DRY_RUN')
    } else {
        @('build.mode=CONTROLLED_BUILD_ARTIFACT',
            'build.mode=RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT',
            'build.mode=M6_STAGE_CONTROLLED_BUILD_ARTIFACT')
    }
    if (@($lines | Where-Object {
                $_ -ceq "git.commit=$GitCommit"
            }).Count -ne 1 -or
        @($lines | Where-Object {
                $_ -ceq "artifact.sha256=$hash"
            }).Count -ne 1 -or
        @($lines | Where-Object {
                $_ -in $allowedModes
            }).Count -ne 1) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_BUILD_PROOF_INVALID'
    }
}

function Write-MaintenanceResult {
    $script:completedAt = [DateTimeOffset]::UtcNow
    $script:result = [ordered]@{
        schemaVersion =
            'AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE_RESULT_V1'
        status = $script:status
        requestId = $RequestId
        executionId = ($RequestId -replace '^SQHB_', 'MBMAINT_')
        gitCommit = $GitCommit
        targetEndDate = $TargetEndDate
        startedAt = $startedAt.ToString('o')
        completedAt = $script:completedAt.ToString('o')
        operationChain = $operationChain
        forwardRequestId = $script:forwardRequestId
        initialAuditRequestId = $script:initialAuditRequestId
        finalAuditRequestId = $script:finalAuditRequestId
        forwardAction = $script:forwardAction
        calendarBeforeSse = $script:calendarBeforeSse
        calendarBeforeSzse = $script:calendarBeforeSzse
        calendarAfterSse = $script:calendarAfterSse
        calendarAfterSzse = $script:calendarAfterSzse
        latestCommonCompletedOpenTradeDate = $script:latestCommon
        initialLatestCompleteTradeDate = $script:initialLatestComplete
        initialMissingTradeDates = @($script:initialMissing)
        initialPartialTradeDates = @($script:initialPartial)
        completedTradeDates = @($script:dailyResults | ForEach-Object {
            [string]$_.tradeDate
        })
        dailyResults = @($script:dailyResults)
        finalLatestCommonCompletedOpenTradeDate = $script:latestCommon
        finalLatestCompleteTradeDate = $script:finalLatestComplete
        finalMissingTradeDates = @($script:finalMissing)
        finalPartialTradeDates = @($script:finalPartial)
        duplicateCount = [int]$script:duplicateCount
        ledgerBefore = $CurrentLedger
        ledgerLimit = $LedgerLimit
        maximumProviderRequests = $MaximumProviderRequests
        plannedBaseCalls = 2 * @($script:initialMissing).Count
        projectedWorstCaseLedger = if ($null -eq $script:initialLatestComplete) {
            $CurrentLedger
        } else {
            $CurrentLedger + [int]$script:calendarCalls +
                2 * @($script:initialMissing).Count
        }
        finalLedger = $CurrentLedger + [int]$script:providerCalls
        tushareProviderCallCount = [int]$script:providerCalls
        tradeCalendarProviderCallCount = [int]$script:calendarCalls
        dailyProviderCallCount = [int]$script:dailyCalls
        adjustmentFactorProviderCallCount = [int]$script:factorCalls
        stockBasicProviderCallCount = 0
        retryCount = [int]$script:retryCount
        networkRecoveryBudget = 2
        modelCallCount = 0
        researchSelectionRunsCreated = 0
        shadowRunsCreated = 0
        paperOrdersCreated = 0
        evaluationRowsCreated = 0
        historicalResearchClassification = 'POST_HOC_RESEARCH'
        pitClassification = 'PIT_PARTIAL'
        readyForShadow = [bool]$script:readyForShadow
        outputAuditClean = ($script:status -eq 'SUCCEEDED')
        deterministicFake = ($ExecutionMode -eq 'FAKE')
        dataOnly = $true
        realTradingStarted = $false
        failureReason = if ($script:status -eq 'SUCCEEDED') {
            $null
        } else { $script:failureReason }
    }
    $json = ($script:result | ConvertTo-Json -Depth 12) + "`n"
    $script:temporary = "$ResultFile.$PID.tmp"
    [IO.File]::WriteAllText($script:temporary, $json,
        [Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $ResultFile) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_RESULT_ALREADY_EXISTS'
    }
    [IO.File]::Move($script:temporary, $ResultFile)
    $script:temporary = $null
}

Push-Location $repoRoot
try {
    $fullResult = [IO.Path]::GetFullPath($ResultFile)
    $expectedFormalResult = Join-Path $brokerResults `
        "$RequestId.automatic-daily-market-data-maintenance.json"
    if (-not $fullResult.StartsWith($target + '\',
            [StringComparison]::OrdinalIgnoreCase) -or
        $fullResult -split '[\/]' -contains '.ai' -or
        $CurrentLedger -gt $LedgerLimit -or
        $MaximumProviderRequests -ne $LedgerLimit - $CurrentLedger -or
        ($ExecutionMode -eq 'FORMAL' -and (
            $DatabasePort -ne 38432 -or
            -not $fullResult.Equals(
                [IO.Path]::GetFullPath($expectedFormalResult),
                [StringComparison]::OrdinalIgnoreCase))) -or
        ($ExecutionMode -eq 'FAKE' -and $DatabasePort -eq 38432)) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_PATH_OR_MODE_INVALID'
    }
    $resultPathValidated = $true
    foreach ($path in @($forwardArtifact, $auditArtifact, $dailyArtifact,
            $forwardScript, $auditScript, $dailyScript)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
            $path -split '[\/]' -contains '.ai') {
            throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_FIXED_PATH_INVALID'
        }
    }
    foreach ($artifact in @($forwardArtifact, $auditArtifact,
            $dailyArtifact)) {
        Assert-ArtifactBuildProof $artifact
    }
    if ($ExecutionMode -eq 'FORMAL') {
        Import-Module (Join-Path $PSScriptRoot `
            'host-broker\StockQuantHostBroker.Protocol.psm1') -Force
        $heartbeat = Read-StockQuantHostBrokerHeartbeat `
            -ExpectedGitCommit $GitCommit
        if ([int]$heartbeat.processId -ne $PID -or
            [string]$heartbeat.state -ne 'BUSY') {
            throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_BROKER_CONTEXT_REQUIRED'
        }
    }
    if ($CurrentLedger + 4 -gt $LedgerLimit -or
        $MaximumProviderRequests -lt 4) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_EXHAUSTED'
    }

    $forwardRequestId = Derived-RequestId 'FORWARD'
    $forwardResult = Stage-ResultPath $forwardRequestId `
        'mainboard-trade-cal-forward-increment'
    $forwardOutput = @(& $forwardScript -ResultFile $forwardResult `
        -ArtifactPath $forwardArtifact `
        -ExecutionId ($forwardRequestId -replace '^SQHB_', 'MBTCFWD_') `
        -GitCommit $GitCommit -TargetEndDate $TargetEndDate `
        -DatabasePort $DatabasePort -MaximumProviderRequests 4 `
        -NetworkRecoveryBudget 2 -ExecutionMode $ExecutionMode 2>&1 |
        ForEach-Object { [string]$_ })
    $forwardExit = $LASTEXITCODE
    $forward = Read-StageResult $forwardResult `
        'AUTOMATIC_MARKET_DATA_MAINTENANCE_FORWARD_RESULT_MISSING'
    Add-ProviderAccounting $forward
    if ($forwardExit -ne 0 -or $forward.status -ne 'SUCCEEDED') {
        throw (Safe-Code $forward.failureReason `
            'AUTOMATIC_MARKET_DATA_MAINTENANCE_FORWARD_FAILED')
    }
    $forwardAction = [string]$forward.action
    $calendarBeforeSse = [string]$forward.currentSseMaxCalDate
    $calendarBeforeSzse = [string]$forward.currentSzseMaxCalDate
    $calendarAfterSse = [string]$forward.finalSseMaxCalDate
    $calendarAfterSzse = [string]$forward.finalSzseMaxCalDate
    if ($providerCalls -gt $MaximumProviderRequests -or
        $CurrentLedger + $providerCalls -gt $LedgerLimit) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_EXHAUSTED'
    }

    $initialAuditRequestId = Derived-RequestId 'INITIAL_AUDIT'
    $initialAuditResult = Stage-ResultPath $initialAuditRequestId `
        'mainboard-catchup-readonly-audit'
    $initialAuditOutput = @(& $auditScript -ResultFile $initialAuditResult `
        -ArtifactPath $auditArtifact `
        -ExecutionId ($initialAuditRequestId -replace '^SQHB_', 'MBAUDIT_') `
        -GitCommit $GitCommit -DatabasePort $DatabasePort `
        -CurrentLedger ($CurrentLedger + $providerCalls) `
        -LedgerLimit $LedgerLimit -ExecutionMode $ExecutionMode 2>&1 |
        ForEach-Object { [string]$_ })
    $initialAuditExit = $LASTEXITCODE
    $initialAudit = Read-StageResult $initialAuditResult `
        'AUTOMATIC_MARKET_DATA_MAINTENANCE_INITIAL_AUDIT_RESULT_MISSING'
    Add-ProviderAccounting $initialAudit
    if ($initialAuditExit -ne 0 -or $initialAudit.status -ne 'SUCCEEDED') {
        throw (Safe-Code $initialAudit.failureReason `
            'AUTOMATIC_MARKET_DATA_MAINTENANCE_INITIAL_AUDIT_FAILED')
    }
    $latestCommon = [string]$initialAudit.latestCommonCompletedOpenTradeDate
    $initialLatestComplete = [string]$initialAudit.latestCompleteTradeDate
    $initialMissing = @($initialAudit.missingTradeDates | ForEach-Object {
        [string]$_
    })
    $initialPartial = @($initialAudit.partialTradeDates | ForEach-Object {
        [string]$_
    })
    if ($initialPartial.Count -ne 0) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_PARTIAL_DATE_PRESENT'
    }
    $sortedMissing = @($initialMissing | Sort-Object -Unique)
    if ($sortedMissing.Count -ne $initialMissing.Count -or
        (Compare-Object -ReferenceObject $sortedMissing `
            -DifferenceObject $initialMissing -SyncWindow 0)) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_MISSING_DATES_INVALID'
    }
    [int]$projected = $CurrentLedger + $providerCalls +
        2 * $initialMissing.Count
    if (-not $initialAudit.projectedWithinLimit -or
        [int]$initialAudit.projectedWorstCaseLedger -ne $projected -or
        $projected -gt $LedgerLimit -or
        $projected -gt $CurrentLedger + $MaximumProviderRequests) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_EXHAUSTED'
    }

    foreach ($date in $initialMissing) {
        if ($providerCalls + 2 -gt $MaximumProviderRequests -or
            $CurrentLedger + $providerCalls + 2 -gt $LedgerLimit) {
            throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_EXHAUSTED'
        }
        $dailyRequestId = Derived-RequestId ("DAILY_" +
            $date.Replace('-', '_'))
        $dailyResult = Stage-ResultPath $dailyRequestId `
            'mainboard-daily-increment'
        $dailyOutput = @(& $dailyScript -ResultFile $dailyResult `
            -ArtifactPath $dailyArtifact `
            -ExecutionId ($dailyRequestId -replace '^SQHB_', 'MBINC_') `
            -GitCommit $GitCommit -TradeDate $date `
            -DatabasePort $DatabasePort -MaximumProviderRequests 2 `
            -ExecutionMode $ExecutionMode 2>&1 |
            ForEach-Object { [string]$_ })
        $dailyExit = $LASTEXITCODE
        $daily = Read-StageResult $dailyResult `
            'AUTOMATIC_MARKET_DATA_MAINTENANCE_DAILY_RESULT_MISSING'
        Add-ProviderAccounting $daily
        if ($dailyExit -ne 0 -or $daily.status -ne 'SUCCEEDED') {
            throw (Safe-Code $daily.failureReason `
                'AUTOMATIC_MARKET_DATA_MAINTENANCE_DAILY_FAILED')
        }
        $duplicateCount += [int]$daily.duplicateCount
        $dailyResults += [pscustomobject][ordered]@{
            tradeDate = [string]$daily.tradeDate
            requestId = $dailyRequestId
            dailyRows = [int]$daily.dailyVisibleCount
            adjustmentFactorRows =
                [int]$daily.adjustmentFactorVisibleCount
            dailyProviderCalls = [int]$daily.dailyProviderCallCount
            adjustmentFactorProviderCalls =
                [int]$daily.adjustmentFactorProviderCallCount
            retryCount = [int]$daily.retryCount
            duplicateCount = [int]$daily.duplicateCount
        }
    }

    $finalAuditRequestId = Derived-RequestId 'FINAL_AUDIT'
    $finalAuditResult = Stage-ResultPath $finalAuditRequestId `
        'mainboard-catchup-readonly-audit'
    $finalAuditOutput = @(& $auditScript -ResultFile $finalAuditResult `
        -ArtifactPath $auditArtifact `
        -ExecutionId ($finalAuditRequestId -replace '^SQHB_', 'MBAUDIT_') `
        -GitCommit $GitCommit -DatabasePort $DatabasePort `
        -CurrentLedger ($CurrentLedger + $providerCalls) `
        -LedgerLimit $LedgerLimit -ExecutionMode $ExecutionMode 2>&1 |
        ForEach-Object { [string]$_ })
    $finalAuditExit = $LASTEXITCODE
    $finalAudit = Read-StageResult $finalAuditResult `
        'AUTOMATIC_MARKET_DATA_MAINTENANCE_FINAL_AUDIT_RESULT_MISSING'
    Add-ProviderAccounting $finalAudit
    if ($finalAuditExit -ne 0 -or $finalAudit.status -ne 'SUCCEEDED') {
        throw (Safe-Code $finalAudit.failureReason `
            'AUTOMATIC_MARKET_DATA_MAINTENANCE_FINAL_AUDIT_FAILED')
    }
    $latestCommon =
        [string]$finalAudit.latestCommonCompletedOpenTradeDate
    $finalLatestComplete = [string]$finalAudit.latestCompleteTradeDate
    $finalMissing = @($finalAudit.missingTradeDates | ForEach-Object {
        [string]$_
    })
    $finalPartial = @($finalAudit.partialTradeDates | ForEach-Object {
        [string]$_
    })
    foreach ($dateAudit in @($finalAudit.dateAudits)) {
        $duplicateCount += [int]$dateAudit.duplicateCount
    }
    if ($finalLatestComplete -ne $latestCommon -or
        $finalMissing.Count -ne 0 -or $finalPartial.Count -ne 0 -or
        $duplicateCount -ne 0) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_FINAL_AUDIT_NOT_READY'
    }
    if ($CurrentLedger + $providerCalls -gt $LedgerLimit -or
        $providerCalls -gt $MaximumProviderRequests) {
        throw 'AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_EXHAUSTED'
    }
    $status = 'SUCCEEDED'
    $readyForShadow = $true
    $failureReason = $null
    Write-MaintenanceResult
    Write-Output `
        'AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE_STATUS=SUCCEEDED'
    Write-Output "AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE_REQUEST_ID=$RequestId"
    Write-Output "AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE_RESULT=$ResultFile"
    exit 0
} catch {
    $failureReason = Safe-Code $_ `
        'AUTOMATIC_MARKET_DATA_MAINTENANCE_EXECUTION_FAILED'
    if ($resultPathValidated -and $null -ne $ResultFile -and
        -not (Test-Path -LiteralPath $ResultFile -PathType Leaf)) {
        try { Write-MaintenanceResult } catch { }
    }
    Write-Output `
        "AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE_FAILURE_REASON=$failureReason"
    exit 20
} finally {
    if ($null -ne $temporary -and
        (Test-Path -LiteralPath $temporary -PathType Leaf)) {
        Remove-Item -LiteralPath $temporary -Force
    }
    Pop-Location
}
