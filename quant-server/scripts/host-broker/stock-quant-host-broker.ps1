[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force

$paths = Initialize-StockQuantHostBrokerDirectories
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$integrationBranch = 'feature/1.4.0-agent-team'
$preflightClass = 'com.stockquant.server.agent.marketfacts.' +
    'TushareReducedResearchDay001Preflight'
$credentialStatusScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\set-stock-quant-secrets.ps1'
$hostRunnerScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\run-stock-quant-local-automation.ps1'
$fakeE2eScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\run-reduced-research-day001-e2e-dry-run.ps1'
$mutex = [Threading.Mutex]::new($false, 'Local\StockQuantLocalBroker')
$mutexHeld = $false
$brokerStartedAt = [DateTimeOffset]::UtcNow
$brokerGitCommit = $null
$pollIntervalMilliseconds = 1000
$processingPath = $null
$processedPath = $null
$request = $null
$requestId = $null
$operation = 'UNKNOWN'
$startedAt = $brokerStartedAt
$stage = 'INITIALIZATION'
$failureSummary = $null

function Get-SafeMarker {
    param(
        [object[]] $Lines,
        [string] $Name,
        [string] $Fallback
    )
    $prefix = "$Name="
    $matches = @($Lines | Where-Object {
        $null -ne $_ -and ([string]$_).StartsWith($prefix)
    })
    if ($matches.Count -ne 1) { return $Fallback }
    $value = ([string]$matches[0]).Substring($prefix.Length)
    if ($value -notmatch '^[A-Z][A-Z0-9_]{2,127}$') { return $Fallback }
    return $value
}

function Assert-GitBinding {
    param(
        [Parameter(Mandatory = $true)]
        [object] $BrokerRequest
    )
    Push-Location $paths.RepositoryRoot
    try {
        $head = (git rev-parse HEAD).Trim()
        $branch = (git branch --show-current).Trim()
        $unexpected = @(git status --porcelain=v1 --untracked-files=normal |
            Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' })
        if ($head -ne $BrokerRequest.GitCommit -or
            $unexpected.Count -ne 0 -or
            @(git diff --cached --name-only).Count -ne 0) {
            throw 'STOCK_QUANT_HOST_BROKER_GIT_BINDING_INVALID'
        }
        if ($BrokerRequest.Operation -eq 'RUN_DAY001') {
            git fetch --quiet origin $integrationBranch
            if ($LASTEXITCODE -ne 0) {
                throw 'STOCK_QUANT_HOST_BROKER_GIT_FETCH_FAILED'
            }
            $remote = (git rev-parse `
                "refs/remotes/origin/$integrationBranch").Trim()
            $divergence = (git rev-list --left-right --count `
                "$integrationBranch...origin/$integrationBranch").Trim() `
                -split '\s+'
            if ($branch -ne $integrationBranch -or
                $remote -ne $BrokerRequest.GitCommit -or
                $divergence.Count -ne 2 -or $divergence[0] -ne '0' -or
                $divergence[1] -ne '0') {
                throw 'STOCK_QUANT_HOST_BROKER_GIT_BINDING_INVALID'
            }
        } elseif ($branch -ne $integrationBranch -and
            -not $branch.StartsWith('codex/')) {
            throw 'STOCK_QUANT_HOST_BROKER_GIT_BINDING_INVALID'
        }
    } finally {
        Pop-Location
    }
}

function Assert-UserApprovedPreflight {
    param(
        [Parameter(Mandatory = $true)]
        [object] $BrokerRequest
    )
    if (Test-Path -LiteralPath `
            "$($BrokerRequest.AuthorizationFile).consumed") {
        throw 'TUSHARE_REDUCED_RESEARCH_AUTHORIZATION_ALREADY_CONSUMED'
    }
    $output = @(& java "-Dloader.main=$preflightClass" `
        -cp $BrokerRequest.JarPath `
        'org.springframework.boot.loader.launch.PropertiesLauncher' `
        "--authorization-file=$($BrokerRequest.AuthorizationFile)" 2>&1)
    if ($LASTEXITCODE -ne 0 -or
        $output -notcontains 'TUSHARE_REDUCED_RESEARCH_DAY001_PREFLIGHT=PASS' -or
        $output -notcontains `
            "TUSHARE_REDUCED_RESEARCH_GIT_COMMIT=$($BrokerRequest.GitCommit)" -or
        $output -notcontains `
            "TUSHARE_REDUCED_RESEARCH_ARTIFACT_SHA256=$($BrokerRequest.JarSha256)" -or
        $output -notcontains `
            "TUSHARE_REDUCED_RESEARCH_BUILD_PROOF_PATH=$($BrokerRequest.BuildProofPath)") {
        $reason = Get-SafeMarker -Lines $output `
            -Name 'TUSHARE_REDUCED_RESEARCH_PREFLIGHT_REASON' `
            -Fallback 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_INVALID'
        throw $reason
    }
    $authorizationLines = @(Get-Content -LiteralPath `
        $BrokerRequest.AuthorizationFile -Encoding UTF8)
    foreach ($expected in @(
            'authorization.status=USER_APPROVED',
            'day001.mode=IDEMPOTENCY_VERIFICATION',
            'security.symbol=600000',
            'security.exchange=SSE',
            'trade.date=2025-01-03',
            'database.port=38432',
            'execution.source=REDUCED_RESEARCH_MANUAL_DAY001')) {
        if (@($authorizationLines | Where-Object { $_ -ceq $expected }).Count `
                -ne 1) {
            throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_SCOPE_MISMATCH'
        }
    }
}

function Invoke-CredentialStatus {
    $output = @(& $credentialStatusScript -Status 2>&1)
    if ($LASTEXITCODE -ne 0 -or
        $output -notcontains 'STOCK_QUANT_CREDENTIALS_READY=True') {
        throw 'STOCK_QUANT_HOST_BROKER_CREDENTIALS_MISSING'
    }
    return [ordered]@{
        credentialsReady = $true
        providerCallCount = 0
        retryCount = 0
    }
}

function Invoke-FakeE2e {
    param(
        [Parameter(Mandatory = $true)]
        [object] $BrokerRequest
    )
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $fakeE2eScript `
            -ExpectedCommit $BrokerRequest.GitCommit 2>&1 |
            ForEach-Object { [string]$_ })
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($output -notcontains 'TUSHARE_REDUCED_RESEARCH_DAY001_E2E=PASS' -or
        $output -notcontains `
            'TUSHARE_REDUCED_RESEARCH_DAY001_REAL_PROVIDER_CALLS=0' -or
        $output -notcontains `
            'TUSHARE_REDUCED_RESEARCH_DAY001_E2E_RESIDUALS=0') {
        throw 'STOCK_QUANT_HOST_BROKER_FAKE_E2E_FAILED'
    }
    return [ordered]@{
        fakeProviderCallCount = 9
        providerCallCount = 0
        retryCount = 0
        temporaryPostgres = 'PASSED'
        outputAudit = 'PASSED'
        residualCount = 0
    }
}

function Invoke-Day001 {
    param(
        [Parameter(Mandatory = $true)]
        [object] $BrokerRequest
    )
    Assert-UserApprovedPreflight -BrokerRequest $BrokerRequest
    $runnerResult = Join-Path $paths.Results `
        "$($BrokerRequest.RequestId).day001.json"
    if (Test-Path -LiteralPath $runnerResult) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_ALREADY_EXISTS'
    }
    $output = @(& $hostRunnerScript `
        -AuthorizationFile $BrokerRequest.AuthorizationFile `
        -ResultFile $runnerResult `
        -ArtifactPath $BrokerRequest.JarPath `
        -SecretMode WINDOWS_CREDENTIAL_MANAGER 2>&1)
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path -LiteralPath $runnerResult -PathType Leaf) {
            try {
                $failed = Get-Content -LiteralPath $runnerResult `
                    -Raw -Encoding UTF8 | ConvertFrom-Json
                $calls = [int]$failed.providerCallCount
                $retry = [int]$failed.retryCount
                if ($calls -ge 0 -and $calls -le 3 -and $retry -eq 0 -and
                    [string]$failed.runId -match
                        '^[A-Za-z0-9][A-Za-z0-9_-]{7,95}$') {
                    $script:failureSummary = [ordered]@{
                        runId = [string]$failed.runId
                        providerCallCount = $calls
                        retryCount = $retry
                        captureBatchId = $failed.captureBatchId
                        newObservationCount = [int]$failed.newObservationCount
                        existingChainTailCount = `
                            [int]$failed.existingChainTailCount
                        outputAudit = $(if ($failed.outputAudit.clean) {
                            'PASSED'
                        } else { 'FAILED' })
                    }
                }
            } catch {
                $script:failureSummary = $null
            }
        }
        $failureStage = Get-SafeMarker -Lines $output `
            -Name 'STOCK_QUANT_AUTOMATION_FAILURE_STAGE' `
            -Fallback 'FAILED_VALIDATION'
        $failureReason = Get-SafeMarker -Lines $output `
            -Name 'STOCK_QUANT_AUTOMATION_FAILURE_REASON' `
            -Fallback 'STOCK_QUANT_HOST_BROKER_DAY001_FAILED'
        throw [InvalidOperationException]::new(
            $failureReason + '__STAGE__' + $failureStage)
    }
    if (-not (Test-Path -LiteralPath $runnerResult -PathType Leaf)) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_MISSING'
    }
    $day001 = Get-Content -LiteralPath $runnerResult -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($day001.status -ne 'SUCCEEDED' -or
        $day001.providerCallCount -ne 3 -or $day001.retryCount -ne 0 -or
        $day001.endpointCallCounts.daily -ne 1 -or
        $day001.endpointCallCounts.adj_factor -ne 1 -or
        $day001.endpointCallCounts.trade_cal -ne 1 -or
        $day001.typedFactReadback -ne 'PASSED' -or
        $day001.systemKnowledgeReadback -ne 'PASSED' -or
        $day001.formulaOnlyQfq.result -ne 'PASSED' -or
        -not $day001.outputAudit.clean) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_INVALID'
    }
    return [ordered]@{
        runId = [string]$day001.runId
        providerCallCount = [int]$day001.providerCallCount
        retryCount = [int]$day001.retryCount
        captureBatchId = [long]$day001.captureBatchId
        newObservationCount = [int]$day001.newObservationCount
        existingChainTailCount = [int]$day001.existingChainTailCount
        typedFactReadback = [string]$day001.typedFactReadback
        systemKnowledgeReadback = [string]$day001.systemKnowledgeReadback
        qfqResult = [string]$day001.formulaOnlyQfq.result
        outputAudit = 'PASSED'
    }
}

