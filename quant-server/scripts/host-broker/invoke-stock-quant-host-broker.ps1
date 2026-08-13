[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        'CHECK_CREDENTIAL_STATUS',
        'DIAGNOSE_TUSHARE_CREDENTIAL',
        'RUN_FAKE_E2E',
        'RUN_DAY001',
        'RUN_M1_RESEARCH_DATA',
        'VERIFY_M1_TUSHARE_TOKEN',
        'RUN_M2_STRATEGY_RESEARCH_SMOKE',
        'CHECK_BAILIAN_CREDENTIAL_STATUS',
        'RUN_M3_AGENT_RESEARCH_SMOKE',
        'RUN_M4_SHADOW_RESEARCH',
        'START_RESEARCH_PRODUCTION',
        'STOP_RESEARCH_PRODUCTION',
        'CHECK_RESEARCH_PRODUCTION_STATUS',
        'READ_SANITIZED_RESULT'
    )]
    [string] $Operation,

    [string] $AuthorizationFile = 'NONE',

    [string] $ArtifactPath,

    [string] $SourceRequestId = 'NONE',

    [ValidatePattern('^(AUTO|SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12})$')]
    [string] $RequestId = 'AUTO',

    [ValidatePattern('^(AUTO|20[0-9]{2}-[0-9]{2}-[0-9]{2})$')]
    [string] $TradeDate = 'AUTO',

    [ValidateSet('KNOWN_OPEN', 'UNKNOWN')]
    [string] $CalendarAdmission = 'KNOWN_OPEN',

    [ValidateSet('AUTO', 'CONTROLLED_MANUAL')]
    [string] $ShadowDispatchMode = 'AUTO',

    [switch] $SubmitOnly,

    [ValidateRange(5, 2700)]
    [int] $TimeoutSeconds = 2700
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force

$paths = Initialize-StockQuantHostBrokerDirectories
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$integrationBranch = 'feature/1.4.0-agent-team'

if (($RequestId -ne 'AUTO' -or $TradeDate -ne 'AUTO' -or
        $ShadowDispatchMode -ne 'AUTO' -or $SubmitOnly) -and
    $Operation -notin @('RUN_M4_SHADOW_RESEARCH',
        'START_RESEARCH_PRODUCTION', 'STOP_RESEARCH_PRODUCTION',
        'CHECK_RESEARCH_PRODUCTION_STATUS')) {
    throw 'STOCK_QUANT_HOST_BROKER_FIXED_DISPATCH_ARGUMENT_INVALID'
}

