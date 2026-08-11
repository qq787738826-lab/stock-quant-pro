[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
$paths = Initialize-StockQuantHostBrokerDirectories
$prefix = 'stock-quant-m1-protocol-'
$root = Join-Path $paths.TargetRoot ($prefix + [Guid]::NewGuid().ToString('N'))
$artifact = Join-Path $root 'm1-protocol-test.jar'
$proof = "$artifact.f1f-b2-proof.properties"
$authorization = Join-Path $root 'authorization.properties'
$processing = $null

function Write-Lines([string] $Path, [System.Collections.IDictionary] $Values) {
    $lines = foreach ($key in $Values.Keys) { "$key=$($Values[$key])" }
    [IO.File]::WriteAllText($Path, ($lines -join "`n") + "`n",
        [Text.UTF8Encoding]::new($false))
}

function Expect-Rejection(
    [System.Collections.IDictionary] $Values,
    [string] $Expected
) {
    $id = [string]$Values['request.id']
    $path = Join-Path $paths.Requests "$id.processing.properties"
    Write-Lines $path $Values
    try {
        try {
            Read-StockQuantHostBrokerRequest -Path $path | Out-Null
            throw 'M1_PROTOCOL_EXPECTED_REJECTION_MISSING'
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
    $issued = [DateTimeOffset]::UtcNow.AddSeconds(-2)
    $expires = $issued.AddMinutes(20)
    $auth = [ordered]@{
        'authorization.status' = 'USER_APPROVED'
        'authorization.version' = 'M1_RESEARCH_DATA_AUTHORIZATION_V1'
        'run.id' = 'M1_PROTOCOL_TEST_0001'
        'git.commit' = $head
        'artifact.sha256' = $hash
        'build.proof.path' = $proof
        'provider' = 'TUSHARE'
        'securities' = '600000:SSE,000001:SZSE'
        'range.start' = '2025-01-02'
        'range.end' = '2025-01-06'
        'anchor.trade.date' = '2025-01-06'
        'mode' = 'CAPTURE'
        'endpoints' = 'daily,adj_factor,trade_cal'
        'endpoint.daily.requests' = '2'
        'endpoint.adj_factor.requests' = '2'
        'endpoint.trade_cal.requests' = '2'
        'maximum.provider.requests' = '6'
        'retry.budget' = '0'
        'redirects' = 'NEVER'
        'provider.historical.baseline' = '34'
        'provider.stage.limit' = '30'
        'provider.cumulative.limit' = '64'
        'provider.stage.calls.before' = '0'
        'database.host' = '127.0.0.1'
        'database.port' = '38432'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'database.ssl.mode' = 'DISABLE_LOCAL_ONLY'
        'schema.name' = 'tushare_research'
        'issued.at' = $issued.ToString('o')
        'expires.at' = $expires.ToString('o')
        'purpose' = 'M1_RESEARCH_DATA_READY'
        'execution.source' = 'M1_RESEARCH_DATA_MANUAL'
        'user.approval.reference' = 'M1_STAGE_USER_APPROVED_TEST'
    }
    Write-Lines $authorization $auth

    $created = [DateTimeOffset]::UtcNow
    $id = New-StockQuantHostBrokerRequestId
    $values = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = $id
        'operation' = 'RUN_M1_RESEARCH_DATA'
        'git.commit' = $head
        'jar.path' = $artifact
        'jar.sha256' = $hash
        'authorization.file' = $authorization
        'm1.mode' = 'CAPTURE'
        'securities' = '600000:SSE,000001:SZSE'
        'range.start' = '2025-01-02'
        'range.end' = '2025-01-06'
        'anchor.trade.date' = '2025-01-06'
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
        'provider.stage.calls.before' = '0'
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'M1_RESEARCH_DATA_MANUAL'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    $processing = Join-Path $paths.Requests "$id.processing.properties"
    Write-Lines $processing $values
    $parsed = Read-StockQuantHostBrokerRequest -Path $processing
    if ($parsed.Operation -ne 'RUN_M1_RESEARCH_DATA' -or
        $parsed.AuthorizationStatus -ne 'USER_APPROVED' -or
        -not $parsed.NoRetry) {
        throw 'M1_PROTOCOL_VALID_REQUEST_REJECTED'
    }
    Remove-Item -LiteralPath $processing -Force
    $processing = $null

    $overflow = [ordered]@{}
    foreach ($key in $values.Keys) { $overflow[$key] = $values[$key] }
    $overflow['request.id'] = New-StockQuantHostBrokerRequestId
    $overflow['provider.stage.calls.before'] = '25'
    Expect-Rejection $overflow 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'

    $injection = [ordered]@{}
    foreach ($key in $values.Keys) { $injection[$key] = $values[$key] }
    $injection['request.id'] = New-StockQuantHostBrokerRequestId
    $injection['command.text'] = 'Write-Host forbidden'
    Expect-Rejection $injection 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'

    $expired = [ordered]@{}
    foreach ($key in $values.Keys) { $expired[$key] = $values[$key] }
    $expired['request.id'] = New-StockQuantHostBrokerRequestId
    $expired['created.at'] = $created.AddMinutes(-20).ToString('o')
    $expired['expires.at'] = $created.AddMinutes(-10).ToString('o')
    Expect-Rejection $expired 'STOCK_QUANT_HOST_BROKER_REQUEST_EXPIRED'

    Write-Output 'STOCK_QUANT_M1_BROKER_PROTOCOL_TESTS=4'
    Write-Output 'STOCK_QUANT_M1_BROKER_PROTOCOL_FAILURES=0'
    Write-Output 'STOCK_QUANT_M1_BROKER_PROTOCOL_REAL_PROVIDER_CALLS=0'
} finally {
    if ($processing -and (Test-Path -LiteralPath $processing)) {
        Remove-Item -LiteralPath $processing -Force
    }
    if (Test-Path -LiteralPath $root) {
        $full = [IO.Path]::GetFullPath($root).TrimEnd('\', '/')
        if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne
                $paths.TargetRoot.TrimEnd('\', '/') -or
            -not [IO.Path]::GetFileName($full).StartsWith($prefix)) {
            throw 'M1_PROTOCOL_CLEANUP_INVALID'
        }
        Remove-Item -LiteralPath $full -Recurse -Force
    }
}
