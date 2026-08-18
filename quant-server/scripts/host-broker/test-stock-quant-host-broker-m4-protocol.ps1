[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
$paths = Initialize-StockQuantHostBrokerDirectories
$prefix = 'stock-quant-m4-protocol-'
$root = Join-Path $paths.TargetRoot ($prefix + [Guid]::NewGuid().ToString('N'))
$artifact = Join-Path $root 'm4-protocol-test.jar'
$tests = 0
$ledgerFiles = @()

function Write-Lines([string] $Path, [System.Collections.IDictionary] $Values) {
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
    finally { Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue }
}

function Reject(
    [System.Collections.IDictionary] $Values,
    [string] $Reason,
    [string] $Case = 'UNLABELED'
) {
    try {
        Read-Valid $Values | Out-Null
        $safeCase = $Case.ToUpperInvariant() -replace '[^A-Z0-9_]', '_'
        throw "M4_PROTOCOL_EXPECTED_REJECTION_MISSING_$safeCase"
    } catch {
        if ($_.Exception.Message -ne $Reason) { throw }
    }
    $script:tests++
}

try {
    New-Item -ItemType Directory -Path $root | Out-Null
    [IO.File]::WriteAllBytes($artifact, [byte[]](1, 2, 3, 4))
    $hash = ((Get-FileHash $artifact -Algorithm SHA256).Hash).ToLowerInvariant()
    $created = [DateTimeOffset]::UtcNow
    $chinaZone = [TimeZoneInfo]::FindSystemTimeZoneById(
        'China Standard Time')
    $scheduledDate = [TimeZoneInfo]::ConvertTime($created, $chinaZone).Date
    $calendarMonth = $created.ToOffset(
        [TimeSpan]::FromHours(8)).ToString('yyyy-MM')
    [int]$monthlyTushareLimit = Get-StockQuantTushareMonthlyLimit `
        -CalendarMonth $calendarMonth
    $wrongMonthlyTushareLimit = if ($monthlyTushareLimit -eq 250) {
        '150'
    } else { '250' }
    $request = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = New-StockQuantHostBrokerRequestId
        'operation' = 'RUN_M4_SHADOW_RESEARCH'
        'git.commit' = (git -C $paths.RepositoryRoot rev-parse HEAD).Trim()
        'jar.path' = $artifact
        'jar.sha256' = $hash
        'authorization.file' = 'NONE'
        'm4.runtime' = 'SHADOW_RESEARCH_RUNTIME_V1'
        'm4.scheduler' = 'SHADOW_SCHEDULER_V1'
        'm4.snapshot' = 'SHADOW_SNAPSHOT_V1'
        'm4.paper.portfolio' = 'PAPER_PORTFOLIO_V1'
        'm4.replay' = 'SHADOW_REPLAY_V1'
        'm4.outcome' = 'SHADOW_OUTCOME_V1'
        'm3.agent.runtime' = 'AGENT_RUNTIME_V1'
        'm3.agent.team' = 'AGENT_RESEARCH_TEAM_V1'
        'm3.tool.gateway' = 'AGENT_TOOL_GATEWAY_V1'
        'm2.strategy.engine' = 'STRATEGY_ENGINE_V1'
        'm2.backtest.engine' = 'BACKTEST_ENGINE_V1'
        'securities' = '600000:SSE,000001:SZSE'
        'range.start' = $scheduledDate.AddDays(-30).ToString('yyyy-MM-dd')
        'trade.date' = $scheduledDate.ToString('yyyy-MM-dd')
        'next.trade.date' = 'INTERNAL_CALENDAR'
        'calendar.admission' = 'KNOWN_OPEN'
        'calendar.horizon.end' =
            $scheduledDate.AddDays(30).ToString('yyyy-MM-dd')
        'capture.mode' = 'CAPTURE_OR_IDEMPOTENT'
        'trigger.mode' = 'SCHEDULED'
        'database.host' = '127.0.0.1'
        'database.port' = '38432'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'schema.name' = 'tushare_research'
        'tushare.provider' = 'TUSHARE'
        'tushare.endpoints' = 'daily,adj_factor,trade_cal'
        'endpoint.daily.requests' = '2'
        'endpoint.adj_factor.requests' = '2'
        'endpoint.trade_cal.requests' = '2'
        'maximum.provider.requests' = '6'
        'budget.calendar.month' = $calendarMonth
        'tushare.monthly.limit' = [string]$monthlyTushareLimit
        'tushare.monthly.calls.before' = '0'
        'llm.provider' = 'BAILIAN'
        'model' = 'qwen3.7-plus'
        'provider.endpoint' =
            'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'
        'maximum.model.calls' = '13'
        'maximum.output.tokens.per.call' = '900'
        'maximum.cost.cny' = '5.00'
        'llm.monthly.limit.cny' = '30.00'
        'llm.monthly.cost.before.cny' = '0.00'
        'project.monthly.limit.cny' = '200.00'
        'project.monthly.cost.before.cny' = '6.777600000000'
        'retry.budget' = '0'
        'redirects' = 'NEVER'
        'user.approval.reference' =
            'USER_APPROVED_M4_CONTINUOUS_SHADOW_MONTHLY'
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'M4_SHADOW_RESEARCH_CONTINUOUS_SCHEDULED'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    [IO.File]::WriteAllText("$artifact.f1f-b2-proof.properties", (@(
        "git.commit=$($request['git.commit'])"
        "artifact.sha256=$hash"
        'build.mode=M4_STAGE_CONTROLLED_BUILD_ARTIFACT'
    ) -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    $parsed = Read-Valid $request
    if ($parsed.Operation -ne 'RUN_M4_SHADOW_RESEARCH' -or
        $parsed.AuthorizationStatus -ne
            'M4_USER_APPROVED_CONTINUOUS_MONTHLY') {
        throw 'M4_PROTOCOL_VALID_REQUEST_REJECTED'
    }
    $tests++

    $unknown = Copy-Values $request
    $unknown['request.id'] = New-StockQuantHostBrokerRequestId
    $unknown['calendar.admission'] = 'UNKNOWN'
    $unknown['endpoint.trade_cal.requests'] = '4'
    $unknown['maximum.provider.requests'] = '8'
    $unknownParsed = Read-Valid $unknown
    if ($unknownParsed.Values['calendar.admission'] -ne 'UNKNOWN' -or
        $unknownParsed.Values['maximum.provider.requests'] -ne '8') {
        throw 'M4_PROTOCOL_UNKNOWN_CALENDAR_REQUEST_REJECTED'
    }
    $tests++

    $historical = Copy-Values $unknown
    $historical['request.id'] = New-StockQuantHostBrokerRequestId
    $historicalDate = $scheduledDate.AddDays(-1)
    $historical['range.start'] =
        $historicalDate.AddDays(-30).ToString('yyyy-MM-dd')
    $historical['trade.date'] = $historicalDate.ToString('yyyy-MM-dd')
    $historical['calendar.horizon.end'] =
        $historicalDate.AddDays(30).ToString('yyyy-MM-dd')
    $historical['trigger.mode'] = 'HISTORICAL_REPLAY'
    $historical['user.approval.reference'] =
        'USER_APPROVED_M6_CONTROLLED_SHADOW_SMOKE'
    $historical['execution.source'] =
        'M6_RESEARCH_PRODUCTION_CONTROLLED_REPLAY'
    $historicalParsed = Read-Valid $historical
    if ($historicalParsed.Values['trigger.mode'] -ne
            'HISTORICAL_REPLAY') {
        throw 'M4_PROTOCOL_HISTORICAL_REPLAY_REJECTED'
    }
    $tests++

    $manual = Copy-Values $historical
    $manual['request.id'] = New-StockQuantHostBrokerRequestId
    $manual['trigger.mode'] = 'MANUAL'
    $manual['execution.source'] =
        'M6_RESEARCH_PRODUCTION_CONTROLLED_MANUAL'
    $manualParsed = Read-Valid $manual
    if ($manualParsed.Values['trigger.mode'] -ne 'MANUAL') {
        throw 'M4_PROTOCOL_CONTROLLED_MANUAL_REJECTED'
    }
    $tests++

    $manualWithScheduledApproval = Copy-Values $manual
    $manualWithScheduledApproval['request.id'] =
        New-StockQuantHostBrokerRequestId
    $manualWithScheduledApproval['user.approval.reference'] =
        'USER_APPROVED_M4_CONTINUOUS_SHADOW_MONTHLY'
    Reject $manualWithScheduledApproval `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' `
        'MANUAL_WITH_SCHEDULED_APPROVAL'

    $historicalAsScheduled = Copy-Values $historical
    $historicalAsScheduled['request.id'] =
        New-StockQuantHostBrokerRequestId
    $historicalAsScheduled['trigger.mode'] = 'SCHEDULED'
    $historicalAsScheduled['user.approval.reference'] =
        'USER_APPROVED_M4_CONTINUOUS_SHADOW_MONTHLY'
    $historicalAsScheduled['execution.source'] =
        'M4_SHADOW_RESEARCH_CONTINUOUS_SCHEDULED'
    Reject $historicalAsScheduled `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' `
        'HISTORICAL_AS_SCHEDULED'

    $unknownBadBudget = Copy-Values $unknown
    $unknownBadBudget['request.id'] = New-StockQuantHostBrokerRequestId
    $unknownBadBudget['maximum.provider.requests'] = '6'
    Reject $unknownBadBudget `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' `
        'UNKNOWN_BAD_BUDGET'

    $invokerPath = Join-Path $PSScriptRoot `
        'invoke-stock-quant-host-broker.ps1'
    $invoker = Get-Content -LiteralPath $invokerPath -Raw -Encoding UTF8
    if ($invoker -notmatch '\$resolvedM4TradeDate\s*=\s*if\s*\(\$TradeDate' -or
        $invoker -match '(?im)^\s*\$tradeDate\s*=\s*if\s*\(' -or
        $invoker -notmatch 'M4_FUTURE_TRADE_DATE_FORBIDDEN' -or
        $invoker -notmatch 'M4_MARKET_CLOSE_NOT_AVAILABLE' -or
        $invoker -notmatch 'M6_RESEARCH_PRODUCTION_CONTROLLED_REPLAY' -or
        $invoker -notmatch 'M6_RESEARCH_PRODUCTION_CONTROLLED_MANUAL' -or
        $invoker -notmatch 'ShadowDispatchMode') {
        throw 'M4_PROTOCOL_TRADE_DATE_PARAMETER_COLLISION'
    }
    $tests++

    $brokerPath = Join-Path $PSScriptRoot 'stock-quant-host-broker.ps1'
    $broker = Get-Content -LiteralPath $brokerPath -Raw -Encoding UTF8
    if ($broker -match '\[decimal\]::Min\s*\(' -or
        $broker -notmatch 'Get-M4MonthlyBudget') {
        throw 'M4_PROTOCOL_POWERSHELL_51_DECIMAL_COMPATIBILITY_INVALID'
    }
    $tests++

    if ($null -eq (Get-Command Get-StockQuantM4MonthlyUsage `
            -ErrorAction SilentlyContinue)) {
        throw 'M4_MONTHLY_LEDGER_NOT_EXPORTED'
    }
    $ledgerCases = @(
        [ordered]@{
            id = 'SQHB_20990102T010203Z_A1B2C3D4E5F6'
            admission = 'KNOWN_OPEN'; maximum = 6; status = 'SUCCEEDED'
            calls = 6; model = 13; cost = '1.25'
        },
        [ordered]@{
            id = 'SQHB_20990103T010203Z_A1B2C3D4E5F6'
            admission = 'UNKNOWN'; maximum = 8
            status = 'SKIPPED_NON_TRADING_DAY'; calls = 2
            model = 0; cost = '0.00'
        })
    foreach ($ledgerCase in $ledgerCases) {
        $ledgerRequest = Join-Path $paths.Requests `
            "$($ledgerCase.id).processed.properties"
        $ledgerResult = Join-Path $paths.Results `
            "$($ledgerCase.id).m4-shadow.json"
        Write-Lines $ledgerRequest ([ordered]@{
            'operation' = 'RUN_M4_SHADOW_RESEARCH'
            'request.id' = $ledgerCase.id
            'created.at' = '2099-01-02T01:02:03Z'
            'calendar.admission' = $ledgerCase.admission
            'maximum.provider.requests' = [string]$ledgerCase.maximum
        })
        $runner = [ordered]@{
            schemaVersion = 'M4_SHADOW_RESEARCH_RESULT_V1'
            status = $ledgerCase.status
            executionId = ($ledgerCase.id -replace '^SQHB_', 'M4SHADOW_')
            tushareProviderCallCount = $ledgerCase.calls
            retryCount = 0
            modelProviderRequestCount = $ledgerCase.model
            modelCallCount = $ledgerCase.model
            conservativeCostCny = $ledgerCase.cost
            outputAuditClean = $true
        }
        [IO.File]::WriteAllText($ledgerResult,
            ($runner | ConvertTo-Json -Compress) + "`n",
            [Text.UTF8Encoding]::new($false))
        $ledgerFiles += $ledgerRequest, $ledgerResult
    }
    $usage = Get-StockQuantM4MonthlyUsage -CalendarMonth '2099-01'
    if ([int]$usage.RequestCount -ne 2 -or
        [int]$usage.TushareCalls -ne 8 -or
        [decimal]$usage.ShadowCostCny -ne [decimal]1.25 -or
        [decimal]$usage.ProjectCostCny -ne [decimal]1.25) {
        throw 'M4_MONTHLY_LEDGER_ACCOUNTING_INVALID'
    }
    $tests++

    $mutations = @(
        @('maximum.provider.requests', '7'),
        @('retry.budget', '1'),
        @('redirects', 'NORMAL'),
        @('model', 'qwen-plus'),
        @('provider.endpoint', 'https://example.invalid'),
        @('capture.mode', 'CAPTURE'),
        @('next.trade.date', 'NONE'),
        @('calendar.admission', 'INVALID'),
        @('calendar.horizon.end', '2026-09-11'),
        @('trigger.mode', 'MANUAL'),
        @('tushare.monthly.limit', $wrongMonthlyTushareLimit),
        @('tushare.monthly.calls.before',
            [string]($monthlyTushareLimit - 5)),
        @('llm.monthly.cost.before.cny', '29.50'),
        @('project.monthly.cost.before.cny', '199.50'))
    foreach ($mutation in $mutations) {
        $copy = Copy-Values $request
        $copy['request.id'] = New-StockQuantHostBrokerRequestId
        $copy[$mutation[0]] = $mutation[1]
        Reject $copy 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' `
            "MUTATION_$($mutation[0])"
    }
    foreach ($field in @('command.text', 'script.path', 'api.key', 'token')) {
        $copy = Copy-Values $request
        $copy['request.id'] = New-StockQuantHostBrokerRequestId
        $copy[$field] = 'forbidden'
        Reject $copy 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID' `
            "FIELD_$field"
    }

    Write-Output "STOCK_QUANT_M4_BROKER_PROTOCOL_TESTS=$tests"
    Write-Output 'STOCK_QUANT_M4_BROKER_PROTOCOL_FAILURES=0'
    Write-Output 'STOCK_QUANT_M4_BROKER_REAL_PROVIDER_CALLS=0'
    Write-Output 'STOCK_QUANT_M4_BROKER_PERMANENT_DATABASE_WRITES=0'
} finally {
    foreach ($ledgerFile in $ledgerFiles) {
        Remove-Item -LiteralPath $ledgerFile -Force `
            -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $root) {
        $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
        if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne
                $paths.TargetRoot.TrimEnd('\', '/') -or
            -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
            throw 'M4_PROTOCOL_CLEANUP_INVALID'
        }
        Remove-Item -LiteralPath $full -Recurse -Force
    }
}