function Assert-StockQuantHostBrokerInvoker([object] $Heartbeat) {
    $isCodexSandbox = $identity -match '(?i)CodexSandbox'
    $isResidentUser = $identity -ceq [string]$Heartbeat.windowsUser
    if (-not $isCodexSandbox -and -not $isResidentUser) {
        throw 'STOCK_QUANT_HOST_BROKER_CODEX_SANDBOX_REQUIRED'
    }
}
if ([string]::IsNullOrWhiteSpace($ArtifactPath)) {
    $artifactName = if ($Operation -in @(
            'CHECK_BAILIAN_CREDENTIAL_STATUS',
            'RUN_M3_AGENT_RESEARCH_SMOKE')) {
        'quant-server-1.3.1-m3-agent-research-runner.jar'
    } elseif ($Operation -in @('START_RESEARCH_PRODUCTION',
            'STOP_RESEARCH_PRODUCTION',
            'CHECK_RESEARCH_PRODUCTION_STATUS')) {
        'quant-server-1.3.1-research-production.jar'
    } elseif ($Operation -eq 'RUN_M4_SHADOW_RESEARCH') {
        'quant-server-1.3.1-m4-shadow-research-runner.jar'
    } elseif ($Operation -eq
            'RUN_M2_STRATEGY_RESEARCH_SMOKE') {
        'quant-server-1.3.1-m2-strategy-research-runner.jar'
    } elseif ($Operation -in @(
            'RUN_M1_RESEARCH_DATA', 'VERIFY_M1_TUSHARE_TOKEN')) {
        'quant-server-1.3.1-m1-research-data-runner.jar'
    } else {
        'quant-server-1.3.1-reduced-research-day001-runner.jar'
    }
    $ArtifactPath = Join-Path $paths.TargetRoot $artifactName
}
$expectedM3Artifact = Join-Path $paths.TargetRoot `
    'quant-server-1.3.1-m3-agent-research-runner.jar'
$isM3Compatibility = $Operation -eq 'RUN_M2_STRATEGY_RESEARCH_SMOKE' -and
    [IO.Path]::GetFullPath($ArtifactPath).Equals($expectedM3Artifact,
        [StringComparison]::OrdinalIgnoreCase)
if ($Operation -in @(
        'READ_SANITIZED_RESULT',
        'DIAGNOSE_TUSHARE_CREDENTIAL',
        'RUN_M3_AGENT_RESEARCH_SMOKE')) {
    if ($SourceRequestId -notmatch
            '^SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$') {
        throw 'STOCK_QUANT_HOST_BROKER_SOURCE_REQUEST_INVALID'
    }
} elseif ($SourceRequestId -ne 'NONE') {
    throw 'STOCK_QUANT_HOST_BROKER_SOURCE_REQUEST_INVALID'
}

Push-Location $paths.RepositoryRoot
try {
    $branch = (git branch --show-current).Trim()
    $requiredBranch = if ($Operation -in @(
            'RUN_M1_RESEARCH_DATA', 'VERIFY_M1_TUSHARE_TOKEN') -and
        $branch -eq 'codex/1.4.0-m1-research-data-ready') {
        'codex/1.4.0-m1-research-data-ready'
    } elseif ($Operation -eq 'RUN_M2_STRATEGY_RESEARCH_SMOKE' -and
        $branch -eq 'codex/1.4.0-m2-strategy-engine-ready') {
        'codex/1.4.0-m2-strategy-engine-ready'
    } elseif ($isM3Compatibility -and
        $branch -eq 'codex/1.4.0-m3-agent-research-ready') {
        'codex/1.4.0-m3-agent-research-ready'
    } elseif ($Operation -in @(
            'CHECK_BAILIAN_CREDENTIAL_STATUS',
            'RUN_M3_AGENT_RESEARCH_SMOKE') -and
        $branch -eq 'codex/1.4.0-m3-agent-research-ready') {
        'codex/1.4.0-m3-agent-research-ready'
    } elseif ($Operation -eq 'RUN_M4_SHADOW_RESEARCH' -and
        $branch -eq 'codex/1.4.0-m4-shadow-research-ready') {
        'codex/1.4.0-m4-shadow-research-ready'
    } elseif ($Operation -eq 'RUN_M4_SHADOW_RESEARCH' -and
        $branch -eq 'codex/1.4.0-m6-research-production-ready') {
        'codex/1.4.0-m6-research-production-ready'
    } elseif ($Operation -in @('START_RESEARCH_PRODUCTION',
            'STOP_RESEARCH_PRODUCTION',
            'CHECK_RESEARCH_PRODUCTION_STATUS') -and
        $branch -eq 'codex/1.4.0-m6-research-production-ready') {
        'codex/1.4.0-m6-research-production-ready'
    } else { $integrationBranch }
    $remoteRef = "refs/heads/$requiredBranch"
    $remoteQuery = @(& git ls-remote --exit-code origin $remoteRef 2>&1 |
        ForEach-Object { [string]$_ })
    $remoteQueryExit = $LASTEXITCODE
    $remoteMatch = @($remoteQuery | Where-Object {
        $_ -match "^([0-9a-f]{40})\s+$([regex]::Escape($remoteRef))$"
    })
    if ($remoteQueryExit -ne 0 -or $remoteMatch.Count -ne 1) {
        throw 'STOCK_QUANT_HOST_BROKER_GIT_REMOTE_QUERY_FAILED'
    }
    $head = (git rev-parse HEAD).Trim()
    $remote = ($remoteMatch[0] -split '\s+', 2)[0]
    $tracking = (git rev-parse `
        "refs/remotes/origin/$requiredBranch").Trim()
    $divergence = (git rev-list --left-right --count `
        "$requiredBranch...origin/$requiredBranch").Trim() -split '\s+'
    $unexpected = @(git status --porcelain=v1 --untracked-files=normal |
        Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' })
    if ($branch -ne $requiredBranch -or $head -ne $remote -or
        $tracking -ne $remote -or
        $divergence.Count -ne 2 -or $divergence[0] -ne '0' -or
        $divergence[1] -ne '0' -or $unexpected.Count -ne 0 -or
        @(git diff --cached --name-only).Count -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_GIT_BASELINE_INVALID'
    }

    $heartbeat = Read-StockQuantHostBrokerHeartbeat `
        -ExpectedGitCommit $head -AllowAncestorGitCommit
    Assert-StockQuantHostBrokerInvoker -Heartbeat $heartbeat

    $artifact = Assert-StockQuantPathInside -Path $ArtifactPath `
        -Root $paths.TargetRoot `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_JAR_PATH_INVALID' `
        -MustExist -PathType Leaf
    if ($Operation -in @(
            'DIAGNOSE_TUSHARE_CREDENTIAL',
            'RUN_M2_STRATEGY_RESEARCH_SMOKE',
            'CHECK_BAILIAN_CREDENTIAL_STATUS',
            'RUN_M3_AGENT_RESEARCH_SMOKE',
            'RUN_M4_SHADOW_RESEARCH',
            'START_RESEARCH_PRODUCTION',
            'STOP_RESEARCH_PRODUCTION',
            'CHECK_RESEARCH_PRODUCTION_STATUS')) {
        if ($AuthorizationFile -ne 'NONE') {
            throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_MODE_INVALID'
        }
        $authorization = 'NONE'
    } else {
        $authorization = Assert-StockQuantPathInside -Path $AuthorizationFile `
            -Root $paths.TargetRoot `
            -FailureCode 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_PATH_INVALID' `
            -MustExist -PathType Leaf
    }
    $artifactHash = ((Get-FileHash -LiteralPath $artifact `
        -Algorithm SHA256).Hash).ToLowerInvariant()
    $requestId = if ($RequestId -eq 'AUTO') {
        New-StockQuantHostBrokerRequestId
    } else { $RequestId }
    $createdAt = [DateTimeOffset]::UtcNow
    $expiresAt = $createdAt.AddMinutes(10)
    if ($Operation -in @('START_RESEARCH_PRODUCTION',
            'STOP_RESEARCH_PRODUCTION',
            'CHECK_RESEARCH_PRODUCTION_STATUS')) {
        $requestValues = [ordered]@{
            'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
            'request.id' = $requestId
            'operation' = $Operation
            'git.commit' = $head
            'jar.path' = $artifact
            'jar.sha256' = $artifactHash
            'authorization.file' = 'NONE'
            'm6.production' = 'RESEARCH_PRODUCTION_V1'
            'database.host' = '127.0.0.1'
            'database.port' = '38432'
            'database.name' = 'stock_quant_research'
            'database.user' = 'stock_quant_research'
            'schema.name' = 'tushare_research'
            'backend.host' = '127.0.0.1'
            'backend.port' = '8080'
            'provider' = 'NONE'
            'maximum.provider.requests' = '0'
            'retry.budget' = '0'
            'redirects' = 'NEVER'
            'created.at' = $createdAt.ToString('o')
            'expires.at' = $expiresAt.ToString('o')
            'execution.source' = 'M6_RESEARCH_PRODUCTION_LOCAL'
            'no.retry' = 'true'
            'source.request.id' = 'NONE'
        }
    } elseif ($Operation -eq 'CHECK_BAILIAN_CREDENTIAL_STATUS') {
        $requestValues = [ordered]@{
            'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
            'request.id' = $requestId
            'operation' = $Operation
            'git.commit' = $head
            'jar.path' = $artifact
            'jar.sha256' = $artifactHash
            'authorization.file' = 'NONE'
            'provider' = 'BAILIAN'
            'model' = 'qwen3.7-plus'
            'provider.endpoint' = 'NONE'
            'maximum.model.calls' = '0'
            'maximum.cost.cny' = '0.00'
            'retry.budget' = '0'
            'redirects' = 'NEVER'
            'created.at' = $createdAt.ToString('o')
            'expires.at' = $expiresAt.ToString('o')
            'execution.source' =
                'M3_BAILIAN_CREDENTIAL_READABILITY_CHECK'
            'no.retry' = 'true'
            'source.request.id' = 'NONE'
        }
    } elseif ($Operation -eq 'RUN_M3_AGENT_RESEARCH_SMOKE') {
        $requestValues = [ordered]@{
            'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
            'request.id' = $requestId
            'operation' = $Operation
            'git.commit' = $head
            'jar.path' = $artifact
            'jar.sha256' = $artifactHash
            'authorization.file' = 'NONE'
            'm3.dataset.contract' = 'M1_RESEARCH_DATASET_V1'
            'm3.strategy.engine' = 'STRATEGY_ENGINE_V1'
            'm3.backtest.engine' = 'BACKTEST_ENGINE_V1'
            'm3.research.api' = 'STRATEGY_RESEARCH_API_V1'
            'm3.agent.runtime' = 'AGENT_RUNTIME_V1'
            'm3.agent.team' = 'AGENT_RESEARCH_TEAM_V1'
            'm3.tool.gateway' = 'AGENT_TOOL_GATEWAY_V1'
            'm3.agent.eval' = 'AGENT_EVAL_V1'
            'm3.research.report' = 'RESEARCH_REPORT_V1'
            'securities' = '600000:SSE,000001:SZSE'
            'range.start' = '2025-01-02'
            'range.end' = '2025-01-10'
            'anchor.trade.date' = '2025-01-10'
            'database.host' = '127.0.0.1'
            'database.port' = '38432'
            'database.name' = 'stock_quant_research'
            'database.user' = 'stock_quant_research'
            'schema.name' = 'tushare_research'
            'database.read.only' = 'true'
            'provider' = 'BAILIAN'
            'model' = 'qwen3.7-plus'
            'provider.endpoint' =
                'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'
            'maximum.model.calls' = '13'
            'maximum.output.tokens.per.call' = '900'
            'maximum.cost.cny' = '5.00'
            'retry.budget' = '0'
            'redirects' = 'NEVER'
            'user.approval.reference' =
                'USER_APPROVED_M3_BAILIAN_SMOKE_TRANCHE_2_CNY_5_00'
            'created.at' = $createdAt.ToString('o')
            'expires.at' = $expiresAt.ToString('o')
            'execution.source' = 'M3_AGENT_RESEARCH_REAL_LLM_SMOKE'
            'no.retry' = 'true'
            'source.request.id' = $SourceRequestId
        }
    } elseif ($Operation -eq 'RUN_M4_SHADOW_RESEARCH') {
        $chinaZone = [TimeZoneInfo]::FindSystemTimeZoneById(
            'China Standard Time')
        $chinaNow = [TimeZoneInfo]::ConvertTime(
            [DateTimeOffset]::UtcNow, $chinaZone)
        $resolvedM4TradeDate = if ($TradeDate -eq 'AUTO') {
            $chinaNow.Date
        } else {
            [DateTime]::ParseExact($TradeDate, 'yyyy-MM-dd',
                [Globalization.CultureInfo]::InvariantCulture)
        }
        while ($TradeDate -eq 'AUTO' -and $resolvedM4TradeDate.DayOfWeek -in @(
                [DayOfWeek]::Saturday, [DayOfWeek]::Sunday)) {
            $resolvedM4TradeDate = $resolvedM4TradeDate.AddDays(-1)
        }
        if ($resolvedM4TradeDate.Date -gt $chinaNow.Date) {
            throw 'M4_FUTURE_TRADE_DATE_FORBIDDEN'
        }
        $m4ControlledManual = $ShadowDispatchMode -eq 'CONTROLLED_MANUAL'
        if ($m4ControlledManual -and ($TradeDate -eq 'AUTO' -or
                $resolvedM4TradeDate.Date -ge $chinaNow.Date)) {
            throw 'M6_CONTROLLED_MANUAL_TRADE_DATE_INVALID'
        }
        $m4HistoricalReplay = -not $m4ControlledManual -and
            $resolvedM4TradeDate.Date -lt $chinaNow.Date
        if (-not $m4HistoricalReplay -and -not $m4ControlledManual -and
            $chinaNow.TimeOfDay -lt [TimeSpan]::FromHours(15)) {
            throw 'M4_MARKET_CLOSE_NOT_AVAILABLE'
        }
        $m4TriggerMode = if ($m4ControlledManual) {
            'MANUAL'
        } elseif ($m4HistoricalReplay) {
            'HISTORICAL_REPLAY'
        } else { 'SCHEDULED' }
        $m4ApprovalReference = if ($m4ControlledManual -or
                $m4HistoricalReplay) {
            'USER_APPROVED_M6_CONTROLLED_SHADOW_SMOKE'
        } else { 'USER_APPROVED_M4_CONTINUOUS_SHADOW_MONTHLY' }
        $m4ExecutionSource = if ($m4ControlledManual) {
            'M6_RESEARCH_PRODUCTION_CONTROLLED_MANUAL'
        } elseif ($m4HistoricalReplay) {
            'M6_RESEARCH_PRODUCTION_CONTROLLED_REPLAY'
        } else { 'M4_SHADOW_RESEARCH_CONTINUOUS_SCHEDULED' }
        $rangeStart = $resolvedM4TradeDate.AddDays(-30)
        $calendarHorizonEnd = $resolvedM4TradeDate.AddDays(30)
        $m4ProviderRequests = if ($CalendarAdmission -eq 'UNKNOWN') {
            8
        } else { 6 }
        $m4CalendarRequests = if ($CalendarAdmission -eq 'UNKNOWN') {
            4
        } else { 2 }
        $calendarMonth = $chinaNow.ToString('yyyy-MM')
        $usage = Get-StockQuantM4MonthlyUsage `
            -CalendarMonth $calendarMonth
        [decimal]$shadowCost = [decimal]::Parse(
            [string]$usage.ShadowCostCny,
            [Globalization.NumberStyles]::Number,
            [Globalization.CultureInfo]::InvariantCulture)
        [decimal]$projectCost = [decimal]::Parse(
            [string]$usage.ProjectCostCny,
            [Globalization.NumberStyles]::Number,
            [Globalization.CultureInfo]::InvariantCulture)
        [decimal]$remainingCost = [decimal]5.00
        if ([decimal]30.00 - $shadowCost -lt $remainingCost) {
            $remainingCost = [decimal]30.00 - $shadowCost
        }
        if ([decimal]200.00 - $projectCost -lt $remainingCost) {
            $remainingCost = [decimal]200.00 - $projectCost
        }
        if ($remainingCost -le 0 -or
            [int]$usage.TushareCalls + $m4ProviderRequests -gt 150) {
            throw 'M4_MONTHLY_BUDGET_EXHAUSTED'
        }
        $requestValues = [ordered]@{
            'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
            'request.id' = $requestId
            'operation' = $Operation
            'git.commit' = $head
            'jar.path' = $artifact
            'jar.sha256' = $artifactHash
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
            'range.start' = $rangeStart.ToString('yyyy-MM-dd')
            'trade.date' = $resolvedM4TradeDate.ToString('yyyy-MM-dd')
            # The fixed runner resolves the next common SSE/SZSE open date
            # only from captured PIT calendar facts.  Requests cannot inject
            # a dynamic execution date.
            'next.trade.date' = 'INTERNAL_CALENDAR'
            'calendar.admission' = $CalendarAdmission
            'calendar.horizon.end' =
                $calendarHorizonEnd.ToString('yyyy-MM-dd')
            'capture.mode' = 'CAPTURE_OR_IDEMPOTENT'
            'trigger.mode' = $m4TriggerMode
            'database.host' = '127.0.0.1'
            'database.port' = '38432'
            'database.name' = 'stock_quant_research'
            'database.user' = 'stock_quant_research'
            'schema.name' = 'tushare_research'
            'tushare.provider' = 'TUSHARE'
            'tushare.endpoints' = 'daily,adj_factor,trade_cal'
            'endpoint.daily.requests' = '2'
            'endpoint.adj_factor.requests' = '2'
            'endpoint.trade_cal.requests' = [string]$m4CalendarRequests
            'maximum.provider.requests' = [string]$m4ProviderRequests
            'budget.calendar.month' = $calendarMonth
            'tushare.monthly.limit' = '150'
            'tushare.monthly.calls.before' =
                [string]$usage.TushareCalls
            'llm.provider' = 'BAILIAN'
            'model' = 'qwen3.7-plus'
            'provider.endpoint' =
                'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'
            'maximum.model.calls' = '13'
            'maximum.output.tokens.per.call' = '900'
            'maximum.cost.cny' = $remainingCost.ToString(
                [Globalization.CultureInfo]::InvariantCulture)
            'llm.monthly.limit.cny' = '30.00'
            'llm.monthly.cost.before.cny' =
                [string]$usage.ShadowCostCny
            'project.monthly.limit.cny' = '200.00'
            'project.monthly.cost.before.cny' =
                [string]$usage.ProjectCostCny
            'retry.budget' = '0'
            'redirects' = 'NEVER'
            'user.approval.reference' = $m4ApprovalReference
            'created.at' = $createdAt.ToString('o')
            'expires.at' = $expiresAt.ToString('o')
            'execution.source' = $m4ExecutionSource
            'no.retry' = 'true'
            'source.request.id' = 'NONE'
        }
    } elseif ($Operation -eq 'RUN_M2_STRATEGY_RESEARCH_SMOKE') {
        $requestValues = [ordered]@{
            'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
            'request.id' = $requestId
            'operation' = $Operation
            'git.commit' = $head
            'jar.path' = $artifact
            'jar.sha256' = $artifactHash
            'authorization.file' = 'NONE'
            'm2.dataset.contract' = 'M1_RESEARCH_DATASET_V1'
            'm2.strategy.engine' = 'STRATEGY_ENGINE_V1'
            'm2.backtest.engine' = 'BACKTEST_ENGINE_V1'
            'm2.research.api' = 'STRATEGY_RESEARCH_API_V1'
            'securities' = '600000:SSE,000001:SZSE'
            'range.start' = '2025-01-02'
            'range.end' = '2025-01-10'
            'anchor.trade.date' = '2025-01-10'
            'database.host' = '127.0.0.1'
            'database.port' = '38432'
            'database.name' = 'stock_quant_research'
            'database.user' = 'stock_quant_research'
            'schema.name' = 'tushare_research'
            'database.read.only' = 'true'
            'provider' = 'NONE'
            'provider.endpoints' = 'NONE'
            'maximum.provider.requests' = '0'
            'retry.budget' = '0'
            'redirects' = 'NEVER'
            'created.at' = $createdAt.ToString('o')
            'expires.at' = $expiresAt.ToString('o')
            'execution.source' = 'M2_STRATEGY_RESEARCH_READ_ONLY'
            'no.retry' = 'true'
            'source.request.id' = 'NONE'
        }
    } elseif ($Operation -eq 'RUN_M1_RESEARCH_DATA') {
        $authorizationValues = Read-StrictStockQuantProperties `
            -Path $authorization
        $requestValues = [ordered]@{
            'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
            'request.id' = $requestId
            'operation' = $Operation
            'git.commit' = $head
            'jar.path' = $artifact
            'jar.sha256' = $artifactHash
            'authorization.file' = $authorization
            'm1.mode' = [string]$authorizationValues['mode']
            'securities' = [string]$authorizationValues['securities']
            'range.start' = [string]$authorizationValues['range.start']
            'range.end' = [string]$authorizationValues['range.end']
            'anchor.trade.date' =
                [string]$authorizationValues['anchor.trade.date']
            'database.host' = '127.0.0.1'
            'database.port' = '38432'
            'database.name' = 'stock_quant_research'
            'database.user' = 'stock_quant_research'
            'schema.name' = 'tushare_research'
            'provider' = 'TUSHARE'
            'provider.endpoints' = 'daily,adj_factor,trade_cal'
            'endpoint.daily.requests' = '2'
            'endpoint.adj_factor.requests' = '2'
            'endpoint.trade_cal.requests' = '2'
            'maximum.provider.requests' = '6'
            'retry.budget' = '0'
            'redirects' = 'NEVER'
            'provider.historical.baseline' = '34'
            'provider.stage.limit' = '30'
            'provider.cumulative.limit' = '64'
            'provider.stage.calls.before' =
                [string]$authorizationValues['provider.stage.calls.before']
            'created.at' = $createdAt.ToString('o')
            'expires.at' = $expiresAt.ToString('o')
            'execution.source' = 'M1_RESEARCH_DATA_MANUAL'
            'no.retry' = 'true'
            'source.request.id' = 'NONE'
        }
    } elseif ($Operation -eq 'VERIFY_M1_TUSHARE_TOKEN') {
        $authorizationValues = Read-StrictStockQuantProperties `
            -Path $authorization
        $requestValues = [ordered]@{
            'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
            'request.id' = $requestId
            'operation' = $Operation
            'git.commit' = $head
            'jar.path' = $artifact
            'jar.sha256' = $artifactHash
            'authorization.file' = $authorization
            'security.symbol' = '600000'
            'security.exchange' = 'SSE'
            'trade.date' = '2025-01-03'
            'provider' = 'TUSHARE'
            'provider.endpoints' = 'daily'
            'endpoint.daily.requests' = '1'
            'maximum.provider.requests' = '1'
            'retry.budget' = '0'
            'redirects' = 'NEVER'
            'provider.historical.baseline' = '34'
            'provider.stage.limit' = '30'
            'provider.cumulative.limit' = '64'
            'provider.stage.calls.before' =
                [string]$authorizationValues['provider.stage.calls.before']
            'created.at' = $createdAt.ToString('o')
            'expires.at' = $expiresAt.ToString('o')
            'execution.source' =
                'M1_TUSHARE_TOKEN_VERIFICATION_MANUAL'
            'no.retry' = 'true'
            'source.request.id' = 'NONE'
        }
    } else {
        $requestValues = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = $requestId
        'operation' = $Operation
        'git.commit' = $head
        'jar.path' = $artifact
        'jar.sha256' = $artifactHash
        'authorization.file' = $authorization
        'day001.mode' = 'IDEMPOTENCY_VERIFICATION'
        'security.symbol' = '600000'
        'security.exchange' = 'SSE'
        'trade.date' = '2025-01-03'
        'database.host' = '127.0.0.1'
        'database.port' = '38432'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'schema.name' = 'tushare_research'
        'provider' = 'TUSHARE'
        'provider.endpoints' = 'daily,adj_factor,trade_cal'
        'endpoint.daily.requests' = '1'
        'endpoint.adj_factor.requests' = '1'
        'endpoint.trade_cal.requests' = '1'
        'maximum.provider.requests' = '3'
        'retry.budget' = '0'
        'redirects' = 'NEVER'
        'created.at' = $createdAt.ToString('o')
        'expires.at' = $expiresAt.ToString('o')
        'execution.source' = 'REDUCED_RESEARCH_MANUAL_DAY001'
        'no.retry' = 'true'
        'source.request.id' = $SourceRequestId
        }
    }
    $heartbeat = Read-StockQuantHostBrokerHeartbeat `
        -ExpectedGitCommit $head -AllowAncestorGitCommit
    Assert-StockQuantHostBrokerInvoker -Heartbeat $heartbeat
    $requestFile = Write-StockQuantHostBrokerRequest -Values $requestValues

    if ($SubmitOnly) {
        Write-Output "STOCK_QUANT_HOST_BROKER_REQUEST_ID=$requestId"
        Write-Output "STOCK_QUANT_HOST_BROKER_REQUEST=$requestFile"
        Write-Output 'STOCK_QUANT_HOST_BROKER_STATUS=SUBMITTED'
        exit 0
    }

    $resultPath = Join-Path $paths.Results "$requestId.result.json"
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
        if ([DateTimeOffset]::UtcNow -ge $deadline) {
            throw 'STOCK_QUANT_HOST_BROKER_RESULT_TIMEOUT'
        }
        Start-Sleep -Milliseconds 250
    }
    $result = Get-Content -LiteralPath $resultPath -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($result.schemaVersion -ne 'STOCK_QUANT_HOST_BROKER_RESULT_V1' -or
        $result.requestId -ne $requestId -or
        $result.operation -ne $Operation -or
        $result.status -notin @('SUCCEEDED', 'FAILED', 'REJECTED')) {
        throw 'STOCK_QUANT_HOST_BROKER_RESULT_INVALID'
    }
    Write-Output "STOCK_QUANT_HOST_BROKER_REQUEST_ID=$requestId"
    Write-Output "STOCK_QUANT_HOST_BROKER_REQUEST=$requestFile"
    Write-Output "STOCK_QUANT_HOST_BROKER_RESULT=$resultPath"
    Write-Output "STOCK_QUANT_HOST_BROKER_STATUS=$($result.status)"
    Write-Output "STOCK_QUANT_HOST_BROKER_STAGE=$($result.stage)"
    Write-Output "STOCK_QUANT_HOST_BROKER_REASON=$($result.reason)"
    if ($result.status -ne 'SUCCEEDED') { exit 20 }
    exit 0
} finally {
    Pop-Location
}
