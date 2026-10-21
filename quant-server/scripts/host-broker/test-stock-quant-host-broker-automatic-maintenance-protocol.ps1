[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
$paths = Initialize-StockQuantHostBrokerDirectories
$artifact = Join-Path $paths.TargetRoot `
    'quant-server-1.3.1-research-production.jar'
$tests = 0
$cleanup = [Collections.Generic.List[string]]::new()

function Maintenance-RequestId([datetime] $Date, [string] $Commit) {
    $dateText = $Date.ToString('yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture)
    $timestamp = $Date.ToString('yyyyMMdd',
        [Globalization.CultureInfo]::InvariantCulture) + 'T083000Z'
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes(
            "V1_AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE|$dateText|$Commit")
        $hash = ([BitConverter]::ToString(
            $sha.ComputeHash($bytes))).Replace('-', '')
        return "SQHB_${timestamp}_$($hash.Substring(0, 12))"
    } finally { $sha.Dispose() }
}

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

function Copy-Result([object] $Source) {
    return $Source | ConvertTo-Json -Depth 12 | ConvertFrom-Json
}

function New-MaintenanceResult {
    param(
        [Parameter(Mandatory = $true)] [string] $Status,
        [Parameter(Mandatory = $true)] [string] $RequestId,
        [Parameter(Mandatory = $true)] [string] $GitCommit,
        [Parameter(Mandatory = $true)] [int] $DailyCalls,
        [Parameter(Mandatory = $true)] [int] $FactorCalls,
        [int] $CalendarCalls = 2,
        [int] $ProviderCalls = -1,
        [int] $FinalLedger = -1
    )
    if ($ProviderCalls -lt 0) {
        $ProviderCalls = $CalendarCalls + $DailyCalls + $FactorCalls
    }
    if ($FinalLedger -lt 0) { $FinalLedger = $ProviderCalls }
    $success = $Status -eq 'SUCCEEDED'
    [int]$initialMissingCount = if ($success) { $DailyCalls } else { 12 }
    [int]$dailyResultCount = if ($success) { $DailyCalls } else {
        $FactorCalls
    }
    $initialMissing = @(
        for ($index = 1; $index -le $initialMissingCount; $index++) {
            '2099-10-{0:D2}' -f $index
        })
    $dailyResults = @(
        for ($index = 1; $index -le $dailyResultCount; $index++) {
            [ordered]@{ tradeDate = '2099-10-{0:D2}' -f $index }
        })
    return [pscustomobject][ordered]@{
        schemaVersion =
            'AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE_RESULT_V1'
        status = $Status
        requestId = $RequestId
        executionId = ($RequestId -replace '^SQHB_', 'MBMAINT_')
        gitCommit = $GitCommit
        targetEndDate = '2099-10-20'
        forwardAction = 'APPENDED'
        calendarAfterSse = '2099-10-20'
        calendarAfterSzse = '2099-10-20'
        operationChain = @(
            'MAINBOARD_TRADE_CAL_FORWARD_INCREMENT',
            'MAINBOARD_CATCHUP_READONLY_AUDIT',
            'MAINBOARD_DAILY_INCREMENT',
            'MAINBOARD_CATCHUP_READONLY_AUDIT')
        initialLatestCompleteTradeDate = '2099-10-19'
        initialMissingTradeDates = $initialMissing
        initialPartialTradeDates = @()
        dailyResults = $dailyResults
        finalLatestCommonCompletedOpenTradeDate = '2099-10-20'
        finalLatestCompleteTradeDate = '2099-10-20'
        finalMissingTradeDates = @()
        finalPartialTradeDates = @()
        duplicateCount = 0
        ledgerBefore = 0
        ledgerLimit = 150
        maximumProviderRequests = 150
        plannedBaseCalls = 2 * $initialMissingCount
        projectedWorstCaseLedger = $CalendarCalls + 2 * $initialMissingCount
        finalLedger = $FinalLedger
        tushareProviderCallCount = $ProviderCalls
        tradeCalendarProviderCallCount = $CalendarCalls
        dailyProviderCallCount = $DailyCalls
        adjustmentFactorProviderCallCount = $FactorCalls
        stockBasicProviderCallCount = 0
        retryCount = 0
        networkRecoveryBudget = 2
        modelCallCount = 0
        researchSelectionRunsCreated = 0
        shadowRunsCreated = 0
        paperOrdersCreated = 0
        evaluationRowsCreated = 0
        historicalResearchClassification = 'POST_HOC_RESEARCH'
        pitClassification = 'PIT_PARTIAL'
        readyForShadow = $success
        outputAuditClean = $success
        dataOnly = $true
        realTradingStarted = $false
        failureReason = $(if ($success) { $null } else {
                'TUSHARE_CONNECT_TIMEOUT_NO_RESPONSE'
            })
    }
}

function Read-Valid(
    [System.Collections.IDictionary] $Values,
    [DateTimeOffset] $Now
) {
    $path = Join-Path $paths.Requests `
        "$($Values['request.id']).processing.properties"
    Write-Lines $path $Values
    try {
        return Read-StockQuantHostBrokerRequest -Path $path -Now $Now
    } finally {
        Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    }
}

