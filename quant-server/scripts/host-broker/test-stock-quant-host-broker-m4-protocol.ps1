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
    [string] $Reason
) {
    try {
        Read-Valid $Values | Out-Null
        throw 'M4_PROTOCOL_EXPECTED_REJECTION_MISSING'
    } catch {
        if ($_.Exception.Message -ne $Reason) { throw }
    }
    $script:tests++
}

try {
    New-Item -ItemType Directory -Path $root | Out-Null
    [IO.File]::WriteAllBytes($artifact, [byte[]](1, 2, 3, 4))
    [IO.File]::WriteAllText("$artifact.f1f-b2-proof.properties", "test=true`n",
        [Text.UTF8Encoding]::new($false))
    $hash = ((Get-FileHash $artifact -Algorithm SHA256).Hash).ToLowerInvariant()
    $created = [DateTimeOffset]::UtcNow
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
        'range.start' = '2026-07-12'
        'trade.date' = '2026-08-11'
        'next.trade.date' = 'NONE'
        'capture.mode' = 'CAPTURE'
        'trigger.mode' = 'MANUAL'
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
        'tushare.stage.limit' = '20'
        'tushare.stage.calls.before' = '0'
        'llm.provider' = 'BAILIAN'
        'model' = 'qwen3.7-plus'
        'provider.endpoint' =
            'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'
        'maximum.model.calls' = '13'
        'maximum.output.tokens.per.call' = '900'
        'maximum.cost.cny' = '5.00'
        'llm.stage.limit.cny' = '10.00'
        'retry.budget' = '0'
        'redirects' = 'NEVER'
        'user.approval.reference' =
            'USER_APPROVED_M4_SHADOW_RESEARCH_CNY_10_TUSHARE_20'
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'M4_SHADOW_RESEARCH_REAL_SMOKE'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    $parsed = Read-Valid $request
    if ($parsed.Operation -ne 'RUN_M4_SHADOW_RESEARCH' -or
        $parsed.AuthorizationStatus -ne 'M4_USER_APPROVED_CNY_10_TUSHARE_20') {
        throw 'M4_PROTOCOL_VALID_REQUEST_REJECTED'
    }
    $tests++

    $invoker = Get-Content -LiteralPath (Join-Path $PSScriptRoot
        'invoke-stock-quant-host-broker.ps1') -Raw -Encoding UTF8
    if ($invoker -notmatch '\$resolvedM4TradeDate\s*=\s*if\s*\(\$TradeDate' -or
        $invoker -match '(?im)^\s*\$tradeDate\s*=\s*if\s*\(') {
        throw 'M4_PROTOCOL_TRADE_DATE_PARAMETER_COLLISION'
    }
    $tests++

    $mutations = @(
        @('maximum.provider.requests', '7'),
        @('retry.budget', '1'),
        @('redirects', 'NORMAL'),
        @('model', 'qwen-plus'),
        @('provider.endpoint', 'https://example.invalid'),
        @('capture.mode', 'IDEMPOTENCY_VERIFICATION'),
        @('next.trade.date', '2026-08-12'),
        @('tushare.stage.calls.before', '15'))
    foreach ($mutation in $mutations) {
        $copy = Copy-Values $request
        $copy['request.id'] = New-StockQuantHostBrokerRequestId
        $copy[$mutation[0]] = $mutation[1]
        Reject $copy 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'
    }
    foreach ($field in @('command.text', 'script.path', 'api.key', 'token')) {
        $copy = Copy-Values $request
        $copy['request.id'] = New-StockQuantHostBrokerRequestId
        $copy[$field] = 'forbidden'
        Reject $copy 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'
    }

    Write-Output "STOCK_QUANT_M4_BROKER_PROTOCOL_TESTS=$tests"
    Write-Output 'STOCK_QUANT_M4_BROKER_PROTOCOL_FAILURES=0'
    Write-Output 'STOCK_QUANT_M4_BROKER_REAL_PROVIDER_CALLS=0'
    Write-Output 'STOCK_QUANT_M4_BROKER_PERMANENT_DATABASE_WRITES=0'
} finally {
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
