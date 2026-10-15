[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
$paths = Initialize-StockQuantHostBrokerDirectories
$artifact = Join-Path $paths.TargetRoot `
    'quant-server-1.3.1-research-production.jar'
$tests = 0
$cleanup = [Collections.Generic.List[string]]::new()

function Maintenance-RequestId([datetime] $Date, [string] $Commit) {
    $dateText = $Date.ToString('yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture)
    $timestamp = $Date.ToString('yyyyMMdd',
        [Globalization.CultureInfo]::InvariantCulture) + 'T083000Z'
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes(
            "V1_AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE|$dateText|$Commit")
        $hash = ([BitConverter]::ToString(
            $sha.ComputeHash($bytes))).Replace('-', '')
        return "SQHB_${timestamp}_$($hash.Substring(0, 12))"
    } finally { $sha.Dispose() }
}

function Write-Lines(
    [string] $Path,
    [System.Collections.IDictionary] $Values
) {
    $lines = foreach ($key in $Values.Keys) { "$key=$($Values[$key])" }
    [IO.File]::WriteAllText($Path, ($lines -join "`n") + "`n",
        [Text.UTF8Encoding]::new($false))
}

function Copy-Values([System.Collections.IDictionary] $Source) {
    $copy = [ordered]@{}
    foreach ($key in $Source.Keys) { $copy[$key] = $Source[$key] }
    return $copy
}

function Read-Valid(
    [System.Collections.IDictionary] $Values,
    [DateTimeOffset] $Now
) {
    $path = Join-Path $paths.Requests `
        "$($Values['request.id']).processing.properties"
    Write-Lines $path $Values
    try {
        return Read-StockQuantHostBrokerRequest -Path $path -Now $Now
    } finally {
        Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    }
}

function Reject(
    [System.Collections.IDictionary] $Values,
    [DateTimeOffset] $Now,
    [string] $Reason,
    [string] $Case
) {
    try {
        Read-Valid -Values $Values -Now $Now | Out-Null
        throw "AUTOMATIC_MAINTENANCE_EXPECTED_REJECTION_MISSING_$Case"
    } catch {
        if ($_.Exception.Message -ne $Reason) { throw }
    }
    $script:tests++
}

