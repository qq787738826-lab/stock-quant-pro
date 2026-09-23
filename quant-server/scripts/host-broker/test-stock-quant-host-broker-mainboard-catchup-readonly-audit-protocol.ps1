[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
$paths = Initialize-StockQuantHostBrokerDirectories
$root = Join-Path $paths.TargetRoot `
    ('stock-quant-catchup-audit-protocol-' +
        [Guid]::NewGuid().ToString('N'))
$artifact = Join-Path $root 'mainboard-catchup-audit-test.jar'
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
        throw "CATCHUP_AUDIT_EXPECTED_REJECTION_MISSING_$Case"
    } catch {
        if ($_.Exception.Message -ne $Reason) { throw }
    }
    $script:tests++
}

try {
    New-Item -ItemType Directory -Path $root | Out-Null
    [IO.File]::WriteAllBytes($artifact, [byte[]](1, 0, 1, 8))
    $hash = ((Get-FileHash $artifact -Algorithm SHA256).Hash
        ).ToLowerInvariant()
    $head = (git -C $paths.RepositoryRoot rev-parse HEAD).Trim()
    $created = [DateTimeOffset]::UtcNow
    $month = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId(
        $created, 'China Standard Time').ToString('yyyy-MM')
    [int]$limit = Get-StockQuantTushareMonthlyLimit -CalendarMonth $month
    [int]$ledger = (Get-StockQuantM4MonthlyUsage `
        -CalendarMonth $month).CommittedTushareCalls
    $request = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = New-StockQuantHostBrokerRequestId
        'operation' = 'MAINBOARD_CATCHUP_READONLY_AUDIT'
        'git.commit' = $head
        'jar.path' = $artifact
        'jar.sha256' = $hash
        'authorization.file' = 'NONE'
        'universe.version' = 'RESEARCH_UNIVERSE_MAINBOARD_V1'
        'database.host' = '127.0.0.1'
        'database.port' = '38432'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'schema.name' = 'tushare_research'
        'database.read.only' = 'true'
        'provider' = 'NONE'
        'provider.endpoints' = 'NONE'
        'maximum.provider.requests' = '0'
        'budget.calendar.month' = $month
        'tushare.monthly.limit' = [string]$limit
        'tushare.monthly.calls.before' = [string]$ledger
        'retry.budget' = '0'
        'network.recovery.budget' = '0'
        'redirects' = 'NEVER'
        'user.approval.reference' =
            'USER_APPROVED_V1_MAINBOARD_CATCHUP_READONLY_AUDIT'
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'V1_MAINBOARD_CATCHUP_READONLY_AUDIT'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    Write-Lines "$artifact.f1f-b2-proof.properties" ([ordered]@{
        'git.commit' = $head
        'artifact.sha256' = $hash
        'build.mode' = 'RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT'
    })

    $parsed = Read-Valid $request
    if ($parsed.Operation -ne 'MAINBOARD_CATCHUP_READONLY_AUDIT' -or
        $parsed.AuthorizationStatus -ne
            'V1_MAINBOARD_CATCHUP_READONLY_AUDIT_APPROVED' -or
        $parsed.Values['provider'] -ne 'NONE' -or
        $parsed.Values['database.read.only'] -ne 'true') {
        throw 'MAINBOARD_CATCHUP_READONLY_AUDIT_VALID_REQUEST_REJECTED'
    }
    $tests++

    foreach ($case in @(
        @('provider', 'TUSHARE'),
        @('provider.endpoints', 'trade_cal'),
        @('maximum.provider.requests', '1'),
        @('database.read.only', 'false'),
        @('network.recovery.budget', '1'),
        @('database.port', '5432'),
        @('execution.source', 'MAINBOARD_DAILY_INCREMENT')
    )) {
        $invalid = Copy-Values $request
        $invalid['request.id'] = New-StockQuantHostBrokerRequestId
        $invalid[$case[0]] = $case[1]
        Reject $invalid 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' `
            $case[0]
    }

    foreach ($field in @('sql', 'table.name', 'schema.override',
            'file.path', 'command.text')) {
        $invalid = Copy-Values $request
        $invalid['request.id'] = New-StockQuantHostBrokerRequestId
        $invalid[$field] = 'forbidden'
        Reject $invalid 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID' `
            $field
    }

    [int]$before = (Get-StockQuantM4MonthlyUsage `
        -CalendarMonth $month).CommittedTushareCalls
    $ledgerProbe = Copy-Values $request
    $ledgerProbe['request.id'] = New-StockQuantHostBrokerRequestId
    $ledgerPath = Join-Path $paths.Requests `
        "$($ledgerProbe['request.id']).processed.properties"
    Write-Lines $ledgerPath $ledgerProbe
    $cleanup += $ledgerPath
    [int]$after = (Get-StockQuantM4MonthlyUsage `
        -CalendarMonth $month).CommittedTushareCalls
    if ($after -ne $before) {
        throw 'MAINBOARD_CATCHUP_AUDIT_LEDGER_ZERO_CALL_INVALID'
    }
    $tests++

    $broker = Get-Content -LiteralPath (Join-Path $PSScriptRoot `
        'stock-quant-host-broker.ps1') -Raw -Encoding UTF8
    $start = $broker.IndexOf(
        'function Invoke-MainboardCatchupReadonlyAudit')
    $end = $broker.IndexOf('function Invoke-MainboardHistoryBackfill')
    if ($start -lt 0 -or $end -le $start) {
        throw 'MAINBOARD_CATCHUP_AUDIT_FIXED_DISPATCH_MISSING'
    }
    $section = $broker.Substring($start, $end - $start)
    foreach ($forbidden in @('TushareToken', 'Bailian', 'AgentResearch',
            'Invoke-Expression', '-Command', 'trade_cal', 'daily(')) {
        if ($section.Contains($forbidden)) {
            throw 'MAINBOARD_CATCHUP_AUDIT_SCOPE_EXPANSION_DETECTED'
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
