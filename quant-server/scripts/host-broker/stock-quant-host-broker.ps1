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
$m4RunnerScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\run-m4-shadow-research.ps1'
$researchSelectionRunnerScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\run-research-selection.ps1'
$productionRoot = Join-Path $paths.TargetRoot 'stock-quant-production'
$productionPidFile = Join-Path $productionRoot 'backend.pid.json'
$productionAutostartFile = Join-Path $productionRoot 'backend.autostart.json'
$productionRecoveryStatusFile = Join-Path $productionRoot `
    'backend.recovery-status.json'
$productionStdout = Join-Path $productionRoot 'logs\backend.stdout.log'
$productionStderr = Join-Path $productionRoot 'logs\backend.stderr.log'
$productionMaximumRestarts = 3
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
                'RUN_M3_AGENT_RESEARCH_SMOKE',
                'RUN_M4_SHADOW_RESEARCH',
                'RUN_RESEARCH_SELECTION',
                'START_RESEARCH_PRODUCTION',
                'STOP_RESEARCH_PRODUCTION',
                'CHECK_RESEARCH_PRODUCTION_STATUS')) {
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
            } elseif ($BrokerRequest.Operation -eq
                    'RUN_M4_SHADOW_RESEARCH' -and
                $branch -eq 'codex/1.4.0-m4-shadow-research-ready' -and
                [IO.Path]::GetFullPath($BrokerRequest.JarPath).Equals(
                    (Join-Path $paths.TargetRoot `
                        'quant-server-1.3.1-m4-shadow-research-runner.jar'),
                    [StringComparison]::OrdinalIgnoreCase)) {
                'codex/1.4.0-m4-shadow-research-ready'
            } elseif ($BrokerRequest.Operation -eq
                    'RUN_M4_SHADOW_RESEARCH' -and
                $branch -eq 'codex/1.4.0-m6-research-production-ready' -and
                [IO.Path]::GetFullPath($BrokerRequest.JarPath).Equals(
                    (Join-Path $paths.TargetRoot `
                        'quant-server-1.3.1-m4-shadow-research-runner.jar'),
                    [StringComparison]::OrdinalIgnoreCase)) {
                'codex/1.4.0-m6-research-production-ready'
            } elseif ($BrokerRequest.Operation -eq
                    'RUN_RESEARCH_SELECTION' -and $branch -in @(
                     'codex/1.4.0-v1.0.1-research-selection-usability',
                     'codex/1.4.0-v1.0.3-research-selection-runtime-fix',
                     'codex/1.4.0-v1.0.7-intraday-research-selection-anchor-fix',
                     'codex/1.4.0-v1.0.9-full-mainboard-universe') -and
                [IO.Path]::GetFullPath($BrokerRequest.JarPath).Equals(
                    (Join-Path $paths.TargetRoot `
                        'quant-server-1.3.1-research-selection-runner.jar'),
                    [StringComparison]::OrdinalIgnoreCase)) {
                $branch
            } elseif ($BrokerRequest.Operation -in @(
                    'START_RESEARCH_PRODUCTION',
                    'STOP_RESEARCH_PRODUCTION',
                    'CHECK_RESEARCH_PRODUCTION_STATUS') -and
                $branch -eq
                    'codex/1.4.0-v1.0.1-research-selection-usability' -and
                [IO.Path]::GetFullPath($BrokerRequest.JarPath).Equals(
                    (Join-Path $paths.TargetRoot `
                        'quant-server-1.3.1-research-production.jar'),
                    [StringComparison]::OrdinalIgnoreCase)) {
                'codex/1.4.0-v1.0.1-research-selection-usability'
            } elseif ($BrokerRequest.Operation -in @(
                    'START_RESEARCH_PRODUCTION',
                    'STOP_RESEARCH_PRODUCTION',
                    'CHECK_RESEARCH_PRODUCTION_STATUS') -and
                $branch -eq
                    'codex/1.4.0-v1.0.2-startup-self-heal-fix' -and
                [IO.Path]::GetFullPath($BrokerRequest.JarPath).Equals(
                    (Join-Path $paths.TargetRoot `
                        'quant-server-1.3.1-research-production.jar'),
                    [StringComparison]::OrdinalIgnoreCase)) {
                'codex/1.4.0-v1.0.2-startup-self-heal-fix'
            } elseif ($BrokerRequest.Operation -in @(
                    'START_RESEARCH_PRODUCTION',
                    'STOP_RESEARCH_PRODUCTION',
                    'CHECK_RESEARCH_PRODUCTION_STATUS') -and
                $branch -in @(
                     'codex/1.4.0-v1.0.3-research-selection-runtime-fix',
                     'codex/1.4.0-v1.0.7-intraday-research-selection-anchor-fix',
                     'codex/1.4.0-v1.0.9-full-mainboard-universe') -and
                [IO.Path]::GetFullPath($BrokerRequest.JarPath).Equals(
                    (Join-Path $paths.TargetRoot `
                        'quant-server-1.3.1-research-production.jar'),
                    [StringComparison]::OrdinalIgnoreCase)) {
                $branch
            } elseif ($BrokerRequest.Operation -in @(
                    'START_RESEARCH_PRODUCTION',
                    'STOP_RESEARCH_PRODUCTION',
                    'CHECK_RESEARCH_PRODUCTION_STATUS') -and
                $branch -eq 'codex/1.4.0-m6-research-production-ready' -and
                [IO.Path]::GetFullPath($BrokerRequest.JarPath).Equals(
                    (Join-Path $paths.TargetRoot `
                        'quant-server-1.3.1-research-production.jar'),
                    [StringComparison]::OrdinalIgnoreCase)) {
                'codex/1.4.0-m6-research-production-ready'
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
            'M3_USER_APPROVED_BAILIAN_SMOKE_TRANCHE_2_CNY_5_00' -or
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
        $sanitizedRunnerReason = $null
        if (Test-Path -LiteralPath $runnerResult -PathType Leaf) {
            try {
                $failed = Get-Content -LiteralPath $runnerResult `
                    -Raw -Encoding UTF8 | ConvertFrom-Json
                if ([int]$failed.providerCallCount -eq 0 -and
                    [int]$failed.databaseWriteCount -eq 0) {
                    if ([string]$failed.reason -match
                            '^[A-Z][A-Z0-9_]{7,127}$') {
                        $sanitizedRunnerReason = [string]$failed.reason
                    }
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
                        providerRequestCount = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [int]$failed.modelDiagnostics.networkCallCount
                        } else { 0 })
                        externalModelCompletedCallCount = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [int]$failed.modelDiagnostics.completedCallCount
                        } else { 0 })
                        modelInputUnits = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [int]$failed.modelDiagnostics.inputTokenCount
                        } else { 0 })
                        modelOutputUnits = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [int]$failed.modelDiagnostics.outputTokenCount
                        } else { 0 })
                        modelReasoningUnits = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [int]$failed.modelDiagnostics.reasoningTokenCount
                        } else { 0 })
                        modelTotalUnits = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            [int]$failed.modelDiagnostics.totalTokenCount
                        } else { 0 })
                        modelCallTelemetry = $(if ($null -ne
                                $failed.modelDiagnostics) {
                            @(ConvertTo-StockQuantM3CallTelemetrySummary `
                                -Telemetry @(
                                    $failed.modelDiagnostics.callTelemetry))
                        } else { @() })
                        providerReportedActualCostCny = $null
                        actualCostStatus = 'NOT_PROVIDED_BY_API'
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
                        budgetTranche = [string]$stageBudget.BudgetTranche
                        outputAudit = $(if ($failed.outputAudit.clean) {
                            'PASSED'
                        } else { 'FAILED' })
                    }
                }
            } catch { $script:failureSummary = $null }
        }
        if ($null -ne $sanitizedRunnerReason) {
            throw $sanitizedRunnerReason
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
        [int]$modelDiagnostics.inputTokenCount -ne
            [int]$usage.inputTokens -or
        [int]$modelDiagnostics.outputTokenCount -ne
            [int]$usage.outputTokens -or
        [int]$modelDiagnostics.reasoningTokenCount -ne
            [int]$usage.reasoningTokens -or
        [int]$modelDiagnostics.totalTokenCount -ne
            [int]$usage.totalTokens -or
        @($modelDiagnostics.callTelemetry).Count -ne 13 -or
        @($modelDiagnostics.callTelemetry | Where-Object {
            $_.status -ne 'COMPLETED' -or
            $_.actualCostStatus -ne 'NOT_PROVIDED_BY_API'
        }).Count -ne 0 -or
        [string]$modelDiagnostics.costCurrency -ne 'CNY' -or
        $diagnosticCost -ne $estimatedCost -or
        $diagnosticCost -gt [decimal]$stageBudget.RemainingCostCny -or
        [int]$m3.research.toolCallCount -ne 4 -or
        [int]$usage.inputTokens -le 0 -or
        [int]$usage.outputTokens -le 0 -or
        [int]$usage.reasoningTokens -lt 0 -or
        [int]$usage.totalTokens -ne
            ([int]$usage.inputTokens + [int]$usage.outputTokens) -or
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
        externalModelCompletedCallCount = 13
        providerRequestCount = 13
        modelInputUnits = [int]$usage.inputTokens
        modelOutputUnits = [int]$usage.outputTokens
        modelReasoningUnits = [int]$usage.reasoningTokens
        modelTotalUnits = [int]$usage.totalTokens
        modelCallTelemetry = @(
            ConvertTo-StockQuantM3CallTelemetrySummary `
                -Telemetry @($modelDiagnostics.callTelemetry))
        estimatedCostCny = $estimatedCost.ToString(
            [Globalization.CultureInfo]::InvariantCulture)
        accountedCostCny = $diagnosticCost.ToString(
            [Globalization.CultureInfo]::InvariantCulture)
        providerReportedActualCostCny = $null
        actualCostStatus = 'NOT_PROVIDED_BY_API'
        hardCostLimitCny = '5.00'
        costCurrency = 'CNY'
        model = 'qwen3.7-plus'
        stageAttempt = [int]$stageBudget.AttemptNumber
        stagePriorAccountedCostCny = [string]$stageBudget.PriorCostCny
        stagePriorModelAttemptCount =
            [int]$stageBudget.PriorModelAttemptCount
        stageRemainingCostBeforeRunCny =
            [string]$stageBudget.RemainingCostCny
        budgetTranche = [string]$stageBudget.BudgetTranche
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
    $approvalMarker =
        'USER_APPROVED_M3_BAILIAN_SMOKE_TRANCHE_2_CNY_5_00'
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
                $content -match '(?m)^provider=BAILIAN$' -and
                $content -match ("(?m)^user\.approval\.reference=" +
                    [regex]::Escape($approvalMarker) + '$')
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
        BudgetTranche = 'M3_BAILIAN_TRANCHE_2'
    }
}

function Get-M4MonthlyBudget {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    $calendarMonth = [string]$BrokerRequest.Values['budget.calendar.month']
    $usage = Get-StockQuantM4MonthlyUsage `
        -CalendarMonth $calendarMonth `
        -ExcludedRequestPath $processingPath
    [decimal]$llmUsed = [decimal]::Parse(
        [string]$usage.CommittedShadowCostCny,
        [Globalization.NumberStyles]::Number,
        [Globalization.CultureInfo]::InvariantCulture)
    [decimal]$projectUsed = [decimal]::Parse(
        [string]$usage.CommittedProjectCostCny,
        [Globalization.NumberStyles]::Number,
        [Globalization.CultureInfo]::InvariantCulture)
    [decimal]$maximum = [decimal]::Parse(
        [string]$BrokerRequest.Values['maximum.cost.cny'],
        [Globalization.NumberStyles]::Number,
        [Globalization.CultureInfo]::InvariantCulture)
    [int]$admittedProviderCalls =
        [int]$BrokerRequest.Values['maximum.provider.requests']
    if ($admittedProviderCalls -notin @(6, 8) -or
        [int]$usage.CommittedTushareCalls + $admittedProviderCalls -gt 150 -or
        $llmUsed + $maximum -gt [decimal]30.00 -or
        $projectUsed + $maximum -gt [decimal]200.00) {
        throw 'M4_MONTHLY_BUDGET_EXHAUSTED'
    }
    return [pscustomobject]@{
        CalendarMonth = $calendarMonth
        PriorRequestCount = [int]$usage.RequestCount
        PriorLlmCostCny = $llmUsed.ToString(
            [Globalization.CultureInfo]::InvariantCulture)
        PriorProjectCostCny = $projectUsed.ToString(
            [Globalization.CultureInfo]::InvariantCulture)
        AdmittedLlmCostCny = $maximum.ToString(
            [Globalization.CultureInfo]::InvariantCulture)
        PriorTushareCalls = [int]$usage.CommittedTushareCalls
        RemainingTushareCalls = 150 - [int]$usage.CommittedTushareCalls
    }
}

function Invoke-M4ShadowResearch {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    if ($BrokerRequest.AuthorizationStatus -ne
            'M4_USER_APPROVED_CONTINUOUS_MONTHLY' -or
        $null -ne $BrokerRequest.AuthorizationFile) {
        throw 'STOCK_QUANT_HOST_BROKER_M4_SCOPE_INVALID'
    }
    $budget = Get-M4MonthlyBudget -BrokerRequest $BrokerRequest
    $runnerResult = Join-Path $paths.Results `
        "$($BrokerRequest.RequestId).m4-shadow.json"
    if (Test-Path -LiteralPath $runnerResult) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_ALREADY_EXISTS'
    }
    $executionId = $BrokerRequest.RequestId -replace '^SQHB_', 'M4SHADOW_'
    $output = @(& $m4RunnerScript -ResultFile $runnerResult `
        -ArtifactPath $BrokerRequest.JarPath -ExecutionId $executionId `
        -DatabasePort 38432 -ExecutionMode FORMAL `
        -Securities $BrokerRequest.Values['securities'] `
        -RangeStart $BrokerRequest.Values['range.start'] `
        -TradeDate $BrokerRequest.Values['trade.date'] `
        -NextTradeDate $BrokerRequest.Values['next.trade.date'] `
        -CalendarAdmission $BrokerRequest.Values['calendar.admission'] `
        -CalendarHorizonEnd `
            $BrokerRequest.Values['calendar.horizon.end'] `
        -CaptureMode $BrokerRequest.Values['capture.mode'] `
        -TriggerMode $BrokerRequest.Values['trigger.mode'] `
        -MaximumCostCny $budget.AdmittedLlmCostCny 2>&1 |
        ForEach-Object { [string]$_ })
    $runnerExitCode = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $runnerResult -PathType Leaf)) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_MISSING'
    }
    $m4 = Get-Content -LiteralPath $runnerResult -Raw -Encoding UTF8 |
        ConvertFrom-Json
    [decimal]$cost = [decimal]::Parse(
        [string]$m4.conservativeCostCny,
        [Globalization.NumberStyles]::Number,
        [Globalization.CultureInfo]::InvariantCulture)
    $summary = [ordered]@{
        executionId = [string]$m4.executionId
        providerCallCount = [int]$m4.tushareProviderCallCount
        retryCount = [int]$m4.retryCount
        externalModelCallCount = [int]$m4.modelProviderRequestCount
        modelInputUnits = [int]$m4.inputTokens
        modelOutputUnits = [int]$m4.outputTokens
        modelReasoningUnits = [int]$m4.reasoningTokens
        modelTotalUnits = [int]$m4.totalTokens
        accountedCostCny = $cost.ToString(
            [Globalization.CultureInfo]::InvariantCulture)
        calendarMonth = [string]$budget.CalendarMonth
        monthlyPriorAccountedCostCny = [string]$budget.PriorLlmCostCny
        monthlyPriorTushareCalls = [int]$budget.PriorTushareCalls
        projectMonthlyPriorAccountedCostCny =
            [string]$budget.PriorProjectCostCny
        sanitizedResult = $runnerResult
        outputAudit = $(if ($m4.outputAuditClean) { 'PASSED' } else { 'FAILED' })
    }
    if ($runnerExitCode -ne 0) {
        $script:failureSummary = $summary
        $reason = [string]$m4.failureReason
        if ($reason -match '^[A-Z][A-Z0-9_]{3,127}$') { throw $reason }
        throw 'STOCK_QUANT_HOST_BROKER_M4_FAILED'
    }
    [int]$expectedProviderCalls =
        [int]$BrokerRequest.Values['maximum.provider.requests']
    if ($m4.status -eq 'SKIPPED_NON_TRADING_DAY') {
        if ($BrokerRequest.Values['calendar.admission'] -ne 'UNKNOWN' -or
            [int]$m4.tushareProviderCallCount -ne 2 -or
            [int]$m4.retryCount -ne 0 -or
            [int]$m4.modelProviderRequestCount -ne 0 -or
            [int]$m4.modelCallCount -ne 0 -or
            [int]$m4.toolCallCount -ne 0 -or
            @($m4.agentRoles).Count -ne 0 -or
            $cost -ne [decimal]0 -or -not $m4.outputAuditClean -or
            -not $m4.researchOnly -or $m4.brokerConnected -or
            $m4.realTradingStarted) {
            throw 'STOCK_QUANT_HOST_BROKER_M4_SKIP_RESULT_INVALID'
        }
        $summary.shadowStatus = 'SKIPPED_NON_TRADING_DAY'
        $summary.tradeDate = [string]$m4.tradeDate
        $summary.researchOnly = $true
        $summary.realTradingStarted = $false
        return $summary
    }
    if ($m4.status -ne 'SUCCEEDED' -or
        [int]$m4.tushareProviderCallCount -ne $expectedProviderCalls -or
        [int]$m4.retryCount -ne 0 -or
        [int]$m4.modelProviderRequestCount -ne 13 -or
        [int]$m4.modelCallCount -ne 13 -or
        [int]$m4.toolCallCount -ne 4 -or
        @($m4.agentRoles | Sort-Object -Unique).Count -ne 7 -or
        -not $m4.typedFactReadback -or
        -not $m4.systemKnowledgeReadback -or
        -not $m4.formulaOnlyQfq -or
        -not $m4.noFutureDataLeakage -or
        -not $m4.outputAuditClean -or -not $m4.researchOnly -or
        $m4.brokerConnected -or $m4.realTradingStarted -or
        $cost -le 0 -or $cost -gt [decimal]$budget.AdmittedLlmCostCny) {
        throw 'STOCK_QUANT_HOST_BROKER_M4_RESULT_INVALID'
    }
    $summary.shadowRunId = [long]$m4.shadowRunId
    $summary.shadowRunKey = [string]$m4.shadowRunKey
    $summary.tradeDate = [string]$m4.tradeDate
    $summary.snapshotFingerprint = [string]$m4.snapshotFingerprint
    $summary.decisionCode = [string]$m4.decisionCode
    $summary.confidence = [string]$m4.confidence
    $summary.riskLevel = [string]$m4.riskLevel
    $summary.evidenceCount = [int]$m4.evidenceCount
    $summary.paperOrderCount = [int]$m4.paperOrderCount
    $summary.paperFillCount = [int]$m4.paperFillCount
    $summary.paperCash = [string]$m4.paperCash
    $summary.paperEquity = [string]$m4.paperEquity
    $summary.paperTotalReturn = [string]$m4.paperTotalReturn
    $summary.typedFactReadback = $true
    $summary.systemKnowledgeReadback = $true
    $summary.formulaOnlyQfq = $true
    $summary.noFutureDataLeakage = $true
    $summary.researchOnly = $true
    $summary.realTradingStarted = $false
    return $summary
}

