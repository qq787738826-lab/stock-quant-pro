[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
$paths = Initialize-StockQuantHostBrokerDirectories
$prefix = 'stock-quant-m3-protocol-'
$root = Join-Path $paths.TargetRoot ($prefix + [Guid]::NewGuid().ToString('N'))
$artifact = Join-Path $root 'm3-protocol-test.jar'
$proof = "$artifact.f1f-b2-proof.properties"
$processing = $null
$credentialResultPath = $null
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
    finally {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }
}

function Expect-Rejection(
    [System.Collections.IDictionary] $Values,
    [string] $Expected
) {
    $path = Join-Path $paths.Requests `
        "$($Values['request.id']).processing.properties"
    Write-Lines $path $Values
    try {
        try {
            Read-StockQuantHostBrokerRequest -Path $path | Out-Null
            throw 'M3_PROTOCOL_EXPECTED_REJECTION_MISSING'
        } catch {
            if ($_.Exception.Message -ne $Expected) { throw }
        }
    } finally {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }
    $script:tests++
}

try {
    New-Item -ItemType Directory -Path $root | Out-Null
    [IO.File]::WriteAllBytes($artifact, [byte[]](1, 2, 3, 4))
    [IO.File]::WriteAllText($proof, "test=true`n",
        [Text.UTF8Encoding]::new($false))
    $hash = ((Get-FileHash $artifact -Algorithm SHA256).Hash).ToLowerInvariant()
    $head = (git -C $paths.RepositoryRoot rev-parse HEAD).Trim()
    $created = [DateTimeOffset]::UtcNow

    $credentialId = New-StockQuantHostBrokerRequestId
    $credential = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = $credentialId
        'operation' = 'CHECK_BAILIAN_CREDENTIAL_STATUS'
        'git.commit' = $head
        'jar.path' = $artifact
        'jar.sha256' = $hash
        'authorization.file' = 'NONE'
        'provider' = 'BAILIAN'
        'model' = 'qwen3.7-plus'
        'provider.endpoint' = 'NONE'
        'maximum.model.calls' = '0'
        'maximum.cost.cny' = '0.00'
        'retry.budget' = '0'
        'redirects' = 'NEVER'
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'M3_BAILIAN_CREDENTIAL_READABILITY_CHECK'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    $parsedCredential = Read-Valid $credential
    if ($parsedCredential.Operation -ne 'CHECK_BAILIAN_CREDENTIAL_STATUS' -or
        $parsedCredential.AuthorizationStatus -ne
            'M3_BAILIAN_CREDENTIAL_READABILITY_ZERO_NETWORK' -or
        $null -ne $parsedCredential.AuthorizationFile) {
        throw 'M3_PROTOCOL_VALID_CREDENTIAL_REQUEST_REJECTED'
    }
    $tests++
    $credentialResultPath = Join-Path $paths.Results `
        "$credentialId.result.json"
    $credentialResult = [ordered]@{
        schemaVersion = 'STOCK_QUANT_HOST_BROKER_RESULT_V1'
        requestId = $credentialId
        operation = 'CHECK_BAILIAN_CREDENTIAL_STATUS'
        status = 'SUCCEEDED'
        stage = 'COMPLETED'
        reason = 'STOCK_QUANT_HOST_BROKER_SUCCEEDED'
        gitCommit = $head
        providerCallCount = 0
        retryCount = 0
        noRetry = $true
        startedAt = $created.ToString('o')
        completedAt = [DateTimeOffset]::UtcNow.ToString('o')
        summary = [ordered]@{
            credentialReady = $true
            readStatus = 'SUCCESS'
            networkCallCount = 0
            providerCallCount = 0
            retryCount = 0
            outputAudit = 'PASSED'
        }
    }
    [IO.File]::WriteAllText($credentialResultPath,
        (($credentialResult | ConvertTo-Json -Depth 5) + "`n"),
        [Text.UTF8Encoding]::new($false))

    $smoke = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = New-StockQuantHostBrokerRequestId
        'operation' = 'RUN_M3_AGENT_RESEARCH_SMOKE'
        'git.commit' = $head
        'jar.path' = $artifact
        'jar.sha256' = $hash
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
        'maximum.output.tokens.per.call' = '600'
        'maximum.cost.cny' = '5.00'
        'retry.budget' = '0'
        'redirects' = 'NEVER'
        'user.approval.reference' =
            'USER_APPROVED_M3_BAILIAN_SMOKE_CNY_5_00'
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'M3_AGENT_RESEARCH_REAL_LLM_SMOKE'
        'no.retry' = 'true'
        'source.request.id' = $credentialId
    }
    $parsedSmoke = Read-Valid $smoke
    if ($parsedSmoke.Operation -ne 'RUN_M3_AGENT_RESEARCH_SMOKE' -or
        $parsedSmoke.AuthorizationStatus -ne
            'M3_USER_APPROVED_BAILIAN_SMOKE_CNY_5_00' -or
        $null -ne $parsedSmoke.AuthorizationFile) {
        throw 'M3_PROTOCOL_VALID_SMOKE_REQUEST_REJECTED'
    }
    $tests++

    $missingCredentialSource = Copy-Values $smoke
    $missingCredentialSource['request.id'] =
        New-StockQuantHostBrokerRequestId
    $missingCredentialSource['source.request.id'] =
        New-StockQuantHostBrokerRequestId
    Expect-Rejection $missingCredentialSource `
        'STOCK_QUANT_HOST_BROKER_M3_CREDENTIAL_SOURCE_INVALID'

    $wrongModel = Copy-Values $smoke
    $wrongModel['request.id'] = New-StockQuantHostBrokerRequestId
    $wrongModel['model'] = 'gpt-5-mini'
    Expect-Rejection $wrongModel `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'

    $wrongEndpoint = Copy-Values $smoke
    $wrongEndpoint['request.id'] = New-StockQuantHostBrokerRequestId
    $wrongEndpoint['provider.endpoint'] = 'https://example.invalid'
    Expect-Rejection $wrongEndpoint `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'

    $higherCost = Copy-Values $smoke
    $higherCost['request.id'] = New-StockQuantHostBrokerRequestId
    $higherCost['maximum.cost.cny'] = '5.01'
    Expect-Rejection $higherCost `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'

    $retry = Copy-Values $smoke
    $retry['request.id'] = New-StockQuantHostBrokerRequestId
    $retry['retry.budget'] = '1'
    Expect-Rejection $retry `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'

    $dynamic = Copy-Values $smoke
    $dynamic['request.id'] = New-StockQuantHostBrokerRequestId
    $dynamic['command.text'] = 'Write-Host forbidden'
    Expect-Rejection $dynamic `
        'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'

    $secret = Copy-Values $smoke
    $secret['request.id'] = New-StockQuantHostBrokerRequestId
    $secret['api.key'] = 'forbidden'
    Expect-Rejection $secret `
        'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'

    $authorization = Copy-Values $smoke
    $authorization['request.id'] = New-StockQuantHostBrokerRequestId
    $authorization['authorization.file'] = $proof
    Expect-Rejection $authorization `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'

    $expired = Copy-Values $smoke
    $expired['request.id'] = New-StockQuantHostBrokerRequestId
    $expired['created.at'] = $created.AddMinutes(-20).ToString('o')
    $expired['expires.at'] = $created.AddMinutes(-10).ToString('o')
    Expect-Rejection $expired 'STOCK_QUANT_HOST_BROKER_REQUEST_EXPIRED'

    Write-Output "STOCK_QUANT_M3_BROKER_PROTOCOL_TESTS=$tests"
    Write-Output 'STOCK_QUANT_M3_BROKER_PROTOCOL_FAILURES=0'
    Write-Output 'STOCK_QUANT_M3_BROKER_BAILIAN_NETWORK_CALLS=0'
    Write-Output 'STOCK_QUANT_M3_BROKER_TUSHARE_CALLS=0'
    Write-Output 'STOCK_QUANT_M3_BROKER_DATABASE_WRITES=0'
} finally {
    if ($processing -and (Test-Path -LiteralPath $processing)) {
        Remove-Item -LiteralPath $processing -Force
    }
    if ($credentialResultPath -and
        (Test-Path -LiteralPath $credentialResultPath)) {
        Remove-Item -LiteralPath $credentialResultPath -Force
    }
    if (Test-Path -LiteralPath $root) {
        $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
        if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne
                $paths.TargetRoot.TrimEnd('\', '/') -or
            -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
            throw 'M3_PROTOCOL_CLEANUP_INVALID'
        }
        Remove-Item -LiteralPath $full -Recurse -Force
    }
}