function Reject(
    [System.Collections.IDictionary] $Values,
    [DateTimeOffset] $Now,
    [string] $Reason,
    [string] $Case
) {
    try {
        Read-Valid -Values $Values -Now $Now | Out-Null
        throw "AUTOMATIC_MAINTENANCE_EXPECTED_REJECTION_MISSING_$Case"
    } catch {
        if ($_.Exception.Message -ne $Reason) { throw }
    }
    $script:tests++
}

try {
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath `
            "$artifact.f1f-b2-proof.properties" -PathType Leaf)) {
        throw 'AUTOMATIC_MAINTENANCE_PROTOCOL_ARTIFACT_MISSING'
    }
    $head = (git -C $paths.RepositoryRoot rev-parse HEAD).Trim()
    $hash = ((Get-FileHash $artifact -Algorithm SHA256).Hash
        ).ToLowerInvariant()
    $targetDate = [datetime]::ParseExact('2026-09-24', 'yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture)
    $created = [DateTimeOffset]::Parse(
        '2026-09-24T08:30:00Z',
        [Globalization.CultureInfo]::InvariantCulture)
    $now = $created.AddMinutes(1)
    $month = '2026-09'
    [int]$limit = Get-StockQuantTushareMonthlyLimit -CalendarMonth $month
    $request = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = Maintenance-RequestId $targetDate $head
        'operation' = 'AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE'
        'git.commit' = $head
        'jar.path' = $artifact
        'jar.sha256' = $hash
        'authorization.file' = 'NONE'
        'target.end.date' = '2026-09-24'
        'maintenance.chain' =
            'MAINBOARD_TRADE_CAL_FORWARD_INCREMENT>MAINBOARD_CATCHUP_READONLY_AUDIT>MAINBOARD_DAILY_INCREMENT>MAINBOARD_CATCHUP_READONLY_AUDIT'
        'universe.version' = 'RESEARCH_UNIVERSE_MAINBOARD_V1'
        'database.host' = '127.0.0.1'
        'database.port' = '38432'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'schema.name' = 'tushare_research'
        'provider' = 'TUSHARE'
        'provider.endpoints' = 'trade_cal,daily,adj_factor'
        'maximum.provider.requests' = [string]$limit
        'budget.calendar.month' = $month
        'tushare.monthly.limit' = [string]$limit
        'tushare.monthly.calls.before' = '0'
        'retry.budget' = '0'
        'network.recovery.budget' = '2'
        'redirects' = 'NEVER'
        'historical.research.classification' = 'POST_HOC_RESEARCH'
        'pit.classification' = 'PIT_PARTIAL'
        'user.approval.reference' =
            'USER_APPROVED_V1_AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE'
        'created.at' = $created.ToString('o')
        'expires.at' = $created.AddMinutes(10).ToString('o')
        'execution.source' =
            'V1_AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE'
        'no.retry' = 'true'
        'source.request.id' = 'NONE'
    }

    $parsed = Read-Valid -Values $request -Now $now
    if ($parsed.Operation -ne
            'AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE' -or
        $parsed.AuthorizationStatus -ne
            'V1_AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE_APPROVED' -or
        [int]$parsed.Values['maximum.provider.requests'] -ne $limit) {
        throw 'AUTOMATIC_MAINTENANCE_VALID_REQUEST_REJECTED'
    }
    $tests++

    foreach ($case in @(
        @('maintenance.chain', 'MAINBOARD_DAILY_INCREMENT'),
        @('provider.endpoints', 'daily,adj_factor'),
        @('maximum.provider.requests', [string]($limit - 1)),
        @('network.recovery.budget', '3'),
        @('retry.budget', '1'),
        @('universe.version', 'RESEARCH_UNIVERSE_V1'),
        @('pit.classification', 'PIT_COMPLETE'),
        @('user.approval.reference', 'UNAPPROVED')
    )) {
        $invalid = Copy-Values $request
        $invalid[$case[0]] = $case[1]
        Reject $invalid $now `
            'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' $case[0]
    }
    $wrongId = Copy-Values $request
    $wrongId['request.id'] = New-StockQuantHostBrokerRequestId
    Reject $wrongId $now `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' 'RANDOM_ID'

    $outside = Copy-Values $request
    $outsideCreated = [DateTimeOffset]::Parse(
        '2026-09-24T07:00:00Z')
    $outside['created.at'] = $outsideCreated.ToString('o')
    $outside['expires.at'] = $outsideCreated.AddMinutes(10).ToString('o')
    Reject $outside $outsideCreated.AddMinutes(1) `
        'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID' 'OUTSIDE_WINDOW'

    $unknown = Copy-Values $request
    $unknown['sql'] = 'SELECT secret FROM forbidden'
    Reject $unknown $now `
        'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID' 'SQL_FIELD'

    $ledgerMonth = '2099-10'
    [int]$ledgerLimit = Get-StockQuantTushareMonthlyLimit `
        -CalendarMonth $ledgerMonth
    [int]$before = (Get-StockQuantM4MonthlyUsage `
        -CalendarMonth $ledgerMonth).CommittedTushareCalls
    if ($ledgerLimit -ne 150 -or $before -ne 0) {
        throw 'AUTOMATIC_MAINTENANCE_PROTOCOL_LEDGER_FIXTURE_DIRTY'
    }
    $ledgerDate = [datetime]::ParseExact('2099-10-20', 'yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture)
    $ledger = Copy-Values $request
    $ledger['request.id'] = Maintenance-RequestId $ledgerDate $head
    $ledger['target.end.date'] = '2099-10-20'
    $ledger['budget.calendar.month'] = $ledgerMonth
    $ledger['tushare.monthly.limit'] = [string]$ledgerLimit
    $ledger['maximum.provider.requests'] = [string]$ledgerLimit
    $ledger['tushare.monthly.calls.before'] = '0'
    $ledgerCreated = [DateTimeOffset]::Parse('2099-10-20T08:30:00Z')
    $ledger['created.at'] = $ledgerCreated.ToString('o')
    $ledger['expires.at'] = $ledgerCreated.AddMinutes(10).ToString('o')
    $requestPath = Join-Path $paths.Requests `
        "$($ledger['request.id']).processed.properties"
    $resultPath = Join-Path $paths.Results `
        "$($ledger['request.id']).automatic-daily-market-data-maintenance.json"
    if ((Test-Path -LiteralPath $requestPath) -or
        (Test-Path -LiteralPath $resultPath)) {
        throw 'AUTOMATIC_MAINTENANCE_PROTOCOL_LEDGER_FIXTURE_COLLISION'
    }
    Write-Lines $requestPath $ledger
    $cleanup.Add($requestPath)
    $cleanup.Add($resultPath)

    $failedElevenTen = New-MaintenanceResult -Status 'FAILED' `
        -RequestId $ledger['request.id'] -GitCommit $head `
        -DailyCalls 11 -FactorCalls 10
    $failedTenTen = New-MaintenanceResult -Status 'FAILED' `
        -RequestId $ledger['request.id'] -GitCommit $head `
        -DailyCalls 10 -FactorCalls 10
    $succeededElevenTen = New-MaintenanceResult -Status 'SUCCEEDED' `
        -RequestId $ledger['request.id'] -GitCommit $head `
        -DailyCalls 11 -FactorCalls 10
    $failedTwelveTen = New-MaintenanceResult -Status 'FAILED' `
        -RequestId $ledger['request.id'] -GitCommit $head `
        -DailyCalls 12 -FactorCalls 10
    $failedTenEleven = New-MaintenanceResult -Status 'FAILED' `
        -RequestId $ledger['request.id'] -GitCommit $head `
        -DailyCalls 10 -FactorCalls 11
    $providerSumInvalid = Copy-Result $failedElevenTen
    $providerSumInvalid.tushareProviderCallCount = 24
    $providerSumInvalid.finalLedger = 24
    $finalLedgerInvalid = Copy-Result $failedElevenTen
    $finalLedgerInvalid.finalLedger = 24
    $finalLedgerOverLimit = Copy-Result $failedElevenTen
    $finalLedgerOverLimit.finalLedger = 151
    $failedReady = Copy-Result $failedElevenTen
    $failedReady.readyForShadow = $true
    $failedAuditClean = Copy-Result $failedElevenTen
    $failedAuditClean.outputAuditClean = $true
    $failureReasonInvalid = Copy-Result $failedElevenTen
    $failureReasonInvalid.failureReason = 'tushare_connect_timeout'
    $succeeded = New-MaintenanceResult -Status 'SUCCEEDED' `
        -RequestId $ledger['request.id'] -GitCommit $head `
        -DailyCalls 1 -FactorCalls 1
    $resultCases = @(
        [pscustomobject]@{ Name = 'FAILED_11_10'; Valid = $true;
            Result = $failedElevenTen },
        [pscustomobject]@{ Name = 'FAILED_10_10'; Valid = $true;
            Result = $failedTenTen },
        [pscustomobject]@{ Name = 'SUCCEEDED_11_10'; Valid = $false;
            Result = $succeededElevenTen },
        [pscustomobject]@{ Name = 'FAILED_12_10'; Valid = $false;
            Result = $failedTwelveTen },
        [pscustomobject]@{ Name = 'FAILED_10_11'; Valid = $false;
            Result = $failedTenEleven },
        [pscustomobject]@{ Name = 'PROVIDER_SUM'; Valid = $false;
            Result = $providerSumInvalid },
        [pscustomobject]@{ Name = 'FINAL_LEDGER'; Valid = $false;
            Result = $finalLedgerInvalid },
        [pscustomobject]@{ Name = 'FINAL_LEDGER_LIMIT'; Valid = $false;
            Result = $finalLedgerOverLimit },
        [pscustomobject]@{ Name = 'FAILED_READY'; Valid = $false;
            Result = $failedReady },
        [pscustomobject]@{ Name = 'FAILED_AUDIT'; Valid = $false;
            Result = $failedAuditClean },
        [pscustomobject]@{ Name = 'FAILURE_REASON'; Valid = $false;
            Result = $failureReasonInvalid },
        [pscustomobject]@{ Name = 'SUCCEEDED_NORMAL'; Valid = $true;
            Result = $succeeded })

    $ledgerAccidentValidated = $false
    foreach ($case in $resultCases) {
        [IO.File]::WriteAllText($resultPath,
            ($case.Result | ConvertTo-Json -Depth 12 -Compress) + "`n",
            [Text.UTF8Encoding]::new($false))
        try {
            $usage = Get-StockQuantM4MonthlyUsage `
                -CalendarMonth $ledgerMonth
            if (-not $case.Valid -or
                [int]$usage.CommittedTushareCalls -ne
                    [int]$case.Result.tushareProviderCallCount) {
                throw "AUTOMATIC_MAINTENANCE_LEDGER_CASE_INVALID_$($case.Name)"
            }
        } catch {
            if ($case.Valid -or $_.Exception.Message -ne
                    'M4_MONTHLY_BUDGET_LEDGER_INVALID') {
                throw
            }
        }
        if ($case.Name -ceq 'FAILED_11_10') {
            $ledgerAccidentValidated = $true
        }
        $tests++
    }

    Remove-Item -LiteralPath $resultPath -Force

    $brokerPath = Join-Path $PSScriptRoot 'stock-quant-host-broker.ps1'
    $tokens = $null
    $parseErrors = $null
    $brokerAst = [Management.Automation.Language.Parser]::ParseFile(
        $brokerPath, [ref]$tokens, [ref]$parseErrors)
    if (@($parseErrors).Count -ne 0) {
        throw 'AUTOMATIC_MAINTENANCE_BROKER_PARSE_INVALID'
    }
    $maintenanceFunctions = @($brokerAst.FindAll({
                param($node)
                $node -is
                    [Management.Automation.Language.FunctionDefinitionAst] -and
                $node.Name -ceq 'Invoke-AutomaticDailyMarketDataMaintenance'
            }, $true))
    if ($maintenanceFunctions.Count -ne 1) {
        throw 'AUTOMATIC_MAINTENANCE_BROKER_VALIDATOR_MISSING'
    }
    . ([scriptblock]::Create($maintenanceFunctions[0].Extent.Text))

    function Assert-AutomaticMarketDataMaintenanceArtifactSet {
        param([object] $BrokerRequest)
    }
    function Get-StockQuantM4MonthlyUsage {
        param([string] $CalendarMonth, [string] $ExcludedRequestPath)
        return [pscustomobject]@{ CommittedTushareCalls = 0 }
    }
    function Get-StockQuantTushareMonthlyLimit {
        param([string] $CalendarMonth)
        return 150
    }
    function Invoke-AutomaticMaintenanceFixtureRunner {
        param(
            [string] $ResultFile,
            [string] $RequestId,
            [string] $GitCommit,
            [string] $TargetEndDate,
            [int] $DatabasePort,
            [int] $CurrentLedger,
            [int] $LedgerLimit,
            [int] $MaximumProviderRequests,
            [string] $ExecutionMode
        )
        [IO.File]::WriteAllText($ResultFile,
            $global:AutomaticMaintenanceFixtureJson + "`n",
            [Text.UTF8Encoding]::new($false))
        $global:LASTEXITCODE =
            [int]$global:AutomaticMaintenanceFixtureExitCode
    }

    $brokerDate = [datetime]::ParseExact('2099-10-21', 'yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture)
    $brokerRequestId = Maintenance-RequestId $brokerDate $head
    $brokerRequest = [pscustomobject]@{
        RequestId = $brokerRequestId
        GitCommit = $head
        JarPath = $artifact
        AuthorizationStatus =
            'V1_AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE_APPROVED'
        AuthorizationFile = $null
        Values = [ordered]@{
            'budget.calendar.month' = $ledgerMonth
            'target.end.date' = '2099-10-20'
            'tushare.monthly.calls.before' = '0'
            'tushare.monthly.limit' = '150'
            'maximum.provider.requests' = '150'
        }
    }
    $processingPath = Join-Path $paths.Requests `
        "$brokerRequestId.processing.properties"
    $automaticDailyMarketDataMaintenanceRunnerScript =
        'Invoke-AutomaticMaintenanceFixtureRunner'
    $brokerResultPath = Join-Path $paths.Results `
        "$brokerRequestId.automatic-daily-market-data-maintenance.json"
    if (Test-Path -LiteralPath $brokerResultPath) {
        throw 'AUTOMATIC_MAINTENANCE_BROKER_FIXTURE_COLLISION'
    }
    $cleanup.Add($brokerResultPath)

    $brokerAccidentValidated = $false
    foreach ($case in $resultCases) {
        $brokerResult = Copy-Result $case.Result
        $brokerResult.requestId = $brokerRequestId
        $brokerResult.executionId =
            ($brokerRequestId -replace '^SQHB_', 'MBMAINT_')
        $global:AutomaticMaintenanceFixtureJson =
            $brokerResult | ConvertTo-Json -Depth 12 -Compress
        $global:AutomaticMaintenanceFixtureExitCode =
            $(if ($brokerResult.status -eq 'SUCCEEDED') { 0 } else { 20 })
        $exception = $null
        $summary = $null
        try {
            $summary = Invoke-AutomaticDailyMarketDataMaintenance `
                -BrokerRequest $brokerRequest
        } catch {
            $exception = $_.Exception.Message
        } finally {
            Remove-Item -LiteralPath $brokerResultPath -Force `
                -ErrorAction SilentlyContinue
        }
        if ($case.Valid -and $brokerResult.status -eq 'FAILED') {
            if ($exception -ne [string]$brokerResult.failureReason) {
                throw "AUTOMATIC_MAINTENANCE_BROKER_CASE_INVALID_$($case.Name)"
            }
        } elseif ($case.Valid) {
            if ($null -ne $exception -or $null -eq $summary -or
                [int]$summary.providerCallCount -ne
                    [int]$brokerResult.tushareProviderCallCount) {
                throw "AUTOMATIC_MAINTENANCE_BROKER_CASE_INVALID_$($case.Name)"
            }
        } elseif ($exception -ne
                'AUTOMATIC_MARKET_DATA_MAINTENANCE_RESULT_INVALID') {
            throw "AUTOMATIC_MAINTENANCE_BROKER_CASE_INVALID_$($case.Name)"
        }
        if ($case.Name -ceq 'FAILED_11_10') {
            $brokerAccidentValidated = $true
        }
        $tests++
    }

    if (-not $ledgerAccidentValidated -or -not $brokerAccidentValidated) {
        throw 'AUTOMATIC_MAINTENANCE_ACCIDENT_FIXTURE_NOT_VALIDATED'
    }

    $broker = Get-Content -LiteralPath $brokerPath -Raw -Encoding UTF8
    $invoker = Get-Content -LiteralPath (Join-Path $PSScriptRoot `
        'invoke-stock-quant-host-broker.ps1') -Raw -Encoding UTF8
    if (-not $broker.Contains(
            "'AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE' {") -or
        -not $broker.Contains(
            'Invoke-AutomaticDailyMarketDataMaintenance') -or
        -not $invoker.Contains(
            "'AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE'") -or
        $broker.Contains('Invoke-Expression') -or
        $invoker.Contains('Invoke-Expression') -or
        $invoker.Contains('-Command')) {
        throw 'AUTOMATIC_MAINTENANCE_FIXED_DISPATCH_INVALID'
    }
    $tests++

    Write-Output "TESTS_RUN=$tests"
    Write-Output 'TESTS_FAILED=0'
    Write-Output 'TESTS_SKIPPED=0'
    Write-Output 'TESTS_ERRORS=0'
    Write-Output 'FAILED_11_10_LEDGER_VALIDATOR=PASS'
    Write-Output 'FAILED_11_10_BROKER_RESULT_VALIDATOR=PASS'
    Write-Output 'REAL_TUSHARE_CALLS=0'
    Write-Output 'REAL_BAILIAN_CALLS=0'
    Write-Output 'DATABASE_WRITES=0'
} finally {
    Remove-Variable AutomaticMaintenanceFixtureJson -Scope Global `
        -ErrorAction SilentlyContinue
    Remove-Variable AutomaticMaintenanceFixtureExitCode -Scope Global `
        -ErrorAction SilentlyContinue
    foreach ($path in $cleanup) {
        Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    }
}