function Invoke-ResearchSelection {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    if ($BrokerRequest.AuthorizationStatus -ne
            'STOCK_QUANT_PRO_V1_MONTHLY_APPROVED' -or
        $null -ne $BrokerRequest.AuthorizationFile) {
        throw 'STOCK_QUANT_HOST_BROKER_SELECTION_SCOPE_INVALID'
    }
    $usage = Get-StockQuantM4MonthlyUsage `
        -CalendarMonth $BrokerRequest.Values['budget.calendar.month'] `
        -ExcludedRequestPath $processingPath
    [int]$maximumProvider =
        [int]$BrokerRequest.Values['maximum.provider.requests']
    [decimal]$maximumCost = [decimal]$BrokerRequest.Values[
        'maximum.cost.cny']
    if ([int]$usage.CommittedTushareCalls + $maximumProvider -gt 150 -or
        [decimal]$usage.CommittedShadowCostCny + $maximumCost -gt
            [decimal]30 -or
        [decimal]$usage.CommittedProjectCostCny + $maximumCost -gt
            [decimal]200) {
        throw 'RESEARCH_SELECTION_MONTHLY_BUDGET_BINDING_INVALID'
    }
    $runnerResult = Join-Path $paths.Results `
        "$($BrokerRequest.RequestId).research-selection.json"
    if (Test-Path -LiteralPath $runnerResult) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_ALREADY_EXISTS'
    }
    $executionId = $BrokerRequest.RequestId -replace '^SQHB_', 'SELECTEXEC_'
    $output = @(& $researchSelectionRunnerScript `
        -ResultFile $runnerResult -ArtifactPath $BrokerRequest.JarPath `
        -ExecutionId $executionId `
        -SelectionRunId ([long]$BrokerRequest.Values['selection.run.id']) `
        -PublicRunId $BrokerRequest.Values['selection.public.run.id'] `
        -SelectionTrigger $BrokerRequest.Values['selection.trigger'] `
        -GitCommit $BrokerRequest.GitCommit -DatabasePort 38432 `
        -MaximumProviderRequests $maximumProvider `
        -ExecutionMode FORMAL -MaximumCostCny $maximumCost 2>&1 |
        ForEach-Object { [string]$_ })
    $runnerExitCode = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $runnerResult -PathType Leaf)) {
        throw 'STOCK_QUANT_HOST_BROKER_RUNNER_RESULT_MISSING'
    }
    $selection = Get-Content -LiteralPath $runnerResult -Raw -Encoding UTF8 |
        ConvertFrom-Json
    [decimal]$cost = [decimal]$selection.conservativeCostCny
    $summary = [ordered]@{
        selectionRunId = [long]$selection.selectionRunId
        publicRunId = [string]$selection.publicRunId
        selectionStatus = [string]$selection.selectionStatus
        providerCallCount = [int]$selection.tushareProviderCallCount
        retryCount = [int]$selection.retryCount
        externalModelCallCount = [int]$selection.modelProviderRequestCount
        modelInputUnits = [int]$selection.inputTokens
        modelOutputUnits = [int]$selection.outputTokens
        modelReasoningUnits = [int]$selection.reasoningTokens
        modelTotalUnits = [int]$selection.totalTokens
        accountedCostCny = $cost.ToString(
            [Globalization.CultureInfo]::InvariantCulture)
        candidateCount = [int]$selection.candidateCount
        decisionCode = [string]$selection.decisionCode
        sanitizedResult = $runnerResult
        outputAudit = $(if ($selection.outputAuditClean) {
            'PASSED'
        } else { 'FAILED' })
    }
    if ($runnerExitCode -ne 0) {
        $script:failureSummary = $summary
        $reason = [string]$selection.failureReason
        if ($reason -match '^[A-Z][A-Z0-9_]{3,127}$') { throw $reason }
        throw 'STOCK_QUANT_HOST_BROKER_SELECTION_FAILED'
    }
    if ($selection.status -ne 'SUCCEEDED' -or
        [int]$selection.universeSize -lt 1000 -or
        [int]$selection.shortlistSize -ne 10 -or
        [int]$selection.tushareProviderCallCount -gt $maximumProvider -or
        [int]$selection.retryCount -ne 0 -or
        [int]$selection.modelProviderRequestCount -ne 13 -or
        [int]$selection.modelCallCount -ne 13 -or
        @($selection.agentRoles | Sort-Object -Unique).Count -ne 7 -or
        -not $selection.typedFactReadback -or
        -not $selection.systemKnowledgeReadback -or
        -not $selection.formulaOnlyQfq -or
        -not $selection.noFutureDataLeakage -or
        -not $selection.outputAuditClean -or
        -not $selection.researchOnly -or $selection.realTradingStarted -or
        $cost -le 0 -or $cost -gt $maximumCost) {
        throw 'STOCK_QUANT_HOST_BROKER_SELECTION_RESULT_INVALID'
    }
    return $summary
}

function Resolve-ResearchProductionJavaExecutable {
    $command = 'java.exe'
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $details = @(& $command '-XshowSettings:properties' '-version' 2>&1 |
            ForEach-Object { [string]$_ })
        $javaExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
    }
    $homes = @()
    $versions = @()
    foreach ($line in $details) {
        if ($line -match '^\s*java\.home\s*=\s*(.+?)\s*$') {
            $homes += $Matches[1].Trim()
        }
        if ($line -match '^\s*java\.version\s*=\s*(.+?)\s*$') {
            $versions += $Matches[1].Trim()
        }
    }
    if ($javaExitCode -ne 0 -or $homes.Count -ne 1 -or
        $versions.Count -ne 1 -or $versions[0] -notmatch '^17(?:\.|$)') {
        throw 'M6_JAVA_17_RUNTIME_INVALID'
    }
    $java = [IO.Path]::GetFullPath(
        (Join-Path $homes[0] 'bin\java.exe'))
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
        throw 'M6_JAVA_17_RUNTIME_INVALID'
    }
    return $java
}

function Get-ResearchProductionProcess {
    if (-not (Test-Path -LiteralPath $productionPidFile -PathType Leaf)) {
        return $null
    }
    try {
        $state = Get-Content -LiteralPath $productionPidFile -Raw `
            -Encoding UTF8 | ConvertFrom-Json
        if ([int]$state.processId -le 0 -or
            [string]$state.gitCommit -notmatch '^[0-9a-f]{40}$' -or
            [string]$state.jarSha256 -notmatch '^[0-9a-f]{64}$') {
            throw 'M6_PRODUCTION_PROCESS_STATE_INVALID'
        }
        $process = Get-CimInstance Win32_Process -Filter `
            "ProcessId=$([int]$state.processId)" -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            Remove-Item -LiteralPath $productionPidFile -Force `
                -ErrorAction SilentlyContinue
            return $null
        }
        $fixedJar = [IO.Path]::GetFullPath(
            (Join-Path $paths.TargetRoot `
                'quant-server-1.3.1-research-production.jar'))
        if ($process.Name -ne 'java.exe' -or
            -not [IO.Path]::GetFullPath([string]$state.jarPath).Equals(
                $fixedJar, [StringComparison]::OrdinalIgnoreCase) -or
            [string]$process.CommandLine -notmatch
                [regex]::Escape(' -jar "' + $fixedJar + '"') -or
            [string]$process.CommandLine -match '(?i)(password|token|api.?key)') {
            throw 'M6_PRODUCTION_PROCESS_IDENTITY_INVALID'
        }
        return [pscustomobject]@{ Process = $process; State = $state }
    } catch {
        throw 'M6_PRODUCTION_PROCESS_STATE_INVALID'
    }
}

function Assert-ResearchProductionBinding {
    param([Parameter(Mandatory = $true)] [object] $Binding)
    $fixedJar = [IO.Path]::GetFullPath((Join-Path $paths.TargetRoot `
        'quant-server-1.3.1-research-production.jar'))
    $jar = [IO.Path]::GetFullPath([string]$Binding.JarPath)
    if (-not $jar.Equals($fixedJar,
            [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $jar -PathType Leaf) -or
        [string]$Binding.GitCommit -notmatch '^[0-9a-f]{40}$' -or
        [string]$Binding.JarSha256 -notmatch '^[0-9a-f]{64}$' -or
        ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
            ).ToLowerInvariant() -cne [string]$Binding.JarSha256) {
        throw 'M6_PRODUCTION_AUTOSTART_BINDING_INVALID'
    }
    $proof = "$jar.f1f-b2-proof.properties"
    if (-not (Test-Path -LiteralPath $proof -PathType Leaf)) {
        throw 'M6_PRODUCTION_AUTOSTART_BINDING_INVALID'
    }
    $proofLines = @(Get-Content -LiteralPath $proof -Encoding UTF8)
    $proofGit = @($proofLines | Where-Object {
        $_ -ceq "git.commit=$($Binding.GitCommit)"
    })
    $proofHash = @($proofLines | Where-Object {
        $_ -ceq "artifact.sha256=$($Binding.JarSha256)"
    })
    $proofMode = @($proofLines | Where-Object {
        $_ -in @('build.mode=M6_STAGE_CONTROLLED_BUILD_ARTIFACT',
            'build.mode=RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT',
            'build.mode=CONTROLLED_BUILD_ARTIFACT')
    })
    if ($proofGit.Count -ne 1 -or $proofHash.Count -ne 1 -or
        $proofMode.Count -ne 1) {
        throw 'M6_PRODUCTION_AUTOSTART_BINDING_INVALID'
    }
    Push-Location $paths.RepositoryRoot
    try {
        $head = (git rev-parse HEAD).Trim()
        $branch = (git branch --show-current).Trim()
        $unexpected = @(git status --porcelain=v1 --untracked-files=normal |
            Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' })
        if ($head -cne [string]$Binding.GitCommit -or
            $branch -notin @($integrationBranch,
                'codex/1.4.0-v1.0.1-research-selection-usability',
                'codex/1.4.0-v1.0.2-startup-self-heal-fix',
                'codex/1.4.0-v1.0.3-research-selection-runtime-fix',
                'codex/1.4.0-v1.0.7-intraday-research-selection-anchor-fix',
                'codex/1.4.0-m6-research-production-ready') -or
            $unexpected.Count -ne 0 -or
            @(git diff --cached --name-only).Count -ne 0) {
            throw 'M6_PRODUCTION_AUTOSTART_BINDING_INVALID'
        }
    } finally {
        Pop-Location
    }
}

