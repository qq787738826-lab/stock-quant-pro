[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
$paths = Initialize-StockQuantHostBrokerDirectories
$prefix = 'stock-quant-m2-protocol-'
$root = Join-Path $paths.TargetRoot ($prefix + [Guid]::NewGuid().ToString('N'))
$artifact = Join-Path $root 'm2-protocol-test.jar'
$proof = "$artifact.f1f-b2-proof.properties"
$processing = $null

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
            throw 'M2_PROTOCOL_EXPECTED_REJECTION_MISSING'
        } catch {
            if ($_.Exception.Message -ne $Expected) { throw }
        }
    } finally {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }
}

try {
    New-Item -ItemType Directory -Path $root | Out-Null
    [IO.File]::WriteAllBytes($artifact, [byte[]](1, 2, 3, 4))
    [IO.File]::WriteAllText($proof, "test=true`n",
        [Text.UTF8Encoding]::new($false))
    $hash = ((Get-FileHash $artifact -Algorithm SHA256).Hash).ToLowerInvariant()
    $head = (git -C $paths.RepositoryRoot rev-parse HEAD).Trim()
    $created = [DateTimeOffset]::UtcNow
    $id = New-StockQuantHostBrokerRequestId
    $values = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = $id
        'operation' = 'RUN_M2_STRATEGY_RESEARCH_SMOKE'
        'git.commit' = $head
        'jar.path' = $artifact
        'jar.sha256' = $hash
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
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'M2_STRATEGY_RESEARCH_READ_ONLY'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    $processing = Join-Path $paths.Requests "$id.processing.properties"
    Write-Lines $processing $values
    $parsed = Read-StockQuantHostBrokerRequest -Path $processing
    if ($parsed.Operation -ne 'RUN_M2_STRATEGY_RESEARCH_SMOKE' -or
        $parsed.AuthorizationStatus -ne
            'M2_STAGE_APPROVED_ZERO_PROVIDER_READ_ONLY' -or
        $null -ne $parsed.AuthorizationFile -or -not $parsed.NoRetry) {
        throw 'M2_PROTOCOL_VALID_REQUEST_REJECTED'
    }
    Remove-Item -LiteralPath $processing -Force
    $processing = $null

    $provider = Copy-Values $values
    $provider['request.id'] = New-StockQuantHostBrokerRequestId
    $provider['provider'] = 'TUSHARE'
    Expect-Rejection $provider 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'

    $budget = Copy-Values $values
    $budget['request.id'] = New-StockQuantHostBrokerRequestId
    $budget['maximum.provider.requests'] = '1'
    Expect-Rejection $budget 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'

    $writable = Copy-Values $values
    $writable['request.id'] = New-StockQuantHostBrokerRequestId
    $writable['database.read.only'] = 'false'
    Expect-Rejection $writable 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'

    $authorization = Copy-Values $values
    $authorization['request.id'] = New-StockQuantHostBrokerRequestId
    $authorization['authorization.file'] = $proof
    Expect-Rejection $authorization `
        'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_MODE_INVALID'

    $injection = Copy-Values $values
    $injection['request.id'] = New-StockQuantHostBrokerRequestId
    $injection['command.text'] = 'Write-Host forbidden'
    Expect-Rejection $injection `
        'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'

    $escaped = Copy-Values $values
    $escaped['request.id'] = New-StockQuantHostBrokerRequestId
    $escaped['jar.path'] = Join-Path $paths.RepositoryRoot 'README.md'
    $escaped['jar.sha256'] = ((Get-FileHash `
        (Join-Path $paths.RepositoryRoot 'README.md') `
        -Algorithm SHA256).Hash).ToLowerInvariant()
    Expect-Rejection $escaped 'STOCK_QUANT_HOST_BROKER_JAR_PATH_INVALID'

    Write-Output 'STOCK_QUANT_M2_BROKER_PROTOCOL_TESTS=7'
    Write-Output 'STOCK_QUANT_M2_BROKER_PROTOCOL_FAILURES=0'
    Write-Output 'STOCK_QUANT_M2_BROKER_PROVIDER_CALLS=0'
    Write-Output 'STOCK_QUANT_M2_BROKER_DATABASE_WRITES=0'
} finally {
    if ($processing -and (Test-Path -LiteralPath $processing)) {
        Remove-Item -LiteralPath $processing -Force
    }
    if (Test-Path -LiteralPath $root) {
        $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
        if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne
                $paths.TargetRoot.TrimEnd('\', '/') -or
            -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
            throw 'M2_PROTOCOL_CLEANUP_INVALID'
        }
        Remove-Item -LiteralPath $full -Recurse -Force
    }
}
