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
$m1PreflightClass = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM1ResearchDataPreflight'
$m1TokenVerificationPreflightClass =
    'com.stockquant.server.agent.marketfacts.' +
    'TushareM1TokenVerificationPreflight'
$credentialProbeClass = 'com.stockquant.server.agent.marketfacts.' +
    'TushareCredentialHealthProbe'
$bailianCredentialProbeClass = 'com.stockquant.server.agent.marketfacts.' +
    'BailianCredentialHealthProbe'
$credentialStatusScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\set-stock-quant-secrets.ps1'
$hostRunnerScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\run-stock-quant-local-automation.ps1'
$m1RunnerScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\run-m1-research-data.ps1'
$m1TokenVerificationScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\run-m1-tushare-token-verification.ps1'
$m2RunnerScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\run-m2-strategy-research.ps1'
$m3RunnerScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\run-m3-agent-research.ps1'
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

function Get-SafeIntegerMarker {
    param(
        [object[]] $Lines,
        [string] $Name
    )
    $prefix = "$Name="
    $matches = @($Lines | Where-Object {
        $null -ne $_ -and ([string]$_).StartsWith($prefix)
    })
    if ($matches.Count -ne 1) {
        throw 'STOCK_QUANT_HOST_BROKER_DIAGNOSTIC_OUTPUT_INVALID'
    }
    $value = ([string]$matches[0]).Substring($prefix.Length)
    if ($value -notmatch '^[0-9]{1,4}$') {
        throw 'STOCK_QUANT_HOST_BROKER_DIAGNOSTIC_OUTPUT_INVALID'
    }
    return [int]$value
}