function Write-ResearchProductionAutostart {
    param(
        [Parameter(Mandatory = $true)] [object] $Binding,
        [Parameter(Mandatory = $true)] [int] $RestartCount,
        [Parameter(Mandatory = $true)] [string] $LastRestartAt
    )
    New-Item -ItemType Directory -Path $productionRoot -Force | Out-Null
    $state = [ordered]@{
        schemaVersion = 'STOCK_QUANT_RESEARCH_PRODUCTION_AUTOSTART_V1'
        enabled = $true
        gitCommit = [string]$Binding.GitCommit
        jarPath = [IO.Path]::GetFullPath([string]$Binding.JarPath)
        jarSha256 = [string]$Binding.JarSha256
        restartCount = $RestartCount
        lastRestartAt = $LastRestartAt
    }
    $temporary = "$productionAutostartFile.$PID.tmp"
    $backup = "$productionAutostartFile.$PID.backup"
    try {
        [IO.File]::WriteAllText($temporary,
            ($state | ConvertTo-Json -Depth 3) + "`n",
            [Text.UTF8Encoding]::new($false))
        if (Test-Path -LiteralPath $productionAutostartFile) {
            Remove-Item -LiteralPath $backup -Force `
                -ErrorAction SilentlyContinue
            [IO.File]::Replace($temporary, $productionAutostartFile, $backup)
        } else {
            [IO.File]::Move($temporary, $productionAutostartFile)
        }
    } catch {
        throw 'M6_PRODUCTION_AUTOSTART_WRITE_FAILED'
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $productionAutostartFile) {
            Remove-Item -LiteralPath $backup -Force `
                -ErrorAction SilentlyContinue
        }
    }
}