function Read-SanitizedBrokerResult {
    param(
        [Parameter(Mandatory = $true)]
        [object] $BrokerRequest
    )
    $source = Join-Path $paths.Results `
        "$($BrokerRequest.SourceRequestId).result.json"
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw 'STOCK_QUANT_HOST_BROKER_SOURCE_RESULT_MISSING'
    }
    $result = Get-Content -LiteralPath $source -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($result.schemaVersion -ne 'STOCK_QUANT_HOST_BROKER_RESULT_V1' -or
        $result.requestId -ne $BrokerRequest.SourceRequestId -or
        $result.status -notin @('SUCCEEDED', 'FAILED', 'REJECTED')) {
        throw 'STOCK_QUANT_HOST_BROKER_SOURCE_RESULT_INVALID'
    }
    return [ordered]@{
        sourceRequestId = [string]$result.requestId
        sourceOperation = [string]$result.operation
        sourceStatus = [string]$result.status
        sourceStage = [string]$result.stage
        sourceReason = [string]$result.reason
        providerCallCount = [int]$result.providerCallCount
        retryCount = [int]$result.retryCount
    }
}

function Write-Outcome {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Status,
        [Parameter(Mandatory = $true)]
        [string] $Stage,
        [Parameter(Mandatory = $true)]
        [string] $Reason,
        [AllowNull()]
        [System.Collections.IDictionary] $Summary
    )
    $providerCalls = 0
    $retries = 0
    if ($null -ne $Summary) {
        if ($Summary.Contains('providerCallCount')) {
            $providerCalls = [int]$Summary['providerCallCount']
        }
        if ($Summary.Contains('retryCount')) {
            $retries = [int]$Summary['retryCount']
        }
    }
    $result = [ordered]@{
        requestId = $requestId
        operation = $operation
        status = $Status
        stage = $Stage
        reason = $Reason
        gitCommit = $(if ($null -ne $request) {
            $request.GitCommit
        } else { 'UNKNOWN' })
        providerCallCount = $providerCalls
        retryCount = $retries
        noRetry = $true
        startedAt = $startedAt.ToString('o')
        completedAt = [DateTimeOffset]::UtcNow.ToString('o')
        summary = $Summary
    }
    Write-StockQuantHostBrokerResult -Result $result | Out-Null
}

function Write-BrokerHeartbeat {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('IDLE', 'BUSY')]
        [string] $State
    )
    Write-StockQuantHostBrokerHeartbeat `
        -GitCommit $brokerGitCommit -WindowsUser $identity `
        -ProcessId $PID -StartedAt $brokerStartedAt -State $State |
        Out-Null
}

function Invoke-ClaimedRequest {
    param(
        [Parameter(Mandatory = $true)]
        [IO.FileInfo] $Candidate
    )
    $script:processingPath = $null
    $script:processedPath = $null
    $script:request = $null
    $script:requestId = $null
    $script:operation = 'UNKNOWN'
    $script:startedAt = [DateTimeOffset]::UtcNow
    $script:stage = 'REQUEST_CLAIM'
    $script:failureSummary = $null
    try {
        if ($Candidate.Name -notmatch
                '^(SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12})\.request\.properties$') {
            $invalidPath = $Candidate.FullName + '.rejected'
            [IO.File]::Move($Candidate.FullName, $invalidPath)
            return
        }
        $script:requestId = $Matches[1]
        $priorRequestFiles = @(Get-ChildItem -LiteralPath $paths.Requests `
            -File -Filter "$requestId.*" | Where-Object {
                $_.FullName -ne $Candidate.FullName
            })
        $priorResultFiles = @(Get-ChildItem -LiteralPath $paths.Results `
            -File -Filter "$requestId.*")
        if ($priorRequestFiles.Count -gt 0 -or $priorResultFiles.Count -gt 0) {
            $duplicatePath = Join-Path $paths.Requests `
                "$requestId.rejected.properties"
            if (-not (Test-Path -LiteralPath $duplicatePath)) {
                [IO.File]::Move($Candidate.FullName, $duplicatePath)
            }
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_ID_ALREADY_USED'
        }
        $script:processingPath = Join-Path $paths.Requests `
            "$requestId.processing.properties"
        $script:processedPath = Join-Path $paths.Requests `
            "$requestId.processed.properties"
        [IO.File]::Move($Candidate.FullName, $processingPath)

        $script:stage = 'REQUEST_VALIDATION'
        $script:request = Read-StockQuantHostBrokerRequest `
            -Path $processingPath
        $script:operation = $request.Operation
        Assert-GitBinding -BrokerRequest $request

        $script:stage = $operation
        $summary = switch ($operation) {
            'CHECK_CREDENTIAL_STATUS' { Invoke-CredentialStatus; break }
            'RUN_FAKE_E2E' { Invoke-FakeE2e -BrokerRequest $request; break }
            'RUN_DAY001' { Invoke-Day001 -BrokerRequest $request; break }
            'READ_SANITIZED_RESULT' {
                Read-SanitizedBrokerResult -BrokerRequest $request
                break
            }
            default { throw 'STOCK_QUANT_HOST_BROKER_OPERATION_NOT_ALLOWED' }
        }
        Write-Outcome -Status 'SUCCEEDED' -Stage 'COMPLETED' `
            -Reason 'STOCK_QUANT_HOST_BROKER_SUCCEEDED' -Summary $summary
        [IO.File]::Move($processingPath, $processedPath)
        $script:processingPath = $null
        Write-Output "STOCK_QUANT_HOST_BROKER_REQUEST_ID=$requestId"
        Write-Output 'STOCK_QUANT_HOST_BROKER_STATUS=SUCCEEDED'
    } catch {
        $rawCode = ConvertTo-StockQuantSafeCode -ErrorValue $_
        $reason = $rawCode
        if ($rawCode -match '^([A-Z][A-Z0-9_]{7,127})__STAGE__([A-Z][A-Z0-9_]{2,127})$') {
            $reason = $Matches[1]
            $script:stage = $Matches[2]
        }
        if ($null -ne $requestId -and
            -not (Test-Path -LiteralPath (
                Join-Path $paths.Results "$requestId.result.json"))) {
            try {
                Write-Outcome -Status $(if ($stage -in @(
                        'REQUEST_CLAIM', 'REQUEST_VALIDATION')) {
                        'REJECTED'
                    } else { 'FAILED' }) `
                    -Stage $stage -Reason $reason -Summary $failureSummary
            } catch {
                # Fail closed without writing unsafe fallback output.
            }
        }
        if ($null -ne $processingPath -and
            (Test-Path -LiteralPath $processingPath) -and
            $null -ne $processedPath -and
            -not (Test-Path -LiteralPath $processedPath)) {
            [IO.File]::Move($processingPath, $processedPath)
            $script:processingPath = $null
        }
        Write-Output "STOCK_QUANT_HOST_BROKER_FAILURE_STAGE=$stage"
        Write-Output "STOCK_QUANT_HOST_BROKER_FAILURE_REASON=$reason"
        Write-Output 'STOCK_QUANT_HOST_BROKER_STATUS=FAILED'
    }
}

try {
    if ($identity -match '(?i)CodexSandbox') {
        throw 'STOCK_QUANT_HOST_BROKER_REAL_USER_REQUIRED'
    }
    $mutexHeld = $mutex.WaitOne(0)
    if (-not $mutexHeld) {
        throw 'STOCK_QUANT_HOST_BROKER_ALREADY_RUNNING'
    }
    Push-Location $paths.RepositoryRoot
    try {
        $brokerGitCommit = (git rev-parse HEAD).Trim()
    } finally {
        Pop-Location
    }
    if ($brokerGitCommit -notmatch '^[0-9a-f]{40}$') {
        throw 'STOCK_QUANT_HOST_BROKER_GIT_BINDING_INVALID'
    }

    while ($true) {
        Write-BrokerHeartbeat -State IDLE
        $pending = @(Get-ChildItem -LiteralPath $paths.Requests -File `
            -Filter 'SQHB_*.request.properties' |
            Sort-Object LastWriteTimeUtc, Name)
        if ($pending.Count -eq 0) {
            Start-Sleep -Milliseconds $pollIntervalMilliseconds
            continue
        }
        Write-BrokerHeartbeat -State BUSY
        Invoke-ClaimedRequest -Candidate $pending[0]
    }
} catch {
    $reason = ConvertTo-StockQuantSafeCode -ErrorValue $_
    Write-Output 'STOCK_QUANT_HOST_BROKER_FAILURE_STAGE=RESIDENT_LOOP'
    Write-Output "STOCK_QUANT_HOST_BROKER_FAILURE_REASON=$reason"
    Write-Output 'STOCK_QUANT_HOST_BROKER_STATUS=FAILED'
    exit 20
} finally {
    if ($mutexHeld) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