function Get-SafeBooleanMarker {
    param(
        [object[]] $Lines,
        [string] $Name
    )
    $prefix = "$Name="
    $matches = @($Lines | Where-Object {
        $null -ne $_ -and ([string]$_).StartsWith($prefix)
    })
    if ($matches.Count -ne 1) {
        throw 'STOCK_QUANT_HOST_BROKER_DIAGNOSTIC_OUTPUT_INVALID'
    }
    $value = ([string]$matches[0]).Substring($prefix.Length)
    if ($value -cnotin @('true', 'false')) {
        throw 'STOCK_QUANT_HOST_BROKER_DIAGNOSTIC_OUTPUT_INVALID'
    }
    return $value -ceq 'true'
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
        if ($BrokerRequest.Operation -in @(
                'RUN_DAY001', 'RUN_M1_RESEARCH_DATA',
                'VERIFY_M1_TUSHARE_TOKEN',
                'RUN_M2_STRATEGY_RESEARCH_SMOKE',
                'CHECK_BAILIAN_CREDENTIAL_STATUS',
                'RUN_M3_AGENT_RESEARCH_SMOKE')) {
            $requiredBranch = if ($BrokerRequest.Operation -eq 'RUN_DAY001') {
                $integrationBranch
            } elseif ($BrokerRequest.Operation -eq
                    'RUN_M2_STRATEGY_RESEARCH_SMOKE' -and
                $branch -eq 'codex/1.4.0-m2-strategy-engine-ready') {
                'codex/1.4.0-m2-strategy-engine-ready'
            } elseif ($BrokerRequest.Operation -eq
                    'RUN_M2_STRATEGY_RESEARCH_SMOKE' -and
                $branch -eq 'codex/1.4.0-m3-agent-research-ready' -and
                [IO.Path]::GetFullPath($BrokerRequest.JarPath).Equals(
                    (Join-Path $paths.TargetRoot `
                        'quant-server-1.3.1-m3-agent-research-runner.jar'),
                    [StringComparison]::OrdinalIgnoreCase)) {
                'codex/1.4.0-m3-agent-research-ready'
            } elseif ($BrokerRequest.Operation -in @(
                    'CHECK_BAILIAN_CREDENTIAL_STATUS',
                    'RUN_M3_AGENT_RESEARCH_SMOKE') -and
                $branch -eq 'codex/1.4.0-m3-agent-research-ready' -and
                [IO.Path]::GetFullPath($BrokerRequest.JarPath).Equals(
                    (Join-Path $paths.TargetRoot `
                        'quant-server-1.3.1-m3-agent-research-runner.jar'),
                    [StringComparison]::OrdinalIgnoreCase)) {
                'codex/1.4.0-m3-agent-research-ready'
            } elseif ($branch -eq 'codex/1.4.0-m1-research-data-ready') {
                'codex/1.4.0-m1-research-data-ready'
            } else { $integrationBranch }
            git fetch --quiet origin $requiredBranch
            if ($LASTEXITCODE -ne 0) {
                throw 'STOCK_QUANT_HOST_BROKER_GIT_FETCH_FAILED'
            }
            $remote = (git rev-parse `
                "refs/remotes/origin/$requiredBranch").Trim()
            $divergence = (git rev-list --left-right --count `
                "$requiredBranch...origin/$requiredBranch").Trim() `
                -split '\s+'
            if ($branch -ne $requiredBranch -or
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

function Assert-M1UserApprovedPreflight {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    if (Test-Path -LiteralPath `
            "$($BrokerRequest.AuthorizationFile).consumed") {
        throw 'TUSHARE_M1_AUTHORIZATION_ALREADY_CONSUMED'
    }
    $output = @(& java "-Dloader.main=$m1PreflightClass" `
        -cp $BrokerRequest.JarPath `
        'org.springframework.boot.loader.launch.PropertiesLauncher' `
        "--authorization-file=$($BrokerRequest.AuthorizationFile)" 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0 -or
        $output -notcontains 'TUSHARE_M1_PREFLIGHT=PASS' -or
        $output -notcontains
            "TUSHARE_M1_GIT_COMMIT=$($BrokerRequest.GitCommit)" -or
        $output -notcontains
            "TUSHARE_M1_ARTIFACT_SHA256=$($BrokerRequest.JarSha256)" -or
        $output -notcontains
            "TUSHARE_M1_BUILD_PROOF_PATH=$($BrokerRequest.BuildProofPath)") {
        throw (Get-SafeMarker -Lines $output `
            -Name 'TUSHARE_M1_PREFLIGHT_REASON' `
            -Fallback 'STOCK_QUANT_HOST_BROKER_M1_AUTHORIZATION_INVALID')
    }
}

function Assert-M1TokenVerificationPreflight {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    if (Test-Path -LiteralPath `
            "$($BrokerRequest.AuthorizationFile).consumed") {
        throw 'TUSHARE_M1_TOKEN_VERIFICATION_AUTH_ALREADY_CONSUMED'
    }
    $output = @(& java `
        "-Dloader.main=$m1TokenVerificationPreflightClass" `
        -cp $BrokerRequest.JarPath `
        'org.springframework.boot.loader.launch.PropertiesLauncher' `
        "--authorization-file=$($BrokerRequest.AuthorizationFile)" 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0 -or $output -notcontains
            'TUSHARE_M1_TOKEN_VERIFICATION_PREFLIGHT=PASS' -or
        $output -notcontains
            "TUSHARE_M1_TOKEN_VERIFICATION_GIT_COMMIT=$($BrokerRequest.GitCommit)" -or
        $output -notcontains
            "TUSHARE_M1_TOKEN_VERIFICATION_ARTIFACT_SHA256=$($BrokerRequest.JarSha256)" -or
        $output -notcontains
            "TUSHARE_M1_TOKEN_VERIFICATION_BUILD_PROOF_PATH=$($BrokerRequest.BuildProofPath)" -or
        $output -notcontains
            'TUSHARE_M1_TOKEN_VERIFICATION_MAXIMUM_PROVIDER_REQUESTS=1') {
        throw (Get-SafeMarker -Lines $output `
            -Name 'TUSHARE_M1_TOKEN_VERIFICATION_PREFLIGHT_REASON' `
            -Fallback 'STOCK_QUANT_HOST_BROKER_M1_TOKEN_AUTH_INVALID')
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

function Invoke-BailianCredentialStatus {
    param(
        [Parameter(Mandatory = $true)]
        [object] $BrokerRequest
    )
    if ($BrokerRequest.AuthorizationStatus -ne
            'M3_BAILIAN_CREDENTIAL_READABILITY_ZERO_NETWORK' -or
        $null -ne $BrokerRequest.AuthorizationFile) {
        throw 'STOCK_QUANT_HOST_BROKER_M3_CREDENTIAL_SCOPE_INVALID'
    }
    $presence = @(& $credentialStatusScript -BailianStatus 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0 -or
        $presence -notcontains 'StockQuant/BailianApiKey=PRESENT' -or
        $presence -notcontains 'STOCK_QUANT_BAILIAN_CREDENTIAL_READY=True') {
        throw 'STOCK_QUANT_HOST_BROKER_BAILIAN_CREDENTIAL_MISSING'
    }
    $output = @(& java "-Dloader.main=$bailianCredentialProbeClass" `
        -cp $BrokerRequest.JarPath `
        'org.springframework.boot.loader.launch.PropertiesLauncher' 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0 -or
        $output -notcontains 'STOCK_QUANT_BAILIAN_CREDENTIAL_READ=SUCCESS' -or
        $output -notcontains 'STOCK_QUANT_BAILIAN_NETWORK_CALLS=0' -or
        $output -notcontains 'STOCK_QUANT_BAILIAN_OUTPUT_AUDIT=PASSED') {
        throw (Get-SafeMarker -Lines $output `
            -Name 'STOCK_QUANT_BAILIAN_CREDENTIAL_PROBE_REASON' `
            -Fallback 'STOCK_QUANT_HOST_BROKER_BAILIAN_CREDENTIAL_READ_FAILED')
    }
    return [ordered]@{
        credentialReady = $true
        readStatus = 'SUCCESS'
        networkCallCount = 0
        providerCallCount = 0
        retryCount = 0
        outputAudit = 'PASSED'
    }
}

function Invoke-TushareCredentialDiagnostic {
    param(
        [Parameter(Mandatory = $true)]
        [object] $BrokerRequest
    )
    $presence = @(& $credentialStatusScript -Status 2>&1)
    if ($LASTEXITCODE -ne 0 -or
        $presence -notcontains 'StockQuant/TushareToken=PRESENT') {
        throw 'STOCK_QUANT_HOST_BROKER_CREDENTIALS_MISSING'
    }
    $output = @(& java "-Dloader.main=$credentialProbeClass" `
        -cp $BrokerRequest.JarPath `
        'org.springframework.boot.loader.launch.PropertiesLauncher' 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0 -or
        $output -notcontains 'STOCK_QUANT_SECRET_READ=SUCCESS' -or
        $output -notcontains 'STOCK_QUANT_PROVIDER_CALLS=0') {
        $reason = Get-SafeMarker -Lines $output `
            -Name 'STOCK_QUANT_SECRET_PROBE_REASON' `
            -Fallback 'STOCK_QUANT_HOST_BROKER_CREDENTIAL_DIAGNOSTIC_FAILED'
        throw $reason
    }
    $length = Get-SafeIntegerMarker -Lines $output `
        -Name 'STOCK_QUANT_SECRET_LENGTH'
    $format = Get-SafeMarker -Lines $output `
        -Name 'STOCK_QUANT_SECRET_FORMAT' `
        -Fallback 'INVALID_DIAGNOSTIC_OUTPUT'
    $stable = Get-SafeBooleanMarker -Lines $output `
        -Name 'STOCK_QUANT_SECRET_FINGERPRINT_STABLE'
    return [ordered]@{
        readStatus = 'SUCCESS'
        valueLength = $length
        valueFormat = $format
        fingerprintStable = $stable
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

function Invoke-M1TokenVerification {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    Assert-M1TokenVerificationPreflight -BrokerRequest $BrokerRequest
    $runnerResult = Join-Path $paths.Results `
        "$($BrokerRequest.RequestId).m1-token-verification.json"
    if (Test-Path -LiteralPath $runnerResult) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_ALREADY_EXISTS'
    }
    $output = @(& $m1TokenVerificationScript `
        -AuthorizationFile $BrokerRequest.AuthorizationFile `
        -ResultFile $runnerResult -ArtifactPath $BrokerRequest.JarPath `
        2>&1 | ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path -LiteralPath $runnerResult -PathType Leaf) {
            try {
                $failed = Get-Content -LiteralPath $runnerResult `
                    -Raw -Encoding UTF8 | ConvertFrom-Json
                $calls = [int]$failed.providerCallCount
                if ($calls -ge 0 -and $calls -le 1 -and
                    [int]$failed.retryCount -eq 0) {
                    $script:failureSummary = [ordered]@{
                        verificationId = [string]$failed.verificationId
                        providerCallCount = $calls
                        retryCount = 0
                        httpStatus = $failed.httpStatus
                        providerCode = $failed.providerCode
                        providerMessageCategory =
                            [string]$failed.providerMessageCategory
                        responseJsonValid =
                            [bool]$failed.responseJsonValid
                        targetRowPresent =
                            [bool]$failed.targetRowPresent
                        outputAudit = $(if ($failed.outputAudit.clean) {
                            'PASSED'
                        } else { 'FAILED' })
                    }
                }
            } catch { $script:failureSummary = $null }
        }
        $failureStage = Get-SafeMarker -Lines $output `
            -Name 'STOCK_QUANT_M1_TOKEN_VERIFICATION_FAILURE_STAGE' `
            -Fallback 'FAILED_VALIDATION'
        $failureReason = Get-SafeMarker -Lines $output `
            -Name 'STOCK_QUANT_M1_TOKEN_VERIFICATION_FAILURE_REASON' `
            -Fallback 'STOCK_QUANT_HOST_BROKER_M1_TOKEN_VERIFICATION_FAILED'
        throw [InvalidOperationException]::new(
            $failureReason + '__STAGE__' + $failureStage)
    }
    if (-not (Test-Path -LiteralPath $runnerResult -PathType Leaf)) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_MISSING'
    }
    $verification = Get-Content -LiteralPath $runnerResult `
        -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($verification.status -ne 'SUCCEEDED' -or
        [int]$verification.providerCallCount -ne 1 -or
        [int]$verification.retryCount -ne 0 -or
        $verification.endpoint -ne 'daily' -or
        [int]$verification.providerCode -ne 0 -or
        -not $verification.responseJsonValid -or
        -not $verification.targetRowPresent -or
        -not $verification.outputAudit.clean -or
        $verification.prohibitedEffects.databaseConnected -or
        $verification.prohibitedEffects.databaseWritten) {
        throw 'STOCK_QUANT_HOST_BROKER_M1_TOKEN_VERIFICATION_RESULT_INVALID'
    }
    return [ordered]@{
        verificationId = [string]$verification.verificationId
        providerCallCount = 1
        retryCount = 0
        endpoint = 'daily'
        httpStatus = [int]$verification.httpStatus
        providerCode = 0
        providerMessageCategory = 'SUCCESS'
        responseJsonValid = $true
        targetRowPresent = $true
        outputAudit = 'PASSED'
        databaseConnected = $false
        databaseWritten = $false
        sanitizedResult = $runnerResult
    }
}

function Invoke-M1ResearchData {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    Assert-M1UserApprovedPreflight -BrokerRequest $BrokerRequest
    $runnerResult = Join-Path $paths.Results `
        "$($BrokerRequest.RequestId).m1.json"
    if (Test-Path -LiteralPath $runnerResult) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_ALREADY_EXISTS'
    }
    $output = @(& $m1RunnerScript `
        -AuthorizationFile $BrokerRequest.AuthorizationFile `
        -ResultFile $runnerResult -ArtifactPath $BrokerRequest.JarPath `
        -SecretMode WINDOWS_CREDENTIAL_MANAGER 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path -LiteralPath $runnerResult -PathType Leaf) {
            try {
                $failed = Get-Content -LiteralPath $runnerResult `
                    -Raw -Encoding UTF8 | ConvertFrom-Json
                $calls = [int]$failed.providerCallCount
                if ($calls -ge 0 -and $calls -le 6 -and
                    [int]$failed.retryCount -eq 0) {
                    $script:failureSummary = [ordered]@{
                        runId = [string]$failed.runId
                        providerCallCount = $calls
                        retryCount = 0
                        captureBatchIds = @($failed.captureBatchIds)
                        newObservationCount =
                            [int]$failed.newObservationCount
                        idempotentChainTailCount =
                            [int]$failed.idempotentChainTailCount
                        outputAudit = $(if ($failed.outputAudit.clean) {
                            'PASSED'
                        } else { 'FAILED' })
                    }
                }
            } catch { $script:failureSummary = $null }
        }
        $failureStage = Get-SafeMarker -Lines $output `
            -Name 'STOCK_QUANT_M1_FAILURE_STAGE' `
            -Fallback 'FAILED_VALIDATION'
        $failureReason = Get-SafeMarker -Lines $output `
            -Name 'STOCK_QUANT_M1_FAILURE_REASON' `
            -Fallback 'STOCK_QUANT_HOST_BROKER_M1_FAILED'
        throw [InvalidOperationException]::new(
            $failureReason + '__STAGE__' + $failureStage)
    }
    if (-not (Test-Path -LiteralPath $runnerResult -PathType Leaf)) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_MISSING'
    }
    $m1 = Get-Content -LiteralPath $runnerResult -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($m1.status -ne 'SUCCEEDED' -or
        [int]$m1.providerCallCount -ne 6 -or [int]$m1.retryCount -ne 0 -or
        [int]$m1.endpointCallCounts.daily -ne 2 -or
        [int]$m1.endpointCallCounts.adj_factor -ne 2 -or
        [int]$m1.endpointCallCounts.trade_cal -ne 2 -or
        -not $m1.researchDataset.typedFactReadback -or
        -not $m1.researchDataset.systemKnowledgeReadback -or
        -not $m1.researchDataset.formulaOnlyQfq -or
        -not $m1.researchDataset.dataQuality -or
        -not $m1.researchDataset.noFutureDataLeakage -or
        -not $m1.researchDataset.m2Readable -or
        -not $m1.outputAudit.clean) {
        throw 'STOCK_QUANT_HOST_BROKER_M1_RESULT_INVALID'
    }
    return [ordered]@{
        runId = [string]$m1.runId
        providerCallCount = [int]$m1.providerCallCount
        retryCount = [int]$m1.retryCount
        stageProviderCallsAfter = [int]$m1.stageProviderCallsAfter
        cumulativeProviderCallsAfter = [int]$m1.cumulativeProviderCallsAfter
        captureBatchIds = @($m1.captureBatchIds)
        receivedFactCount = [int]$m1.receivedFactCount
        newObservationCount = [int]$m1.newObservationCount
        idempotentChainTailCount = [int]$m1.idempotentChainTailCount
        typedFactReadback = $true
        systemKnowledgeReadback = $true
        formulaOnlyQfq = $true
        qfqBarCount = [int]$m1.researchDataset.qfqBarCount
        dataQuality = $true
        noFutureDataLeakage = $true
        m2Readable = $true
        outputAudit = 'PASSED'
        sanitizedResult = $runnerResult
    }
}

function Invoke-M2StrategyResearchSmoke {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    if ($BrokerRequest.AuthorizationStatus -ne
            'M2_STAGE_APPROVED_ZERO_PROVIDER_READ_ONLY' -or
        $BrokerRequest.AuthorizationFile -ne $null) {
        throw 'STOCK_QUANT_HOST_BROKER_M2_SCOPE_INVALID'
    }
    $runnerResult = Join-Path $paths.Results `
        "$($BrokerRequest.RequestId).m2.json"
    if (Test-Path -LiteralPath $runnerResult) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_ALREADY_EXISTS'
    }
    $executionId = $BrokerRequest.RequestId -replace '^SQHB_', 'M2SMOKE_'
    $output = @(& $m2RunnerScript `
        -ResultFile $runnerResult -ArtifactPath $BrokerRequest.JarPath `
        -ExecutionId $executionId 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path -LiteralPath $runnerResult -PathType Leaf) {
            try {
                $failed = Get-Content -LiteralPath $runnerResult `
                    -Raw -Encoding UTF8 | ConvertFrom-Json
                if ([int]$failed.providerCallCount -eq 0 -and
                    [int]$failed.databaseWriteCount -eq 0) {
                    $script:failureSummary = [ordered]@{
                        executionId = [string]$failed.executionId
                        providerCallCount = 0
                        retryCount = 0
                        databaseWriteCount = 0
                        outputAudit = $(if ($failed.outputAudit.clean) {
                            'PASSED'
                        } else { 'FAILED' })
                    }
                }
            } catch { $script:failureSummary = $null }
        }
        throw (Get-SafeMarker -Lines $output `
            -Name 'M2_STRATEGY_RESEARCH_FAILURE_REASON' `
            -Fallback 'STOCK_QUANT_HOST_BROKER_M2_FAILED')
    }
    if (-not (Test-Path -LiteralPath $runnerResult -PathType Leaf)) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_MISSING'
    }
    $m2 = Get-Content -LiteralPath $runnerResult -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($m2.schemaVersion -ne
            'M2_STRATEGY_RESEARCH_SMOKE_RESULT_V1' -or
        $m2.status -ne 'SUCCEEDED' -or
        [int]$m2.providerCallCount -ne 0 -or
        [int]$m2.databaseWriteCount -ne 0 -or
        -not $m2.databaseReadOnly -or
        -not $m2.databaseSnapshotUnchanged -or
        -not $m2.outputAudit.clean -or
        -not $m2.research.accountingInvariant -or
        -not $m2.research.lookAheadGuard -or
        -not $m2.research.deterministicReplay -or
        -not $m2.research.typedFactReadback -or
        -not $m2.research.systemKnowledgeReadback -or
        -not $m2.research.dataQuality -or
        -not $m2.research.noFutureDataLeakage) {
        throw 'STOCK_QUANT_HOST_BROKER_M2_RESULT_INVALID'
    }
    return [ordered]@{
        executionId = [string]$m2.executionId
        providerCallCount = 0
        retryCount = 0
        databaseWriteCount = 0
        datasetVersion = [string]$m2.research.datasetVersion
        securityCount = [int]$m2.research.securityCount
        openSessionCount = [int]$m2.research.openSessionCount
        qfqBarCount = [int]$m2.research.qfqBarCount
        fillCount = [int]$m2.research.fillCount
        deterministicFingerprint =
            [string]$m2.research.deterministicFingerprint
        finalEquity = [string]$m2.research.finalEquity
        totalReturn = [string]$m2.research.totalReturn
        maxDrawdown = [string]$m2.research.maxDrawdown
        sharpeRatio = [string]$m2.research.sharpeRatio
        turnover = [string]$m2.research.turnover
        accountingInvariant = $true
        lookAheadGuard = $true
        deterministicReplay = $true
        typedFactReadback = $true
        systemKnowledgeReadback = $true
        dataQuality = $true
        noFutureDataLeakage = $true
        databaseReadOnly = $true
        databaseSnapshotUnchanged = $true
        outputAudit = 'PASSED'
        sanitizedResult = $runnerResult
    }
}

function Invoke-M3AgentResearchSmoke {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    if ($BrokerRequest.AuthorizationStatus -ne
            'M3_USER_APPROVED_BAILIAN_SMOKE_CNY_5_00' -or
        $null -ne $BrokerRequest.AuthorizationFile) {
        throw 'STOCK_QUANT_HOST_BROKER_M3_SCOPE_INVALID'
    }
    $stageBudget = Get-M3BailianStageBudget -BrokerRequest $BrokerRequest
    $runnerResult = Join-Path $paths.Results `
        "$($BrokerRequest.RequestId).m3-bailian.json"
    if (Test-Path -LiteralPath $runnerResult) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_ALREADY_EXISTS'
    }
    $executionId = $BrokerRequest.RequestId -replace '^SQHB_', 'M3SMOKE_'
    $reportDirectory = Join-Path $paths.TargetRoot 'agent-research\reports'
    $output = @(& $m3RunnerScript `
        -ResultFile $runnerResult -ReportDirectory $reportDirectory `
        -ArtifactPath $BrokerRequest.JarPath -ExecutionId $executionId `
        -ModelMode BAILIAN `
        -MaximumCostCny $stageBudget.RemainingCostCny 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path -LiteralPath $runnerResult -PathType Leaf) {
            try {
                $failed = Get-Content -LiteralPath $runnerResult `
                    -Raw -Encoding UTF8 | ConvertFrom-Json
                if ([int]$failed.providerCallCount -eq 0 -and
                    [int]$failed.databaseWriteCount -eq 0) {
                    $script:failureSummary = [ordered]@{
                        executionId = [string]$failed.executionId
                        providerCallCount = 0
                        retryCount = 0
                        databaseWriteCount = 0
                        externalModelCallCount = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [int]$failed.modelDiagnostics.networkCallCount
                        } elseif ($null -ne $failed.research) {
                            [int]$failed.research.modelCallCount
                        } else { 0 })
                        externalModelCompletedCallCount = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [int]$failed.modelDiagnostics.completedCallCount
                        } else { 0 })
                        modelFailureSource = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [string]$failed.modelDiagnostics.failureSource
                        } else { 'LEGACY_UNAVAILABLE' })
                        modelHttpStatus = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [int]$failed.modelDiagnostics.httpStatus
                        } else { 0 })
                        modelProviderCode = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [string]$failed.modelDiagnostics.providerCode
                        } else { 'NONE' })
                        modelProviderCategory = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [string]$failed.modelDiagnostics.providerCategory
                        } else { 'NONE' })
                        modelProviderMessageCategory = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [string]$failed.modelDiagnostics.providerMessageCategory
                        } else { 'NONE' })
                        modelResponseContentTypeCategory = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [string]$failed.modelDiagnostics.responseContentTypeCategory
                        } else { 'NONE' })
                        modelResponseJsonCategory = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [string]$failed.modelDiagnostics.responseJsonCategory
                        } else { 'NOT_EVALUATED' })
                        modelAccountedCostCny = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [string]$failed.modelDiagnostics.accountedCost
                        } else { '0.50' })
                        stagePriorAccountedCostCny =
                            [string]$stageBudget.PriorCostCny
                        stagePriorModelAttemptCount =
                            [int]$stageBudget.PriorModelAttemptCount
                        stageRemainingCostBeforeRunCny =
                            [string]$stageBudget.RemainingCostCny
                        outputAudit = $(if ($failed.outputAudit.clean) {
                            'PASSED'
                        } else { 'FAILED' })
                    }
                }
            } catch { $script:failureSummary = $null }
        }
        throw (Get-SafeMarker -Lines $output `
            -Name 'M3_AGENT_RESEARCH_FAILURE_REASON' `
            -Fallback 'STOCK_QUANT_HOST_BROKER_M3_FAILED')
    }
    if (-not (Test-Path -LiteralPath $runnerResult -PathType Leaf)) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_MISSING'
    }
    $m3 = Get-Content -LiteralPath $runnerResult -Raw -Encoding UTF8 |
        ConvertFrom-Json
    $runs = @($m3.research.agentRuns)
    $roles = @($runs | Select-Object -ExpandProperty agentRole -Unique)
    $usage = $m3.research.totalModelUsage
    $modelDiagnostics = $m3.modelDiagnostics
    [decimal]$estimatedCost = [decimal]::Parse(
        [string]$usage.estimatedCost,
        [Globalization.NumberStyles]::Number,
        [Globalization.CultureInfo]::InvariantCulture)
    [decimal]$diagnosticCost = $(if ($null -ne $modelDiagnostics) {
        [decimal]::Parse([string]$modelDiagnostics.accountedCost,
            [Globalization.NumberStyles]::Number,
            [Globalization.CultureInfo]::InvariantCulture)
    } else { [decimal]-1.00 })
    if ($m3.schemaVersion -ne 'M3_AGENT_RESEARCH_SMOKE_RESULT_V1' -or
        $m3.status -ne 'SUCCEEDED' -or
        [int]$m3.providerCallCount -ne 0 -or
        [int]$m3.databaseWriteCount -ne 0 -or
        -not $m3.databaseReadOnly -or
        -not $m3.databaseSnapshotUnchanged -or
        -not $m3.outputAudit.clean -or
        -not $m3.research.researchOnly -or
        $m3.research.providerCalled -or $m3.research.shadowStarted -or
        $m3.research.tradingStarted -or $m3.research.deterministic -or
        [int]$m3.research.modelCallCount -ne 13 -or
        $null -eq $modelDiagnostics -or
        [string]$modelDiagnostics.failureSource -ne 'NONE' -or
        [int]$modelDiagnostics.networkCallCount -ne 13 -or
        [int]$modelDiagnostics.completedCallCount -ne 13 -or
        [string]$modelDiagnostics.costCurrency -ne 'CNY' -or
        $diagnosticCost -ne $estimatedCost -or
        $diagnosticCost -gt [decimal]$stageBudget.RemainingCostCny -or
        [int]$m3.research.toolCallCount -ne 4 -or
        [int]$usage.inputTokens -le 0 -or
        [int]$usage.outputTokens -le 0 -or
        $estimatedCost -le 0 -or $estimatedCost -gt [decimal]5.00 -or
        [string]$usage.costCurrency -ne 'CNY' -or
        $runs.Count -ne 13 -or $roles.Count -ne 7 -or
        @($runs | Where-Object {
            $_.modelProvider -ne 'BAILIAN' -or
            $_.model -ne 'qwen3.7-plus'
        }).Count -ne 0 -or
        -not $m3.research.dataset.typedFactReadback -or
        -not $m3.research.dataset.systemKnowledgeReadback -or
        -not $m3.research.dataset.formulaOnlyQfq -or
        -not $m3.research.dataset.dataQualityPassed -or
        -not $m3.research.dataset.noFutureDataLeakage -or
        @($m3.research.strategyExperiments.experiments).Count -ne 4 -or
        -not $m3.research.risk.accountingPassed -or
        -not $m3.research.risk.lookAheadPassed -or
        -not $m3.research.criticReview.correctionApplied -or
        -not (Test-Path -LiteralPath $m3.reportFile -PathType Leaf)) {
        throw 'STOCK_QUANT_HOST_BROKER_M3_RESULT_INVALID'
    }
    return [ordered]@{
        executionId = [string]$m3.executionId
        providerCallCount = 0
        retryCount = 0
        databaseWriteCount = 0
        externalModelCallCount = 13
        modelInputUnits = [int]$usage.inputTokens
        modelOutputUnits = [int]$usage.outputTokens
        estimatedCostCny = $estimatedCost.ToString(
            [Globalization.CultureInfo]::InvariantCulture)
        hardCostLimitCny = '5.00'
        costCurrency = 'CNY'
        model = 'qwen3.7-plus'
        stageAttempt = [int]$stageBudget.AttemptNumber
        stagePriorAccountedCostCny = [string]$stageBudget.PriorCostCny
        stagePriorModelAttemptCount =
            [int]$stageBudget.PriorModelAttemptCount
        stageRemainingCostBeforeRunCny =
            [string]$stageBudget.RemainingCostCny
        agentRoleCount = 7
        toolCallCount = 4
        researchStatus = [string]$m3.research.status
        typedFactReadback = $true
        systemKnowledgeReadback = $true
        formulaOnlyQfq = $true
        dataQuality = $true
        noFutureDataLeakage = $true
        criticCorrectionApplied = $true
        databaseReadOnly = $true
        databaseSnapshotUnchanged = $true
        outputAudit = 'PASSED'
        sanitizedResult = $runnerResult
        reportFile = [string]$m3.reportFile
    }
}

function Get-M3BailianStageBudget {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    $operationMarker = 'RUN_M3_AGENT_RESEARCH_SMOKE'
    [decimal]$hardLimit = [decimal]5.00
    [decimal]$legacyFailureReserve = [decimal]0.50
    $maximumModelAttempts = 4
    [decimal]$used = [decimal]0.00
    $modelAttempts = 0
    $seen = @{}
    $prior = @(Get-ChildItem -LiteralPath $paths.Requests -File |
        Where-Object { $_.FullName -ne $processingPath } |
        Where-Object {
            if ($_.Length -le 0 -or $_.Length -gt 65536) { return $false }
            $content = Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8
            return $content -match "(?m)^operation=$operationMarker$" -and
                $content -match '(?m)^provider=BAILIAN$'
        })
    foreach ($file in $prior) {
        $content = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
        $idMatch = [regex]::Match($content,
            '(?m)^request\.id=(SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12})$')
        if (-not $idMatch.Success -or $seen.ContainsKey($idMatch.Groups[1].Value)) {
            throw 'M3_BAILIAN_STAGE_BUDGET_LEDGER_INVALID'
        }
        $requestId = $idMatch.Groups[1].Value
        $seen[$requestId] = $true
        $runnerResult = Join-Path $paths.Results "$requestId.m3-bailian.json"
        if (-not (Test-Path -LiteralPath $runnerResult -PathType Leaf)) {
            $brokerResult = Join-Path $paths.Results "$requestId.result.json"
            if (-not (Test-Path -LiteralPath $brokerResult -PathType Leaf)) {
                throw 'M3_BAILIAN_STAGE_BUDGET_LEDGER_INCOMPLETE'
            }
            $terminal = Get-Content -LiteralPath $brokerResult -Raw `
                -Encoding UTF8 | ConvertFrom-Json
            if ($terminal.schemaVersion -ne
                    'STOCK_QUANT_HOST_BROKER_RESULT_V1' -or
                $terminal.requestId -ne $requestId -or
                $terminal.operation -ne $operationMarker -or
                $terminal.status -notin @('FAILED', 'REJECTED') -or
                [int]$terminal.providerCallCount -ne 0 -or
                [int]$terminal.retryCount -ne 0) {
                throw 'M3_BAILIAN_STAGE_BUDGET_LEDGER_INVALID'
            }
            continue
        }
        $priorResult = Get-Content -LiteralPath $runnerResult -Raw `
            -Encoding UTF8 | ConvertFrom-Json
        if ($priorResult.schemaVersion -ne
                'M3_AGENT_RESEARCH_SMOKE_RESULT_V1' -or
            $priorResult.status -notin @('SUCCEEDED', 'FAILED') -or
            [int]$priorResult.providerCallCount -ne 0 -or
            [int]$priorResult.databaseWriteCount -ne 0) {
            throw 'M3_BAILIAN_STAGE_BUDGET_LEDGER_INVALID'
        }
        [decimal]$cost = $legacyFailureReserve
        $diagnosticProperty =
            $priorResult.PSObject.Properties['modelDiagnostics']
        if ($null -ne $diagnosticProperty -and
            $null -ne $diagnosticProperty.Value) {
            $modelDiagnostics = $diagnosticProperty.Value
            $cost = [decimal]::Parse(
                [string]$modelDiagnostics.accountedCost,
                [Globalization.NumberStyles]::Number,
                [Globalization.CultureInfo]::InvariantCulture)
            if ($cost -lt 0 -or $cost -gt $hardLimit -or
                [string]$modelDiagnostics.costCurrency -ne 'CNY' -or
                [int]$modelDiagnostics.networkCallCount -lt 0 -or
                [int]$modelDiagnostics.networkCallCount -gt 13) {
                throw 'M3_BAILIAN_STAGE_BUDGET_LEDGER_INVALID'
            }
            if ([int]$modelDiagnostics.networkCallCount -gt 0) {
                $modelAttempts++
            }
        } else {
            $modelAttempts++
            $brokerResult = Join-Path $paths.Results `
                "$requestId.result.json"
            if (Test-Path -LiteralPath $brokerResult -PathType Leaf) {
                $terminal = Get-Content -LiteralPath $brokerResult -Raw `
                    -Encoding UTF8 | ConvertFrom-Json
                $remainingProperty = $terminal.summary.PSObject.Properties[
                    'stageRemainingCostBeforeRunCny']
                if ($null -ne $remainingProperty) {
                    $cost = [decimal]::Parse(
                        [string]$remainingProperty.Value,
                        [Globalization.NumberStyles]::Number,
                        [Globalization.CultureInfo]::InvariantCulture)
                    if ($cost -le 0 -or $cost -gt $hardLimit) {
                        throw 'M3_BAILIAN_STAGE_BUDGET_LEDGER_INVALID'
                    }
                }
            }
        }
        $used += $cost
    }
    if ($modelAttempts -ge $maximumModelAttempts -or $prior.Count -ge 6 -or
        $used -ge $hardLimit) {
        throw 'M3_BAILIAN_STAGE_BUDGET_EXHAUSTED'
    }
    [decimal]$remaining = $hardLimit - $used
    return [pscustomobject]@{
        AttemptNumber = $modelAttempts + 1
        PriorRequestCount = $prior.Count
        PriorModelAttemptCount = $modelAttempts
        PriorCostCny = $used.ToString(
            [Globalization.CultureInfo]::InvariantCulture)
        RemainingCostCny = $remaining.ToString(
            [Globalization.CultureInfo]::InvariantCulture)
        HardLimitCny = '5.00'
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

function Start-BusyHeartbeatPump {
    $timer = [Timers.Timer]::new($pollIntervalMilliseconds)
    $timer.AutoReset = $true
    $sourceIdentifier = "StockQuantHostBrokerBusyHeartbeat_$PID"
    $context = [pscustomobject]@{
        GitCommit = $brokerGitCommit
        WindowsUser = $identity
        ProcessId = $PID
        StartedAt = $brokerStartedAt
    }
    $job = Register-ObjectEvent -InputObject $timer -EventName Elapsed `
        -SourceIdentifier $sourceIdentifier -MessageData $context -Action {
            try {
                Write-StockQuantHostBrokerHeartbeat `
                    -GitCommit $Event.MessageData.GitCommit `
                    -WindowsUser $Event.MessageData.WindowsUser `
                    -ProcessId $Event.MessageData.ProcessId `
                    -StartedAt $Event.MessageData.StartedAt -State BUSY |
                    Out-Null
            } catch {
                # The foreground result remains fail-closed; stale health is rejected.
            }
        }
    $timer.Start()
    return [pscustomobject]@{
        Timer = $timer
        Job = $job
        SourceIdentifier = $sourceIdentifier
    }
}

function Stop-BusyHeartbeatPump {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Pump
    )
    $Pump.Timer.Stop()
    Unregister-Event -SourceIdentifier $Pump.SourceIdentifier `
        -ErrorAction SilentlyContinue
    Remove-Job -Job $Pump.Job -Force -ErrorAction SilentlyContinue
    $Pump.Timer.Dispose()
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
            'DIAGNOSE_TUSHARE_CREDENTIAL' {
                Invoke-TushareCredentialDiagnostic -BrokerRequest $request
                break
            }
            'RUN_FAKE_E2E' { Invoke-FakeE2e -BrokerRequest $request; break }
            'RUN_DAY001' { Invoke-Day001 -BrokerRequest $request; break }
            'RUN_M1_RESEARCH_DATA' {
                Invoke-M1ResearchData -BrokerRequest $request
                break
            }
            'VERIFY_M1_TUSHARE_TOKEN' {
                Invoke-M1TokenVerification -BrokerRequest $request
                break
            }
            'RUN_M2_STRATEGY_RESEARCH_SMOKE' {
                Invoke-M2StrategyResearchSmoke -BrokerRequest $request
                break
            }
            'CHECK_BAILIAN_CREDENTIAL_STATUS' {
                Invoke-BailianCredentialStatus -BrokerRequest $request
                break
            }
            'RUN_M3_AGENT_RESEARCH_SMOKE' {
                Invoke-M3AgentResearchSmoke -BrokerRequest $request
                break
            }
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
        $heartbeatPump = Start-BusyHeartbeatPump
        try {
            Invoke-ClaimedRequest -Candidate $pending[0]
        } finally {
            Stop-BusyHeartbeatPump -Pump $heartbeatPump
        }
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