function Read-ResearchProductionAutostart {
    if (-not (Test-Path -LiteralPath $productionAutostartFile `
            -PathType Leaf)) { return $null }
    try {
        $state = Get-Content -LiteralPath $productionAutostartFile -Raw `
            -Encoding UTF8 | ConvertFrom-Json
        $expected = @('schemaVersion', 'enabled', 'gitCommit', 'jarPath',
            'jarSha256', 'restartCount', 'lastRestartAt')
        $actual = @($state.PSObject.Properties.Name)
        $lastRestartValid = $true
        if ([string]$state.lastRestartAt -cne 'NONE') {
            [DateTimeOffset]$parsedRestart = [DateTimeOffset]::MinValue
            $lastRestartValid = [DateTimeOffset]::TryParse(
                [string]$state.lastRestartAt,
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::RoundtripKind,
                [ref]$parsedRestart) -and
                $parsedRestart.Offset -eq [TimeSpan]::Zero
        }
        if ($actual.Count -ne $expected.Count -or
            @($expected | Where-Object { $_ -notin $actual }).Count -ne 0 -or
            $state.schemaVersion -cne
                'STOCK_QUANT_RESEARCH_PRODUCTION_AUTOSTART_V1' -or
            $state.enabled -ne $true -or [int]$state.restartCount -lt 0 -or
            [int]$state.restartCount -gt $productionMaximumRestarts -or
            -not $lastRestartValid) {
            throw 'M6_PRODUCTION_AUTOSTART_STATE_INVALID'
        }
        $binding = [pscustomobject]@{
            GitCommit = [string]$state.gitCommit
            JarPath = [string]$state.jarPath
            JarSha256 = [string]$state.jarSha256
        }
        Assert-ResearchProductionBinding -Binding $binding
        return [pscustomobject]@{
            Binding = $binding
            RestartCount = [int]$state.restartCount
            LastRestartAt = [string]$state.lastRestartAt
        }
    } catch {
        throw 'M6_PRODUCTION_AUTOSTART_STATE_INVALID'
    }
}

function Start-ResearchProductionProcess {
    param(
        [Parameter(Mandatory = $true)] [object] $Binding,
        [switch] $Recovery
    )
    Assert-ResearchProductionBinding -Binding $Binding
    if (Test-Path -LiteralPath $productionPidFile) {
        Remove-Item -LiteralPath $productionPidFile -Force
    }
    New-Item -ItemType Directory -Path (
        Split-Path -Parent $productionStdout) -Force | Out-Null
    foreach ($log in @($productionStdout, $productionStderr)) {
        if (Test-Path -LiteralPath $log) {
            Move-Item -LiteralPath $log -Destination `
                "$log.$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()).previous"
        }
    }
    $javaExecutable = Resolve-ResearchProductionJavaExecutable
    $process = Start-Process -FilePath $javaExecutable `
        -ArgumentList @('-jar', ('"' + $Binding.JarPath + '"')) `
        -WorkingDirectory $paths.RepositoryRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $productionStdout `
        -RedirectStandardError $productionStderr
    $state = [ordered]@{
        schemaVersion = 'STOCK_QUANT_RESEARCH_PRODUCTION_PROCESS_V1'
        processId = $process.Id
        gitCommit = $Binding.GitCommit
        jarPath = $Binding.JarPath
        jarSha256 = $Binding.JarSha256
        startedAt = [DateTimeOffset]::UtcNow.ToString('o')
    }
    [IO.File]::WriteAllText($productionPidFile,
        ($state | ConvertTo-Json -Depth 3) + "`n",
        [Text.UTF8Encoding]::new($false))
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(90)
    $health = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if ($Recovery) { Write-BrokerHeartbeat -State IDLE }
        if ($process.HasExited) {
            Remove-Item -LiteralPath $productionPidFile -Force `
                -ErrorAction SilentlyContinue
            throw 'M6_PRODUCTION_BACKEND_EXITED'
        }
        try {
            $health = Invoke-RestMethod -Uri `
                'http://127.0.0.1:8080/api/system/health' `
                -Method Get -TimeoutSec 3
            if ($health.success -and
                $health.data.gitCommit -eq $Binding.GitCommit) {
                break
            }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if ($null -eq $health -or -not $health.success -or
        $health.data.gitCommit -ne $Binding.GitCommit) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $productionPidFile -Force `
            -ErrorAction SilentlyContinue
        throw 'M6_PRODUCTION_HEALTH_TIMEOUT'
    }
    return [ordered]@{
        backendState = 'RUNNING'
        processId = $process.Id
        schemaVersion = [int](
            $health.data.components | Where-Object component -eq 'Database'
        ).details.schemaVersion
        schedulerState = [string]$health.data.scheduler.state
        overallHealth = [string]$health.data.status
        providerCallCount = 0
        retryCount = 0
    }
}

function Invoke-ResearchProductionStart {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    if ($BrokerRequest.AuthorizationStatus -ne
            'M6_LOCAL_PRODUCTION_APPROVED') {
        throw 'M6_PRODUCTION_SCOPE_INVALID'
    }
    $binding = [pscustomobject]@{
        GitCommit = $BrokerRequest.GitCommit
        JarPath = $BrokerRequest.JarPath
        JarSha256 = $BrokerRequest.JarSha256
    }
    Assert-ResearchProductionBinding -Binding $binding
    $existing = Get-ResearchProductionProcess
    if ($null -ne $existing) {
        if ([string]$existing.State.gitCommit -cne $binding.GitCommit -or
            [string]$existing.State.jarSha256 -cne $binding.JarSha256) {
            throw 'M6_PRODUCTION_RUNNING_VERSION_MISMATCH'
        }
        Write-ResearchProductionAutostart -Binding $binding `
            -RestartCount 0 -LastRestartAt 'NONE'
        return [ordered]@{
            backendState = 'ALREADY_RUNNING'
            processId = [int]$existing.Process.ProcessId
            migrationTarget = 16
            autoRecovery = 'ARMED'
            providerCallCount = 0
            retryCount = 0
        }
    }
    $summary = Start-ResearchProductionProcess -Binding $binding
    try {
        Write-ResearchProductionAutostart -Binding $binding `
            -RestartCount 0 -LastRestartAt 'NONE'
    } catch {
        Stop-Process -Id ([int]$summary.processId) -Force `
            -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $productionPidFile -Force `
            -ErrorAction SilentlyContinue
        throw 'M6_PRODUCTION_AUTOSTART_ARM_FAILED'
    }
    $summary['autoRecovery'] = 'ARMED'
    return $summary
}

function Invoke-ResearchProductionRecovery {
    try {
        if (-not (Test-Path -LiteralPath $productionAutostartFile `
                -PathType Leaf) -or
            $null -ne (Get-ResearchProductionProcess)) { return }
        $autostart = Read-ResearchProductionAutostart
        if ($autostart.RestartCount -ge $productionMaximumRestarts) { return }
        if ($autostart.LastRestartAt -cne 'NONE') {
            $last = [DateTimeOffset]::Parse($autostart.LastRestartAt)
            if ([DateTimeOffset]::UtcNow - $last -lt
                    [TimeSpan]::FromSeconds(15)) { return }
        }
        $nextCount = $autostart.RestartCount + 1
        $now = [DateTimeOffset]::UtcNow.ToString('o')
        Write-ResearchProductionAutostart -Binding $autostart.Binding `
            -RestartCount $nextCount -LastRestartAt $now
        Start-ResearchProductionProcess -Binding $autostart.Binding `
            -Recovery | Out-Null
        $status = [ordered]@{
            schemaVersion = 'STOCK_QUANT_RESEARCH_PRODUCTION_RECOVERY_V1'
            status = 'SUCCEEDED'
            reason = 'M6_PRODUCTION_RECOVERED'
            completedAt = [DateTimeOffset]::UtcNow.ToString('o')
        }
        [IO.File]::WriteAllText($productionRecoveryStatusFile,
            ($status | ConvertTo-Json -Depth 3) + "`n",
            [Text.UTF8Encoding]::new($false))
    } catch {
        $reason = ConvertTo-StockQuantSafeCode -ErrorValue $_
        $status = [ordered]@{
            schemaVersion = 'STOCK_QUANT_RESEARCH_PRODUCTION_RECOVERY_V1'
            status = 'FAILED'
            reason = $reason
            completedAt = [DateTimeOffset]::UtcNow.ToString('o')
        }
        [IO.File]::WriteAllText($productionRecoveryStatusFile,
            ($status | ConvertTo-Json -Depth 3) + "`n",
            [Text.UTF8Encoding]::new($false))
        # Recovery is bounded by the persisted counter and never kills Broker.
    }
}

function Invoke-ResearchProductionStatus {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    $process = Get-ResearchProductionProcess
    if ($null -eq $process) {
        $autostart = Read-ResearchProductionAutostart
        return [ordered]@{
            backendState = 'STOPPED'
            processId = 0
            autoRecovery = $(if ($null -eq $autostart) { 'DISARMED' }
                elseif ($autostart.RestartCount -ge
                    $productionMaximumRestarts) { 'EXHAUSTED' }
                else { 'ARMED' })
            providerCallCount = 0
            retryCount = 0
        }
    }
    try {
        $health = Invoke-RestMethod -Uri `
            'http://127.0.0.1:8080/api/system/health' -Method Get -TimeoutSec 5
    } catch {
        throw 'M6_PRODUCTION_HEALTH_UNAVAILABLE'
    }
    if ($health.data.gitCommit -cne $BrokerRequest.GitCommit -or
        [string]$process.State.gitCommit -cne $BrokerRequest.GitCommit -or
        [string]$process.State.jarSha256 -cne $BrokerRequest.JarSha256) {
        throw 'M6_PRODUCTION_RUNNING_VERSION_MISMATCH'
    }
    return [ordered]@{
        backendState = 'RUNNING'
        processId = [int]$process.Process.ProcessId
        overallHealth = [string]$health.data.status
        schedulerState = [string]$health.data.scheduler.state
        autoRecovery = 'ARMED'
        providerCallCount = 0
        retryCount = 0
    }
}

function Invoke-ResearchProductionStop {
    param([Parameter(Mandatory = $true)] [object] $BrokerRequest)
    Remove-Item -LiteralPath $productionAutostartFile -Force `
        -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $productionRecoveryStatusFile -Force `
        -ErrorAction SilentlyContinue
    $process = Get-ResearchProductionProcess
    if ($null -eq $process) {
        if (Test-Path -LiteralPath $productionPidFile) {
            Remove-Item -LiteralPath $productionPidFile -Force
        }
        return [ordered]@{
            backendState = 'ALREADY_STOPPED'
            processId = 0
            autoRecovery = 'DISARMED'
            providerCallCount = 0
            retryCount = 0
        }
    }
    $graceful = $false
    try {
        $response = Invoke-RestMethod -Uri `
            'http://127.0.0.1:8080/api/system/lifecycle/stop' `
            -Method Post -TimeoutSec 5
        $graceful = $response.success -eq $true
    } catch { }
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(15)
    do {
        Start-Sleep -Milliseconds 250
        $alive = Get-Process -Id ([int]$process.Process.ProcessId) `
            -ErrorAction SilentlyContinue
    } while ($null -ne $alive -and [DateTimeOffset]::UtcNow -lt $deadline)
    if ($null -ne $alive) {
        Stop-Process -Id ([int]$process.Process.ProcessId) -Force
        $graceful = $false
        Start-Sleep -Milliseconds 500
        $alive = Get-Process -Id ([int]$process.Process.ProcessId) `
            -ErrorAction SilentlyContinue
    }
    if ($null -ne $alive) { throw 'M6_PRODUCTION_STOP_FAILED' }
    Remove-Item -LiteralPath $productionPidFile -Force
    return [ordered]@{
        backendState = 'STOPPED'
        processId = [int]$process.Process.ProcessId
        graceful = $graceful
        autoRecovery = 'DISARMED'
        providerCallCount = 0
        retryCount = 0
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
        $script:operation = Get-StockQuantHostBrokerDeclaredOperation `
            -Path $processingPath
        $script:request = Read-StockQuantHostBrokerRequest `
            -Path $processingPath
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
            'RUN_M4_SHADOW_RESEARCH' {
                Invoke-M4ShadowResearch -BrokerRequest $request
                break
            }
            'RUN_RESEARCH_SELECTION' {
                Invoke-ResearchSelection -BrokerRequest $request
                break
            }
            'START_RESEARCH_PRODUCTION' {
                Invoke-ResearchProductionStart -BrokerRequest $request
                break
            }
            'STOP_RESEARCH_PRODUCTION' {
                Invoke-ResearchProductionStop -BrokerRequest $request
                break
            }
            'CHECK_RESEARCH_PRODUCTION_STATUS' {
                Invoke-ResearchProductionStatus -BrokerRequest $request
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
            Invoke-ResearchProductionRecovery
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
