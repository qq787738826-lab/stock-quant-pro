[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
$paths = Initialize-StockQuantHostBrokerDirectories
$root = Join-Path $paths.TargetRoot `
    ('stock-quant-selection-protocol-' + [Guid]::NewGuid().ToString('N'))
$artifact = Join-Path $root 'research-selection-protocol-test.jar'
$tests = 0
$ledgerFiles = @()

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
        throw "SELECTION_EXPECTED_REJECTION_MISSING_$Case"
    } catch {
        if ($_.Exception.Message -ne $Reason) { throw }
    }
    $script:tests++
}

try {
    New-Item -ItemType Directory -Path $root | Out-Null
    [IO.File]::WriteAllBytes($artifact, [byte[]](1, 0, 1, 25, 10, 5))
    $hash = ((Get-FileHash $artifact -Algorithm SHA256).Hash
        ).ToLowerInvariant()
    $created = [DateTimeOffset]::UtcNow
    $china = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId(
        $created, 'China Standard Time')
    $publicRun = 'SELECT_' + $created.ToString('yyyyMMddTHHmmssZ') + '_' +
        ([Guid]::NewGuid().ToString('N').Substring(0, 12)).ToUpperInvariant()
    $request = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = New-StockQuantHostBrokerRequestId
        'operation' = 'RUN_RESEARCH_SELECTION'
        'git.commit' = (git -C $paths.RepositoryRoot rev-parse HEAD).Trim()
        'jar.path' = $artifact
        'jar.sha256' = $hash
        'authorization.file' = 'NONE'
        'selection.run.id' = '101'
        'selection.public.run.id' = $publicRun
        'selection.trigger' = 'ON_DEMAND'
        'selection.universe.version' = 'RESEARCH_UNIVERSE_MAINBOARD_V1'
        'selection.primary.window' = '20'
        'selection.auxiliary.window' = '60'
        'selection.shortlist.limit' = '10'
        'selection.final.limit' = '5'
        'selection.paper.enabled' = 'true'
        'database.host' = '127.0.0.1'
        'database.port' = '38432'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'schema.name' = 'tushare_research'
        'tushare.provider' = 'TUSHARE'
        'tushare.endpoints' = 'stock_basic,daily,adj_factor,trade_cal'
        'endpoint.stock_basic.requests' = '0'
        'endpoint.daily.requests' = '1'
        'endpoint.adj_factor.requests' = '1'
        'endpoint.trade_cal.requests' = '0'
        'maximum.provider.requests' = '2'
        'budget.calendar.month' = $china.ToString('yyyy-MM')
        'tushare.monthly.limit' = '150'
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
        'project.monthly.cost.before.cny' = '0.00'
        'retry.budget' = '0'
        'redirects' = 'NEVER'
        'user.approval.reference' =
            'USER_APPROVED_STOCK_QUANT_PRO_V1_MONTHLY'
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'CURRENT_AS_OF_RESEARCH_SELECTION'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    [IO.File]::WriteAllText("$artifact.f1f-b2-proof.properties", (@(
        "git.commit=$($request['git.commit'])"
        "artifact.sha256=$hash"
        'build.mode=RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT'
    ) -join "`n") + "`n", [Text.UTF8Encoding]::new($false))

    foreach ($maximum in @(0, 2, 121)) {
        $value = Copy-Values $request
        $value['request.id'] = New-StockQuantHostBrokerRequestId
        $value['maximum.provider.requests'] = [string]$maximum
        $value['endpoint.stock_basic.requests'] = [string]($maximum % 2)
        $value['endpoint.daily.requests'] = [string][math]::Floor(
            $maximum / 2)
        $value['endpoint.adj_factor.requests'] = [string][math]::Floor(
            $maximum / 2)
        $parsed = Read-Valid $value
        if ($parsed.Operation -ne 'RUN_RESEARCH_SELECTION' -or
            $parsed.AuthorizationStatus -ne
                'STOCK_QUANT_PRO_V1_MONTHLY_APPROVED' -or
            [int]$parsed.Values['maximum.provider.requests'] -ne $maximum) {
            throw 'RESEARCH_SELECTION_PROTOCOL_VALID_REQUEST_REJECTED'
        }
        # Read-Valid owns and removes its path, so exercise the declared
        # operation parser through a fresh non-claimable processing file.
        $declaredPath = Join-Path $paths.Requests `
            "$($value['request.id']).processing.properties"
        Write-Lines $declaredPath $value
        try {
            $declared = Get-StockQuantHostBrokerDeclaredOperation `
                -Path $declaredPath
            if ($declared -ne 'RUN_RESEARCH_SELECTION') {
                throw 'RESEARCH_SELECTION_DECLARED_OPERATION_INVALID'
            }
        } finally {
            Remove-Item -LiteralPath $declaredPath -Force `
                -ErrorAction SilentlyContinue
        }
        $tests++
    }

    $scheduled = Copy-Values $request
    $scheduled['request.id'] = New-StockQuantHostBrokerRequestId
    $scheduled['selection.trigger'] = 'SCHEDULED_SHADOW'
    $scheduledParsed = Read-Valid $scheduled
    if ($scheduledParsed.Operation -ne 'RUN_RESEARCH_SELECTION' -or
        $scheduledParsed.Values['selection.trigger'] -ne
            'SCHEDULED_SHADOW' -or
        $scheduledParsed.AuthorizationStatus -ne
            'STOCK_QUANT_PRO_V1_MONTHLY_APPROVED') {
        throw 'RESEARCH_SELECTION_SCHEDULED_REQUEST_REJECTED'
    }
    $brokerSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot `
        'stock-quant-host-broker.ps1') -Raw -Encoding UTF8
    if (-not $brokerSource.Contains(
            "-SelectionTrigger `$BrokerRequest.Values['selection.trigger']")) {
        throw 'RESEARCH_SELECTION_SCHEDULED_TRIGGER_NOT_BOUND_TO_RUNNER'
    }
    $tests++

    $protocolSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot `
        'StockQuantHostBroker.Protocol.psm1') -Raw -Encoding UTF8
    $prePublishValidation = $protocolSource.IndexOf(
        'Read-StockQuantHostBrokerRequest -Path $temporary')
    $atomicPublish = $protocolSource.IndexOf(
        '[IO.File]::Move($temporary, $destination)')
    if ($prePublishValidation -lt 0 -or
        $atomicPublish -le $prePublishValidation -or
        $protocolSource.Contains(
            'Read-StockQuantHostBrokerRequest -Path $destination')) {
        throw 'RESEARCH_SELECTION_REQUEST_PUBLICATION_RACE_NOT_CLOSED'
    }
    $tests++

    foreach ($mutation in @(
            @('maximum.provider.requests', '51',
                'INVALID_PROVIDER_BUDGET'),
            @('selection.universe.version', 'DYNAMIC_UNIVERSE',
                'DYNAMIC_UNIVERSE'),
            @('selection.shortlist.limit', '25', 'UNBOUNDED_AGENT_SCOPE'),
            @('retry.budget', '1', 'RETRY_ENABLED'),
            @('redirects', 'FOLLOW', 'REDIRECT_ENABLED'),
            @('model', 'dynamic-model', 'DYNAMIC_MODEL'),
            @('tushare.monthly.calls.before', '149', 'TUSHARE_OVER_BUDGET'),
            @('llm.monthly.cost.before.cny', '29.00', 'LLM_OVER_BUDGET'),
            @('project.monthly.cost.before.cny', '199.00',
                'PROJECT_OVER_BUDGET'))) {
        $invalid = Copy-Values $request
        $invalid['request.id'] = New-StockQuantHostBrokerRequestId
        $invalid[$mutation[0]] = $mutation[1]
        Reject $invalid 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' `
            $mutation[2]
    }

    $command = Copy-Values $request
    $command['request.id'] = New-StockQuantHostBrokerRequestId
    $command['command.text'] = 'forbidden'
    Reject $command 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID' `
        'COMMAND_INJECTION'

    $outside = Copy-Values $request
    $outside['request.id'] = New-StockQuantHostBrokerRequestId
    $outside['jar.path'] =
        (Resolve-Path (Join-Path $paths.RepositoryRoot 'README.md')).Path
    $outside['jar.sha256'] = ((Get-FileHash $outside['jar.path'] `
        -Algorithm SHA256).Hash).ToLowerInvariant()
    Reject $outside 'STOCK_QUANT_HOST_BROKER_JAR_PATH_INVALID' `
        'PATH_ESCAPE'

    $ledgerId = 'SQHB_20990203T010203Z_A1B2C3D4E5F6'
    $ledgerRequest = Join-Path $paths.Requests `
        "$ledgerId.processed.properties"
    $ledgerResult = Join-Path $paths.Results `
        "$ledgerId.research-selection.json"
    Write-Lines $ledgerRequest ([ordered]@{
        'operation' = 'RUN_RESEARCH_SELECTION'
        'request.id' = $ledgerId
        'created.at' = '2099-02-03T01:02:03Z'
        'maximum.provider.requests' = '2'
        'selection.run.id' = '202'
        'selection.universe.version' =
            'RESEARCH_UNIVERSE_MAINBOARD_V1'
        'selection.public.run.id' =
            'SELECT_20990203T010203Z_A1B2C3D4E5F6'
    })
    $runner = [ordered]@{
        schemaVersion = 'RESEARCH_SELECTION_RUNNER_RESULT_V1'
        status = 'SUCCEEDED'
        executionId = ($ledgerId -replace '^SQHB_', 'SELECTEXEC_')
        selectionRunId = 202
        publicRunId = 'SELECT_20990203T010203Z_A1B2C3D4E5F6'
        universeSize = 3000
        shortlistSize = 10
        tushareProviderCallCount = 2
        retryCount = 0
        modelProviderRequestCount = 13
        modelCallCount = 13
        conservativeCostCny = '0.75'
        outputAuditClean = $true
    }
    [IO.File]::WriteAllText($ledgerResult,
        ($runner | ConvertTo-Json -Compress) + "`n",
        [Text.UTF8Encoding]::new($false))
    $ledgerFiles += $ledgerRequest, $ledgerResult
    $usage = Get-StockQuantM4MonthlyUsage -CalendarMonth '2099-02'
    if ([int]$usage.RequestCount -ne 1 -or
        [int]$usage.TushareCalls -ne 2 -or
        [decimal]$usage.ShadowCostCny -ne [decimal]0.75 -or
        [decimal]$usage.ProjectCostCny -ne [decimal]0.75) {
        throw 'RESEARCH_SELECTION_MONTHLY_LEDGER_INVALID'
    }
    $tests++

    $oldUniverseId = 'SQHB_20990503T010203Z_D1E2F3A4B5C6'
    $oldUniverseRequest = Join-Path $paths.Requests `
        "$oldUniverseId.processed.properties"
    $oldUniverseResult = Join-Path $paths.Results `
        "$oldUniverseId.research-selection.json"
    Write-Lines $oldUniverseRequest ([ordered]@{
        'operation' = 'RUN_RESEARCH_SELECTION'
        'request.id' = $oldUniverseId
        'created.at' = '2099-05-03T01:02:03Z'
        'maximum.provider.requests' = '2'
        'selection.run.id' = '505'
        'selection.universe.version' = 'RESEARCH_UNIVERSE_V1'
        'selection.public.run.id' =
            'SELECT_20990503T010203Z_D1E2F3A4B5C6'
    })
    $oldUniverseRunner = [ordered]@{
        schemaVersion = 'RESEARCH_SELECTION_RUNNER_RESULT_V1'
        status = 'SUCCEEDED'
        executionId = ($oldUniverseId -replace '^SQHB_', 'SELECTEXEC_')
        selectionRunId = 505
        publicRunId = 'SELECT_20990503T010203Z_D1E2F3A4B5C6'
        universeSize = 25
        shortlistSize = 10
        tushareProviderCallCount = 2
        retryCount = 0
        modelProviderRequestCount = 13
        modelCallCount = 13
        conservativeCostCny = '0.75'
        outputAuditClean = $true
    }
    [IO.File]::WriteAllText($oldUniverseResult,
        ($oldUniverseRunner | ConvertTo-Json -Compress) + "`n",
        [Text.UTF8Encoding]::new($false))
    $ledgerFiles += $oldUniverseRequest, $oldUniverseResult
    $oldUniverseUsage = Get-StockQuantM4MonthlyUsage `
        -CalendarMonth '2099-05'
    if ([int]$oldUniverseUsage.RequestCount -ne 1 -or
        [int]$oldUniverseUsage.TushareCalls -ne 2 -or
        [decimal]$oldUniverseUsage.ShadowCostCny -ne [decimal]0.75) {
        throw 'RESEARCH_SELECTION_OLD_UNIVERSE_LEDGER_INVALID'
    }
    $tests++

    $pendingId = 'SQHB_20990303T010203Z_B1C2D3E4F5A6'
    # Keep the synthetic reservation non-claimable while the resident Broker
    # is watching the real request directory.
    $pendingRequest = Join-Path $paths.Requests `
        "$pendingId.processing.properties"
    Write-Lines $pendingRequest ([ordered]@{
        'operation' = 'RUN_RESEARCH_SELECTION'
        'request.id' = $pendingId
        'created.at' = '2099-03-03T01:02:03Z'
        'maximum.provider.requests' = '2'
        'maximum.cost.cny' = '5.00'
    })
    $ledgerFiles += $pendingRequest
    $reserved = Get-StockQuantM4MonthlyUsage -CalendarMonth '2099-03'
    if ([int]$reserved.RequestCount -ne 1 -or
        [int]$reserved.TushareCalls -ne 0 -or
        [int]$reserved.ReservedTushareCalls -ne 2 -or
        [int]$reserved.CommittedTushareCalls -ne 2 -or
        [decimal]$reserved.ShadowCostCny -ne [decimal]0 -or
        [decimal]$reserved.ReservedShadowCostCny -ne [decimal]5 -or
        [decimal]$reserved.CommittedShadowCostCny -ne [decimal]5 -or
        [decimal]$reserved.CommittedProjectCostCny -ne [decimal]5) {
        throw 'RESEARCH_SELECTION_MONTHLY_RESERVATION_INVALID'
    }
    $tests++

    $legacyId = 'SQHB_20990403T010203Z_C1D2E3F4A5B6'
    $legacyRequest = Join-Path $paths.Requests `
        "$legacyId.processed.properties"
    $legacyTerminal = Join-Path $paths.Results "$legacyId.result.json"
    Write-Lines $legacyRequest ([ordered]@{
        'operation' = 'RUN_RESEARCH_SELECTION'
        'request.id' = $legacyId
        'created.at' = '2099-04-03T01:02:03Z'
        'maximum.provider.requests' = '2'
        'selection.run.id' = '303'
        'selection.public.run.id' =
            'SELECT_20990403T010203Z_C1D2E3F4A5B6'
    })
    $legacyResult = [ordered]@{
        schemaVersion = 'STOCK_QUANT_HOST_BROKER_RESULT_V1'
        requestId = $legacyId
        operation = 'UNKNOWN'
        status = 'REJECTED'
        stage = 'REQUEST_VALIDATION'
        reason = 'STOCK_QUANT_HOST_BROKER_BUILD_PROOF_BINDING_INVALID'
        providerCallCount = 0
        retryCount = 0
    }
    [IO.File]::WriteAllText($legacyTerminal,
        ($legacyResult | ConvertTo-Json -Compress) + "`n",
        [Text.UTF8Encoding]::new($false))
    $ledgerFiles += $legacyRequest, $legacyTerminal
    $legacyUsage = Get-StockQuantM4MonthlyUsage -CalendarMonth '2099-04'
    if ([int]$legacyUsage.RequestCount -ne 1 -or
        [int]$legacyUsage.TushareCalls -ne 0 -or
        [decimal]$legacyUsage.ShadowCostCny -ne [decimal]0) {
        throw 'RESEARCH_SELECTION_LEGACY_REJECTION_LEDGER_INVALID'
    }
    $tests++

    $legacyResult.reason = 'STOCK_QUANT_HOST_BROKER_FAILED'
    [IO.File]::WriteAllText($legacyTerminal,
        ($legacyResult | ConvertTo-Json -Compress) + "`n",
        [Text.UTF8Encoding]::new($false))
    try {
        Get-StockQuantM4MonthlyUsage -CalendarMonth '2099-04' | Out-Null
        throw 'RESEARCH_SELECTION_UNSAFE_OPERATION_MISMATCH_ACCEPTED'
    } catch {
        if ($_.Exception.Message -ne 'M4_MONTHLY_BUDGET_LEDGER_INVALID') {
            throw
        }
    }
    $legacyResult.reason =
        'STOCK_QUANT_HOST_BROKER_BUILD_PROOF_BINDING_INVALID'
    [IO.File]::WriteAllText($legacyTerminal,
        ($legacyResult | ConvertTo-Json -Compress) + "`n",
        [Text.UTF8Encoding]::new($false))
    $tests++

    Write-Output "RESEARCH_SELECTION_BROKER_PROTOCOL_TESTS=$tests"
    Write-Output 'RESEARCH_SELECTION_BROKER_PROVIDER_CALLS=0'
    Write-Output 'RESEARCH_SELECTION_BROKER_PERMANENT_DATABASE_WRITES=0'
    Write-Output 'RESEARCH_SELECTION_BROKER_PROTOCOL_STATUS=PASS'
} finally {
    foreach ($file in $ledgerFiles) {
        Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
