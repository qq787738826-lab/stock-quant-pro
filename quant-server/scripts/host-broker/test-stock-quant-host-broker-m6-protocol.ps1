[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
$paths = Initialize-StockQuantHostBrokerDirectories
$root = Join-Path $paths.TargetRoot `
    ('stock-quant-m6-protocol-' + [Guid]::NewGuid().ToString('N'))
$artifact = Join-Path $root 'quant-server-1.3.1-research-production.jar'
$tests = 0
$brokerScript = Get-Content -LiteralPath (
    Join-Path $PSScriptRoot 'stock-quant-host-broker.ps1') -Raw

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

function Reject([System.Collections.IDictionary] $Values, [string] $Reason) {
    try { Read-Valid $Values | Out-Null; throw 'M6_EXPECTED_REJECTION_MISSING' }
    catch { if ($_.Exception.Message -ne $Reason) { throw } }
    $script:tests++
}

try {
    New-Item -ItemType Directory -Path $root | Out-Null
    [IO.File]::WriteAllBytes($artifact, [byte[]](6, 1, 6))
    $created = [DateTimeOffset]::UtcNow
    $request = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = New-StockQuantHostBrokerRequestId
        'operation' = 'START_RESEARCH_PRODUCTION'
        'git.commit' = (git -C $paths.RepositoryRoot rev-parse HEAD).Trim()
        'jar.path' = $artifact
        'jar.sha256' = ((Get-FileHash $artifact -Algorithm SHA256).Hash).ToLowerInvariant()
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
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'M6_RESEARCH_PRODUCTION_LOCAL'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    [IO.File]::WriteAllText("$artifact.f1f-b2-proof.properties", (@(
        "git.commit=$($request['git.commit'])"
        "artifact.sha256=$($request['jar.sha256'])"
        'build.mode=M6_STAGE_CONTROLLED_BUILD_ARTIFACT'
    ) -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    foreach ($operation in @('START_RESEARCH_PRODUCTION',
            'STOP_RESEARCH_PRODUCTION',
            'CHECK_RESEARCH_PRODUCTION_STATUS')) {
        $value = Copy-Values $request
        $value['request.id'] = New-StockQuantHostBrokerRequestId
        $value['operation'] = $operation
        $parsed = Read-Valid $value
        if ($parsed.Operation -ne $operation -or
            $parsed.AuthorizationStatus -ne 'M6_LOCAL_PRODUCTION_APPROVED') {
            throw 'M6_PROTOCOL_VALID_REQUEST_FAILED'
        }
        $tests++
    }
    $dynamic = Copy-Values $request
    $dynamic['request.id'] = New-StockQuantHostBrokerRequestId
    $dynamic['command.text'] = 'forbidden'
    Reject $dynamic 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'
    $provider = Copy-Values $request
    $provider['request.id'] = New-StockQuantHostBrokerRequestId
    $provider['maximum.provider.requests'] = '1'
    Reject $provider 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'
    $proofMismatch = Copy-Values $request
    $proofMismatch['request.id'] = New-StockQuantHostBrokerRequestId
    [IO.File]::WriteAllText("$artifact.f1f-b2-proof.properties", (@(
        "git.commit=$('f' * 40)"
        "artifact.sha256=$($request['jar.sha256'])"
        'build.mode=M6_STAGE_CONTROLLED_BUILD_ARTIFACT'
    ) -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    Reject $proofMismatch `
        'STOCK_QUANT_HOST_BROKER_BUILD_PROOF_BINDING_INVALID'
    [IO.File]::WriteAllText("$artifact.f1f-b2-proof.properties", (@(
        "git.commit=$($request['git.commit'])"
        "artifact.sha256=$($request['jar.sha256'])"
        'build.mode=M6_STAGE_CONTROLLED_BUILD_ARTIFACT'
    ) -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    $path = Copy-Values $request
    $path['request.id'] = New-StockQuantHostBrokerRequestId
    $path['jar.path'] = (Resolve-Path (Join-Path $paths.RepositoryRoot 'README.md')).Path
    $path['jar.sha256'] = ((Get-FileHash $path['jar.path'] -Algorithm SHA256).Hash).ToLowerInvariant()
    Reject $path 'STOCK_QUANT_HOST_BROKER_JAR_PATH_INVALID'

    if ($brokerScript -notmatch
            'function Resolve-ResearchProductionJavaExecutable' -or
        $brokerScript -notmatch
            "Start-Process -FilePath \`$javaExecutable" -or
        $brokerScript -notmatch "java\\.home" -or
        $brokerScript -notmatch 'M6_JAVA_17_RUNTIME_INVALID' -or
        $brokerScript -notmatch 'backend\.recovery-status\.json' -or
        $brokerScript -notmatch 'M6_PRODUCTION_RECOVERED') {
        throw 'M6_JAVA_PROCESS_BINDING_CONTRACT_FAILED'
    }
    $tests++

    Write-Output "M6_BROKER_PROTOCOL_TESTS=$tests"
    Write-Output 'M6_BROKER_PROVIDER_CALLS=0'
    Write-Output 'M6_BROKER_PERMANENT_DATABASE_WRITES=0'
    Write-Output 'M6_BROKER_PROTOCOL_STATUS=PASS'
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
