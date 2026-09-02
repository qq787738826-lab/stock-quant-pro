[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
$paths = Initialize-StockQuantHostBrokerDirectories
$root = Join-Path $paths.TargetRoot `
    ('stock-quant-trade-cal-backfill-protocol-' +
        [Guid]::NewGuid().ToString('N'))
$artifact = Join-Path $root 'trade-cal-backfill-test.jar'
$tests = 0
$cleanup = @()

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

function Read-Valid([System.Collections.IDictionary] $Values) {
    $path = Join-Path $paths.Requests `
        "$($Values['request.id']).processing.properties"
    Write-Lines $path $Values
    try { return Read-StockQuantHostBrokerRequest -Path $path }
    finally {
        Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    }
}

function Reject(
    [System.Collections.IDictionary] $Values,
    [string] $Reason,
    [string] $Case
) {
    try {
        Read-Valid $Values | Out-Null
        throw "TRADE_CAL_BACKFILL_EXPECTED_REJECTION_MISSING_$Case"
    } catch {
        if ($_.Exception.Message -ne $Reason) { throw }
    }
    $script:tests++
}

try {
    New-Item -ItemType Directory -Path $root | Out-Null
    [IO.File]::WriteAllBytes($artifact, [byte[]](2, 5, 0, 2))
    $hash = ((Get-FileHash $artifact -Algorithm SHA256).Hash
        ).ToLowerInvariant()
    $head = (git -C $paths.RepositoryRoot rev-parse HEAD).Trim()
    $created = [DateTimeOffset]::UtcNow
    $month = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId(
        $created, 'China Standard Time').ToString('yyyy-MM')
    [int]$limit = Get-StockQuantTushareMonthlyLimit -CalendarMonth $month
    if ((Get-StockQuantTushareMonthlyLimit -CalendarMonth '2026-09') -ne
            450) {
        throw 'TRADE_CAL_BACKFILL_APPROVED_MONTHLY_LIMIT_INVALID'
    }
    $request = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = New-StockQuantHostBrokerRequestId
        'operation' = 'TRADE_CAL_BACKFILL'
        'git.commit' = $head
        'jar.path' = $artifact
        'jar.sha256' = $hash
        'authorization.file' = 'NONE'
        'anchor.trade.date' = '2026-08-27'
        'calendar.range.start' = '2025-04-15'
        'calendar.range.end' = '2026-08-27'
        'minimum.common.open.sessions' = '260'
        'target.sessions' = '250'
        'universe.version' = 'RESEARCH_UNIVERSE_MAINBOARD_V1'
        'database.host' = '127.0.0.1'
        'database.port' = '38432'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'schema.name' = 'tushare_research'
        'provider' = 'TUSHARE'
        'provider.endpoints' = 'trade_cal'
        'endpoint.stock_basic.requests' = '0'
        'endpoint.daily.requests' = '0'
        'endpoint.adj_factor.requests' = '0'
        'endpoint.trade_cal.requests' = '2'
        'maximum.provider.requests' = '4'
        'budget.calendar.month' = $month
        'tushare.monthly.limit' = [string]$limit
        'tushare.monthly.calls.before' = '0'
        'retry.budget' = '0'
        'network.recovery.budget' = '2'
        'redirects' = 'NEVER'
        'historical.research.classification' = 'POST_HOC_RESEARCH'
        'pit.classification' = 'PIT_PARTIAL'
        'user.approval.reference' =
            'USER_APPROVED_V1_MAINBOARD_250_SESSION_TRADE_CAL_BACKFILL'
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' =
            'V1_MAINBOARD_250_SESSION_TRADE_CAL_BACKFILL'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    Write-Lines "$artifact.f1f-b2-proof.properties" ([ordered]@{
        'git.commit' = $head
        'artifact.sha256' = $hash
        'build.mode' = 'RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT'
    })

    $parsed = Read-Valid $request
    if ($parsed.Operation -ne 'TRADE_CAL_BACKFILL' -or
        $parsed.AuthorizationStatus -ne
            'V1_MAINBOARD_250_SESSION_TRADE_CAL_BACKFILL_APPROVED' -or
        [int]$parsed.Values['endpoint.trade_cal.requests'] -ne 2 -or
        [int]$parsed.Values['maximum.provider.requests'] -ne 4) {
        throw 'TRADE_CAL_BACKFILL_VALID_REQUEST_REJECTED'
    }
    $tests++

    foreach ($case in @(
        @('calendar.range.start', '2025-04-14'),
        @('calendar.range.end', '2026-08-26'),
        @('minimum.common.open.sessions', '250'),
        @('target.sessions', '260'),
        @('provider.endpoints', 'trade_cal,daily'),
        @('endpoint.stock_basic.requests', '1'),
        @('endpoint.daily.requests', '1'),
        @('endpoint.adj_factor.requests', '1'),
        @('endpoint.trade_cal.requests', '1'),
        @('maximum.provider.requests', '5'),
        @('network.recovery.budget', '3'),
        @('retry.budget', '1'),
        @('universe.version', 'RESEARCH_UNIVERSE_V1'),
        @('pit.classification', 'PIT_COMPLETE')
    )) {
        $invalid = Copy-Values $request
        $invalid['request.id'] = New-StockQuantHostBrokerRequestId
        $invalid[$case[0]] = $case[1]
        Reject $invalid 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' `
            $case[0]
    }

    $unknown = Copy-Values $request
    $unknown['request.id'] = New-StockQuantHostBrokerRequestId
    $unknown['command.text'] = 'Write-Output forbidden'
    Reject $unknown 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID' `
        'UNKNOWN_FIELD'

    [int]$beforeCalls = (Get-StockQuantM4MonthlyUsage `
        -CalendarMonth $month).CommittedTushareCalls
    $ledger = Copy-Values $request
    $ledger['request.id'] = New-StockQuantHostBrokerRequestId
    $requestPath = Join-Path $paths.Requests `
        "$($ledger['request.id']).processed.properties"
    $resultPath = Join-Path $paths.Results `
        "$($ledger['request.id']).mainboard-trade-cal-backfill.json"
    Write-Lines $requestPath $ledger
    $targetDates = @(0..249 | ForEach-Object {
        ([datetime]'2026-08-27').AddDays($_ - 249).ToString('yyyy-MM-dd')
    })
    $result = [ordered]@{
        schemaVersion =
            'MAINBOARD_250_SESSION_TRADE_CAL_BACKFILL_RESULT_V1'
        status = 'SUCCEEDED'
        executionId = ($ledger['request.id'] -replace '^SQHB_', 'MBTC250_')
        gitCommit = $head
        anchorTradeDate = '2026-08-27'
        rangeStart = '2025-04-15'
        rangeEnd = '2026-08-27'
        minimumCommonOpenSessions = 260
        targetSessions = 250
        initialCommonOpenSessions = 60
        finalCommonOpenSessions = 357
        target250TradeDates = $targetDates
        latestCommonOpenTradeDate = '2026-08-27'
        universeMemberCount = 3193
        sseTradeCalendarProviderCallCount = 2
        szseTradeCalendarProviderCallCount = 1
        tushareProviderCallCount = 3
        dailyProviderCallCount = 0
        adjustmentFactorProviderCallCount = 0
        stockBasicProviderCallCount = 0
        retryCount = 1
        networkRecoveryBudget = 2
        maximumProviderRequests = 4
        duplicateCount = 0
        modelCallCount = 0
        dataOnly = $true
        realTradingStarted = $false
        researchSelectionRunsCreated = 0
        shadowRunsCreated = 0
        paperOrdersCreated = 0
        evaluationRowsCreated = 0
        knownAtValid = $true
        firstObservedAtValid = $true
        sourceLineageValid = $true
        universeUnchanged = $true
        outputAuditClean = $true
    }
    [IO.File]::WriteAllText($resultPath,
        ($result | ConvertTo-Json -Compress) + "`n",
        [Text.UTF8Encoding]::new($false))
    $cleanup += $requestPath
    $cleanup += $resultPath
    [int]$afterCalls = (Get-StockQuantM4MonthlyUsage `
        -CalendarMonth $month).CommittedTushareCalls
    if ($afterCalls -ne $beforeCalls + 3) {
        throw 'TRADE_CAL_BACKFILL_LEDGER_ACCOUNTING_INVALID'
    }
    $tests++

    $broker = Get-Content -LiteralPath (Join-Path $PSScriptRoot `
        'stock-quant-host-broker.ps1') -Raw -Encoding UTF8
    $start = $broker.IndexOf(
        'function Invoke-MainboardTradeCalendarBackfill')
    $end = $broker.IndexOf(
        'function Resolve-ResearchProductionJavaExecutable')
    if ($start -lt 0 -or $end -le $start -or
        -not $broker.Contains("'TRADE_CAL_BACKFILL' {")) {
        throw 'TRADE_CAL_BACKFILL_FIXED_DISPATCH_MISSING'
    }
    $section = $broker.Substring($start, $end - $start)
    foreach ($forbidden in @('Bailian', 'Top200', 'Top30', 'Top10',
            'Invoke-ResearchSelection', 'Invoke-M4ShadowResearch')) {
        if ($section.Contains($forbidden)) {
            throw 'TRADE_CAL_BACKFILL_SCOPE_EXPANSION_DETECTED'
        }
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
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
