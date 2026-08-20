[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
$paths = Initialize-StockQuantHostBrokerDirectories
$root = Join-Path $paths.TargetRoot `
    ('stock-quant-mainboard-increment-protocol-' +
        [Guid]::NewGuid().ToString('N'))
$artifact = Join-Path $root 'mainboard-daily-increment-test.jar'
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
        throw "MAINBOARD_INCREMENT_EXPECTED_REJECTION_MISSING_$Case"
    } catch {
        if ($_.Exception.Message -ne $Reason) { throw }
    }
    $script:tests++
}

try {
    New-Item -ItemType Directory -Path $root | Out-Null
    [IO.File]::WriteAllBytes($artifact, [byte[]](1, 1, 0, 11))
    $hash = ((Get-FileHash $artifact -Algorithm SHA256).Hash
        ).ToLowerInvariant()
    $head = (git -C $paths.RepositoryRoot rev-parse HEAD).Trim()
    $created = [DateTimeOffset]::UtcNow
    $month = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId(
        $created, 'China Standard Time').ToString('yyyy-MM')
    [int]$limit = Get-StockQuantTushareMonthlyLimit -CalendarMonth $month
    $request = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = New-StockQuantHostBrokerRequestId
        'operation' = 'MAINBOARD_DAILY_INCREMENT'
        'git.commit' = $head
        'jar.path' = $artifact
        'jar.sha256' = $hash
        'authorization.file' = 'NONE'
        'trade.date' = '2026-08-19'
        'universe.version' = 'RESEARCH_UNIVERSE_MAINBOARD_V1'
        'database.host' = '127.0.0.1'
        'database.port' = '38432'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'schema.name' = 'tushare_research'
        'provider' = 'TUSHARE'
        'provider.endpoints' = 'daily,adj_factor'
        'endpoint.daily.requests' = '1'
        'endpoint.adj_factor.requests' = '1'
        'maximum.provider.requests' = '2'
        'budget.calendar.month' = $month
        'tushare.monthly.limit' = [string]$limit
        'tushare.monthly.calls.before' = '0'
        'retry.budget' = '0'
        'redirects' = 'NEVER'
        'user.approval.reference' =
            'USER_APPROVED_V1_0_11_MAINBOARD_DAILY_INCREMENT'
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' = 'MAINBOARD_DAILY_INCREMENT'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }
    Write-Lines "$artifact.f1f-b2-proof.properties" ([ordered]@{
        'git.commit' = $head
        'artifact.sha256' = $hash
        'build.mode' = 'RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT'
    })

    $parsed = Read-Valid $request
    if ($parsed.Operation -ne 'MAINBOARD_DAILY_INCREMENT' -or
        $parsed.AuthorizationStatus -ne
            'V1_0_11_MAINBOARD_DAILY_INCREMENT_APPROVED' -or
        $parsed.Values['provider.endpoints'] -ne 'daily,adj_factor') {
        throw 'MAINBOARD_DAILY_INCREMENT_VALID_REQUEST_REJECTED'
    }
    $tests++

    foreach ($case in @(
        @('provider.endpoints', 'daily,adj_factor,trade_cal'),
        @('endpoint.daily.requests', '2'),
        @('endpoint.adj_factor.requests', '0'),
        @('maximum.provider.requests', '3'),
        @('retry.budget', '1'),
        @('universe.version', 'RESEARCH_UNIVERSE_V1'),
        @('database.port', '5432'),
        @('execution.source', 'CURRENT_AS_OF_RESEARCH_SELECTION')
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
        "$($ledger['request.id']).mainboard-daily-increment.json"
    Write-Lines $requestPath $ledger
    $result = [ordered]@{
        schemaVersion = 'MAINBOARD_DAILY_INCREMENT_RESULT_V1'
        status = 'SUCCEEDED'
        executionId = ($ledger['request.id'] -replace '^SQHB_', 'MBINC_')
        gitCommit = $head
        tradeDate = '2026-08-19'
        universeMemberCount = 3193
        tushareProviderCallCount = 2
        retryCount = 0
        modelCallCount = 0
        dataOnly = $true
        realTradingStarted = $false
        coverageComplete = $true
        knownAtValid = $true
        pitAdmissionPassed = $true
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
    if ($afterCalls -ne $beforeCalls + 2) {
        throw 'MAINBOARD_DAILY_INCREMENT_LEDGER_ACCOUNTING_INVALID'
    }
    $tests++

    $broker = Get-Content -LiteralPath (Join-Path $PSScriptRoot `
        'stock-quant-host-broker.ps1') -Raw -Encoding UTF8
    $start = $broker.IndexOf('function Invoke-MainboardDailyIncrement')
    $end = $broker.IndexOf('function Resolve-ResearchProductionJavaExecutable')
    if ($start -lt 0 -or $end -le $start) {
        throw 'MAINBOARD_DAILY_INCREMENT_FIXED_DISPATCH_MISSING'
    }
    $section = $broker.Substring($start, $end - $start)
    foreach ($forbidden in @('Bailian', 'AgentResearch', 'Top200',
            'Top30', 'Top10', 'ResearchSelectionEngine')) {
        if ($section.Contains($forbidden)) {
            throw 'MAINBOARD_DAILY_INCREMENT_SCOPE_EXPANSION_DETECTED'
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
