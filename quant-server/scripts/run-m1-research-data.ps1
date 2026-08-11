[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $AuthorizationFile,
    [Parameter(Mandatory = $true)] [string] $ResultFile,
    [string] $ArtifactPath,
    [ValidateSet('WINDOWS_CREDENTIAL_MANAGER', 'CONSOLE')]
    [string] $SecretMode = 'WINDOWS_CREDENTIAL_MANAGER'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runnerClass = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM1ResearchDataManualRunner'
$preflightClass = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM1ResearchDataPreflight'
$stage = 'INITIALIZATION'
if ([string]::IsNullOrWhiteSpace($ArtifactPath)) {
    $ArtifactPath = Join-Path $repoRoot `
        'quant-server\target\quant-server-1.3.1-m1-research-data-runner.jar'
}

function Resolve-RepoFile([string] $Path, [string] $Code) {
    if ([string]::IsNullOrWhiteSpace($Path) -or
        -not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw $Code }
    $full = (Resolve-Path -LiteralPath $Path).Path
    $prefix = $repoRoot.TrimEnd('\', '/') + '\'
    if (-not $full.StartsWith(
            $prefix, [StringComparison]::OrdinalIgnoreCase) -or
        ($full -split '[\/]') -contains '.ai') { throw $Code }
    return $full
}

function Get-Marker([object[]] $Lines, [string] $Name, [string] $Fallback) {
    $prefix = "$Name="
    $matches = @($Lines | Where-Object {
        $null -ne $_ -and ([string]$_).StartsWith($prefix)
    })
    if ($matches.Count -ne 1) { return $Fallback }
    return ([string]$matches[0]).Substring($prefix.Length)
}

function Assert-PostgresListening {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync('127.0.0.1', 38432)
        if (-not $connect.Wait([TimeSpan]::FromSeconds(3)) -or
            -not $client.Connected) {
            throw 'TUSHARE_M1_POSTGRES_NOT_LISTENING'
        }
    } finally { $client.Dispose() }
}

Push-Location $repoRoot
try {
    $stage = 'HOST_CONTEXT'
    if ([Security.Principal.WindowsIdentity]::GetCurrent().Name -match
            '(?i)CodexSandbox') {
        throw 'STOCK_QUANT_HOST_BROKER_REQUIRED'
    }
    $stage = 'PATH_VALIDATION'
    $authorization = Resolve-RepoFile $AuthorizationFile `
        'TUSHARE_M1_AUTHORIZATION_MISSING'
    $artifact = Resolve-RepoFile $ArtifactPath 'TUSHARE_M1_ARTIFACT_MISSING'
    $proof = Resolve-RepoFile "$artifact.f1f-b2-proof.properties" `
        'TUSHARE_M1_BUILD_PROOF_MISSING'
    $result = [IO.Path]::GetFullPath($ResultFile)
    $resultPrefix = $repoRoot.TrimEnd('\', '/') + '\'
    if (-not $result.StartsWith(
            $resultPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        ($result -split '[\/]') -contains '.ai' -or
        (Test-Path -LiteralPath $result)) {
        throw 'TUSHARE_M1_RESULT_PATH_INVALID'
    }

    $stage = 'ARTIFACT_PREFLIGHT'
    $preflight = @(& java "-Dloader.main=$preflightClass" -cp $artifact `
        'org.springframework.boot.loader.launch.PropertiesLauncher' `
        "--authorization-file=$authorization" 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0 -or
        $preflight -notcontains 'TUSHARE_M1_PREFLIGHT=PASS') {
        throw (Get-Marker $preflight 'TUSHARE_M1_PREFLIGHT_REASON' `
            'TUSHARE_M1_PREFLIGHT_FAILED')
    }
    $expectedCommit = Get-Marker $preflight 'TUSHARE_M1_GIT_COMMIT' 'INVALID'
    $expectedHash = Get-Marker $preflight 'TUSHARE_M1_ARTIFACT_SHA256' 'INVALID'
    $expectedProof = Get-Marker $preflight 'TUSHARE_M1_BUILD_PROOF_PATH' 'INVALID'
    $actualHash = ((Get-FileHash -LiteralPath $artifact `
        -Algorithm SHA256).Hash).ToLowerInvariant()
    if ($expectedCommit -notmatch '^[0-9a-f]{40}$' -or
        $expectedHash -notmatch '^[0-9a-f]{64}$' -or
        -not ([IO.Path]::GetFullPath($expectedProof)).Equals(
            $proof, [StringComparison]::OrdinalIgnoreCase) -or
        $actualHash -cne $expectedHash) {
        throw 'TUSHARE_M1_ARTIFACT_BINDING_INVALID'
    }

    $stage = 'GIT_BASELINE'
    $head = (git rev-parse HEAD).Trim()
    $branch = (git branch --show-current).Trim()
    if ($head -ne $expectedCommit -or
        $branch -notin @('feature/1.4.0-agent-team',
            'codex/1.4.0-m1-research-data-ready') -or
        @(git status --porcelain=v1 --untracked-files=normal |
            Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' }).Count `
            -ne 0 -or @(git diff --cached --name-only).Count -ne 0) {
        throw 'TUSHARE_M1_GIT_BASELINE_INVALID'
    }
    git fetch --quiet origin $branch
    if ($LASTEXITCODE -ne 0 -or
        (git rev-parse "refs/remotes/origin/$branch").Trim() -ne $head) {
        throw 'TUSHARE_M1_GIT_REMOTE_BINDING_INVALID'
    }

    $stage = 'POSTGRES_HEALTH'
    Assert-PostgresListening
    if ($SecretMode -eq 'WINDOWS_CREDENTIAL_MANAGER') {
        $stage = 'CREDENTIAL_STATUS'
        $status = @(& "$PSScriptRoot\set-stock-quant-secrets.ps1" -Status 2>&1)
        if ($LASTEXITCODE -ne 0 -or
            $status -notcontains 'STOCK_QUANT_CREDENTIALS_READY=True') {
            throw 'TUSHARE_M1_CREDENTIALS_MISSING'
        }
    }

    $stage = 'M1_RUNNER'
    $output = @(& java "-Dloader.main=$runnerClass" -cp $artifact `
        'org.springframework.boot.loader.launch.PropertiesLauncher' `
        "--authorization-file=$authorization" "--result-file=$result" `
        "--secret-mode=$SecretMode" 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) {
        Write-Output "STOCK_QUANT_M1_FAILURE_STAGE=$(Get-Marker $output 'TUSHARE_M1_FAILURE_STAGE' 'FAILED_VALIDATION')"
        Write-Output "STOCK_QUANT_M1_FAILURE_REASON=$(Get-Marker $output 'TUSHARE_M1_FAILURE_REASON' 'TUSHARE_M1_EXECUTION_FAILED')"
        exit 20
    }
    $stage = 'RESULT_VALIDATION'
    $sanitized = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($sanitized.status -ne 'SUCCEEDED' -or
        [int]$sanitized.providerCallCount -ne 6 -or
        [int]$sanitized.retryCount -ne 0 -or
        -not $sanitized.researchDataset.typedFactReadback -or
        -not $sanitized.researchDataset.systemKnowledgeReadback -or
        -not $sanitized.researchDataset.dataQuality -or
        -not $sanitized.researchDataset.noFutureDataLeakage -or
        -not $sanitized.researchDataset.m2Readable -or
        -not $sanitized.outputAudit.clean) {
        throw 'TUSHARE_M1_RESULT_VALIDATION_FAILED'
    }
    Write-Output 'STOCK_QUANT_M1_AUTOMATION_STATUS=SUCCEEDED'
    Write-Output "STOCK_QUANT_M1_RUN_ID=$($sanitized.runId)"
    Write-Output "STOCK_QUANT_M1_RESULT=$result"
    exit 0
} catch {
    $reason = if ($_.Exception.Message -match
            '^[A-Z][A-Z0-9_]{7,127}$') {
        $_.Exception.Message
    } else { 'TUSHARE_M1_AUTOMATION_FAILED' }
    Write-Output "STOCK_QUANT_M1_FAILURE_STAGE=$stage"
    Write-Output "STOCK_QUANT_M1_FAILURE_REASON=$reason"
    exit 20
} finally { Pop-Location }