try {
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath `
            "$artifact.f1f-b2-proof.properties" -PathType Leaf)) {
        throw 'AUTOMATIC_MAINTENANCE_PROTOCOL_ARTIFACT_MISSING'
    }
    $head = (git -C $paths.RepositoryRoot rev-parse HEAD).Trim()
    $hash = ((Get-FileHash $artifact -Algorithm SHA256).Hash
        ).ToLowerInvariant()
    $targetDate = [datetime]::ParseExact('2026-09-24', 'yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture)
    $created = [DateTimeOffset]::Parse(
        '2026-09-24T08:30:00Z',
        [Globalization.CultureInfo]::InvariantCulture)
    $now = $created.AddMinutes(1)
    $month = '2026-09'
    [int]$limit = Get-StockQuantTushareMonthlyLimit -CalendarMonth $month
    $request = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = Maintenance-RequestId $targetDate $head
        'operation' = 'AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE'
        'git.commit' = $head
        'jar.path' = $artifact
        'jar.sha256' = $hash
        'authorization.file' = 'NONE'
        'target.end.date' = '2026-09-24'
        'maintenance.chain' =
            'MAINBOARD_TRADE_CAL_FORWARD_INCREMENT>MAINBOARD_CATCHUP_READONLY_AUDIT>MAINBOARD_DAILY_INCREMENT>MAINBOARD_CATCHUP_READONLY_AUDIT'
        'universe.version' = 'RESEARCH_UNIVERSE_MAINBOARD_V1'
        'database.host' = '127.0.0.1'
        'database.port' = '38432'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'schema.name' = 'tushare_research'
        'provider' = 'TUSHARE'
        'provider.endpoints' = 'trade_cal,daily,adj_factor'
        'maximum.provider.requests' = [string]$limit
        'budget.calendar.month' = $month
        'tushare.monthly.limit' = [string]$limit
        'tushare.monthly.calls.before' = '0'
        'retry.budget' = '0'
        'network.recovery.budget' = '2'
        'redirects' = 'NEVER'
        'historical.research.classification' = 'POST_HOC_RESEARCH'
        'pit.classification' = 'PIT_PARTIAL'
        'user.approval.reference' =
            'USER_APPROVED_V1_AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE'
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' =
            'V1_AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }

    $parsed = Read-Valid -Values $request -Now $now
    if ($parsed.Operation -ne
            'AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE' -or
        $parsed.AuthorizationStatus -ne
            'V1_AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE_APPROVED' -or
        [int]$parsed.Values['maximum.provider.requests'] -ne $limit) {
        throw 'AUTOMATIC_MAINTENANCE_VALID_REQUEST_REJECTED'
    }
    $tests++

    foreach ($case in @(
        @('maintenance.chain', 'MAINBOARD_DAILY_INCREMENT'),
        @('provider.endpoints', 'daily,adj_factor'),
        @('maximum.provider.requests', [string]($limit - 1)),
        @('network.recovery.budget', '3'),
        @('retry.budget', '1'),
        @('universe.version', 'RESEARCH_UNIVERSE_V1'),
        @('pit.classification', 'PIT_COMPLETE'),
        @('user.approval.reference', 'UNAPPROVED')
    )) {
        $invalid = Copy-Values $request
        $invalid[$case[0]] = $case[1]
        Reject $invalid $now `
            'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' $case[0]
    }
    $wrongId = Copy-Values $request
    $wrongId['request.id'] = New-StockQuantHostBrokerRequestId
    Reject $wrongId $now `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' 'RANDOM_ID'

    $outside = Copy-Values $request
    $outsideCreated = [DateTimeOffset]::Parse(
        '2026-09-24T07:00:00Z')
    $outside['created.at'] = $outsideCreated.ToString('o')
    $outside['expires.at'] = $outsideCreated.AddMinutes(10).ToString('o')
    Reject $outside $outsideCreated.AddMinutes(1) `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' 'OUTSIDE_WINDOW'

    $unknown = Copy-Values $request
    $unknown['sql'] = 'SELECT secret FROM forbidden'
    Reject $unknown $now `
        'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID' 'SQL_FIELD'

    [int]$before = (Get-StockQuantM4MonthlyUsage `
        -CalendarMonth $month).CommittedTushareCalls
    if ($before + 4 -gt $limit) {
        throw 'AUTOMATIC_MAINTENANCE_PROTOCOL_LEDGER_FIXTURE_EXHAUSTED'
    }
    $ledger = Copy-Values $request
    $ledger['maximum.provider.requests'] = [string]($limit - $before)
    $ledger['tushare.monthly.calls.before'] = [string]$before
    $requestPath = Join-Path $paths.Requests `
        "$($ledger['request.id']).processed.properties"
    $resultPath = Join-Path $paths.Results `
        "$($ledger['request.id']).automatic-daily-market-data-maintenance.json"
    Write-Lines $requestPath $ledger
    $result = [ordered]@{
        schemaVersion =
            'AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE_RESULT_V1'
        status = 'SUCCEEDED'
        requestId = $ledger['request.id']
        executionId = ($ledger['request.id'] -replace '^SQHB_', 'MBMAINT_')
        gitCommit = $head
        targetEndDate = '2026-09-24'
        forwardAction = 'APPENDED'
        calendarAfterSse = '2026-09-24'
        calendarAfterSzse = '2026-09-24'
        operationChain = @(
            'MAINBOARD_TRADE_CAL_FORWARD_INCREMENT',
            'MAINBOARD_CATCHUP_READONLY_AUDIT',
            'MAINBOARD_DAILY_INCREMENT',
            'MAINBOARD_CATCHUP_READONLY_AUDIT')
        initialLatestCompleteTradeDate = '2026-09-23'
        initialMissingTradeDates = @('2026-09-24')
        initialPartialTradeDates = @()
        dailyResults = @([ordered]@{ tradeDate = '2026-09-24' })
        finalLatestCommonCompletedOpenTradeDate = '2026-09-24'
        finalLatestCompleteTradeDate = '2026-09-24'
        finalMissingTradeDates = @()
        finalPartialTradeDates = @()
        duplicateCount = 0
        ledgerBefore = $before
        ledgerLimit = $limit
        maximumProviderRequests = $limit - $before
        plannedBaseCalls = 2
        projectedWorstCaseLedger = $before + 4
        finalLedger = $before + 4
        tushareProviderCallCount = 4
        tradeCalendarProviderCallCount = 2
        dailyProviderCallCount = 1
        adjustmentFactorProviderCallCount = 1
        stockBasicProviderCallCount = 0
        retryCount = 0
        networkRecoveryBudget = 2
        modelCallCount = 0
        researchSelectionRunsCreated = 0
        shadowRunsCreated = 0
        paperOrdersCreated = 0
        evaluationRowsCreated = 0
        historicalResearchClassification = 'POST_HOC_RESEARCH'
        pitClassification = 'PIT_PARTIAL'
        readyForShadow = $true
        outputAuditClean = $true
        dataOnly = $true
        realTradingStarted = $false
        failureReason = $null
    }
    [IO.File]::WriteAllText($resultPath,
        ($result | ConvertTo-Json -Depth 8 -Compress) + "`n",
        [Text.UTF8Encoding]::new($false))
    $cleanup.Add($requestPath)
    $cleanup.Add($resultPath)
    [int]$after = (Get-StockQuantM4MonthlyUsage `
        -CalendarMonth $month).CommittedTushareCalls
    if ($after -ne $before + 4) {
        throw 'AUTOMATIC_MAINTENANCE_LEDGER_ACCOUNTING_INVALID'
    }
    $tests++

    $broker = Get-Content -LiteralPath (Join-Path $PSScriptRoot `
        'stock-quant-host-broker.ps1') -Raw -Encoding UTF8
    $invoker = Get-Content -LiteralPath (Join-Path $PSScriptRoot `
        'invoke-stock-quant-host-broker.ps1') -Raw -Encoding UTF8
    if (-not $broker.Contains(
            "'AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE' {") -or
        -not $broker.Contains(
            'Invoke-AutomaticDailyMarketDataMaintenance') -or
        -not $invoker.Contains(
            "'AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE'") -or
        $broker.Contains('Invoke-Expression') -or
        $invoker.Contains('Invoke-Expression') -or
        $invoker.Contains('-Command')) {
        throw 'AUTOMATIC_MAINTENANCE_FIXED_DISPATCH_INVALID'
    }
    $tests++

    Write-Output "TESTS_RUN=$tests"
    Write-Output 'TESTS_FAILED=0'
    Write-Output 'TESTS_SKIPPED=0'
    Write-Output 'TESTS_ERRORS=0'
    Write-Output 'REAL_TUSHARE_CALLS=0'
    Write-Output 'REAL_BAILIAN_CALLS=0'
} finally {
    foreach ($path in $cleanup) {
        Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    }
}
