[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
$paths = Initialize-StockQuantHostBrokerDirectories
$root = Join-Path $paths.TargetRoot `
    ('stock-quant-mainboard-backfill-protocol-' +
        [Guid]::NewGuid().ToString('N'))
$artifact = Join-Path $root 'mainboard-history-backfill-test.jar'
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
        throw "MAINBOARD_BACKFILL_EXPECTED_REJECTION_MISSING_$Case"
    } catch {
        if ($_.Exception.Message -ne $Reason) { throw }
    }
    $script:tests++
}

try {
    New-Item -ItemType Directory -Path $root | Out-Null
    [IO.File]::WriteAllBytes($artifact, [byte[]](2, 5, 0, 1))
    $hash = ((Get-FileHash $artifact -Algorithm SHA256).Hash
        ).ToLowerInvariant()
    $head = (git -C $paths.RepositoryRoot rev-parse HEAD).Trim()
    $created = [DateTimeOffset]::UtcNow
    $month = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId(
        $created, 'China Standard Time').ToString('yyyy-MM')
    [int]$limit = Get-StockQuantTushareMonthlyLimit -CalendarMonth $month
    if ($month -eq '2026-08' -and $limit -ne 625) {
        throw 'MAINBOARD_BACKFILL_APPROVED_MONTHLY_LIMIT_INVALID'
    }
    $request = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = New-StockQuantHostBrokerRequestId
        'operation' = 'MAINBOARD_HISTORY_BACKFILL'
        'git.commit' = $head
        'jar.path' = $artifact
        'jar.sha256' = $hash
        'authorization.file' = 'NONE'
        'anchor.trade.date' = '2026-08-27'
        'target.sessions' = '250'
        'expected.missing.sessions' = '190'
        'universe.version' = 'RESEARCH_UNIVERSE_MAINBOARD_V1'
        'database.host' = '127.0.0.1'
        'database.port' = '38432'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'schema.name' = 'tushare_research'
        'provider' = 'TUSHARE'
        'provider.endpoints' = 'daily,adj_factor'
        'endpoint.stock_basic.requests' = '0'
        'endpoint.daily.requests' = '190'
        'endpoint.adj_factor.requests' = '190'
        'endpoint.trade_cal.requests' = '0'
        'maximum.provider.requests' = '384'
        'budget.calendar.month' = $month
        'tushare.monthly.limit' = [string]$limit
        'tushare.monthly.calls.before' = '0'
        'retry.budget' = '0'
        'network.recovery.budget' = '4'
        'redirects' = 'NEVER'
        'historical.research.classification' = 'POST_HOC_RESEARCH'
        'pit.classification' = 'PIT_PARTIAL'
        'user.approval.reference' =
            'USER_APPROVED_V1_MAINBOARD_250_SESSION_HISTORY_BACKFILL'
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'V1_MAINBOARD_250_SESSION_HISTORY_BACKFILL'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    Write-Lines "$artifact.f1f-b2-proof.properties" ([ordered]@{
        'git.commit' = $head
        'artifact.sha256' = $hash
        'build.mode' = 'RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT'
    })

    $parsed = Read-Valid $request
    if ($parsed.Operation -ne 'MAINBOARD_HISTORY_BACKFILL' -or
        $parsed.AuthorizationStatus -ne
            'V1_MAINBOARD_250_SESSION_HISTORY_BACKFILL_APPROVED' -or
        [int]$parsed.Values['maximum.provider.requests'] -ne 384) {
        throw 'MAINBOARD_HISTORY_BACKFILL_VALID_REQUEST_REJECTED'
    }
    $tests++

    foreach ($case in @(
        @('target.sessions', '120'),
        @('endpoint.stock_basic.requests', '1'),
        @('endpoint.daily.requests', '189'),
        @('endpoint.adj_factor.requests', '189'),
        @('endpoint.trade_cal.requests', '1'),
        @('maximum.provider.requests', '380'),
        @('network.recovery.budget', '5'),
        @('retry.budget', '1'),
        @('provider.endpoints', 'daily'),
        @('universe.version', 'RESEARCH_UNIVERSE_V1'),
        @('historical.research.classification', 'LIVE_SHADOW'),
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
        "$($ledger['request.id']).mainboard-history-backfill.json"
    Write-Lines $requestPath $ledger
    $result = [ordered]@{
        schemaVersion = 'MAINBOARD_250_SESSION_HISTORY_BACKFILL_RESULT_V1'
        status = 'SUCCEEDED'
        executionId = ($ledger['request.id'] -replace '^SQHB_', 'MBH250_')
        gitCommit = $head
        targetSessions = 250
        expectedMissingSessions = 190
        maximumProviderRequests = 384
        finalCompleteSessions = 250
        milestone120Complete = $true
        final250Complete = $true
        milestone120MissingCount = 0
        final250MissingCount = 0
        partialDateCount = 0
        duplicateCount = 0
        universeMemberCount = 3193
        tushareProviderCallCount = 382
        dailyProviderCallCount = 191
        adjustmentFactorProviderCallCount = 191
        stockBasicProviderCallCount = 0
        tradeCalendarProviderCallCount = 0
        retryCount = 2
        modelCallCount = 0
        dataOnly = $true
        realTradingStarted = $false
        researchSelectionRunsCreated = 0
        shadowRunsCreated = 0
        paperOrdersCreated = 0
        evaluationRowsCreated = 0
        knownAtValid = $true
        firstObservedAtValid = $true
        historicalResearchClassification = 'POST_HOC_RESEARCH'
        pitClassification = 'PIT_PARTIAL'
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
    if ($afterCalls -ne $beforeCalls + 382) {
        throw 'MAINBOARD_HISTORY_BACKFILL_LEDGER_ACCOUNTING_INVALID'
    }
    $tests++

    $broker = Get-Content -LiteralPath (Join-Path $PSScriptRoot `
        'stock-quant-host-broker.ps1') -Raw -Encoding UTF8
    $start = $broker.IndexOf('function Invoke-MainboardHistoryBackfill')
    $end = $broker.IndexOf('function Resolve-ResearchProductionJavaExecutable')
    if ($start -lt 0 -or $end -le $start) {
        throw 'MAINBOARD_HISTORY_BACKFILL_FIXED_DISPATCH_MISSING'
    }
    $section = $broker.Substring($start, $end - $start)
    foreach ($forbidden in @('Bailian', 'AgentResearch', 'Top200',
            'Top30', 'Top10', 'ResearchSelectionEngine')) {
        if ($section.Contains($forbidden)) {
            throw 'MAINBOARD_HISTORY_BACKFILL_SCOPE_EXPANSION_DETECTED'
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
