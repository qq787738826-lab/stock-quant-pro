[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $AuthorizationFile,
    [Parameter(Mandatory = $true)] [string] $ResultFile,
    [Parameter(Mandatory = $true)] [string] $ArtifactPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$preflightClass = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM1TokenVerificationPreflight'
$runnerClass = 'com.stockquant.server.agent.marketfacts.' +
    'TushareM1TokenVerificationRunner'
$stage = 'INITIALIZATION'

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
    $value = ([string]$matches[0]).Substring($prefix.Length)
    if ($value -notmatch '^[A-Z][A-Z0-9_]{7,127}$') { return $Fallback }
    return $value
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
        'TUSHARE_M1_TOKEN_VERIFICATION_AUTH_MISSING'
    $artifact = Resolve-RepoFile $ArtifactPath `
        'TUSHARE_M1_TOKEN_VERIFICATION_ARTIFACT_MISSING'
    $proof = Resolve-RepoFile "$artifact.f1f-b2-proof.properties" `
        'TUSHARE_M1_TOKEN_VERIFICATION_BUILD_PROOF_MISSING'
    $result = [IO.Path]::GetFullPath($ResultFile)
    if (-not $result.StartsWith(
            $repoRoot.TrimEnd('\', '/') + '\',
            [StringComparison]::OrdinalIgnoreCase) -or
        ($result -split '[\/]') -contains '.ai' -or
        (Test-Path -LiteralPath $result)) {
        throw 'TUSHARE_M1_TOKEN_VERIFICATION_RESULT_PATH_INVALID'
    }

    $stage = 'ARTIFACT_PREFLIGHT'
    $preflight = @(& java "-Dloader.main=$preflightClass" -cp $artifact `
        'org.springframework.boot.loader.launch.PropertiesLauncher' `
        "--authorization-file=$authorization" 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0 -or $preflight -notcontains
            'TUSHARE_M1_TOKEN_VERIFICATION_PREFLIGHT=PASS') {
        throw (Get-Marker $preflight `
            'TUSHARE_M1_TOKEN_VERIFICATION_PREFLIGHT_REASON' `
            'TUSHARE_M1_TOKEN_VERIFICATION_PREFLIGHT_FAILED')
    }
    $expectedCommit = (($preflight | Where-Object {
        $_ -like 'TUSHARE_M1_TOKEN_VERIFICATION_GIT_COMMIT=*'
    }) -split '=', 2)[1]
    $expectedHash = (($preflight | Where-Object {
        $_ -like 'TUSHARE_M1_TOKEN_VERIFICATION_ARTIFACT_SHA256=*'
    }) -split '=', 2)[1]
    $expectedProof = (($preflight | Where-Object {
        $_ -like 'TUSHARE_M1_TOKEN_VERIFICATION_BUILD_PROOF_PATH=*'
    }) -split '=', 2)[1]
    if ($expectedCommit -notmatch '^[0-9a-f]{40}$' -or
        $expectedHash -notmatch '^[0-9a-f]{64}$' -or
        ((Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
            ).ToLowerInvariant() -cne $expectedHash -or
        -not ([IO.Path]::GetFullPath($expectedProof)).Equals(
            $proof, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'TUSHARE_M1_TOKEN_VERIFICATION_ARTIFACT_BINDING_INVALID'
    }

    $stage = 'GIT_BASELINE'
    $branch = (git branch --show-current).Trim()
    $head = (git rev-parse HEAD).Trim()
    if ($head -ne $expectedCommit -or
        $branch -notin @('feature/1.4.0-agent-team',
            'codex/1.4.0-m1-research-data-ready') -or
        @(git status --porcelain=v1 --untracked-files=normal |
            Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' }
        ).Count -ne 0 -or @(git diff --cached --name-only).Count -ne 0) {
        throw 'TUSHARE_M1_TOKEN_VERIFICATION_GIT_BASELINE_INVALID'
    }
    git fetch --quiet origin $branch
    if ($LASTEXITCODE -ne 0 -or
        (git rev-parse "refs/remotes/origin/$branch").Trim() -ne $head) {
        throw 'TUSHARE_M1_TOKEN_VERIFICATION_GIT_REMOTE_BINDING_INVALID'
    }

    $stage = 'CREDENTIAL_STATUS'
    $status = @(& "$PSScriptRoot\set-stock-quant-secrets.ps1" -Status 2>&1)
    if ($LASTEXITCODE -ne 0 -or
        $status -notcontains 'StockQuant/TushareToken=PRESENT') {
        throw 'TUSHARE_M1_TOKEN_VERIFICATION_CREDENTIAL_MISSING'
    }

    $stage = 'TOKEN_VERIFICATION_RUNNER'
    $output = @(& java "-Dloader.main=$runnerClass" -cp $artifact `
        'org.springframework.boot.loader.launch.PropertiesLauncher' `
        "--authorization-file=$authorization" "--result-file=$result" `
        '--secret-mode=WINDOWS_CREDENTIAL_MANAGER' 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) {
        Write-Output ('STOCK_QUANT_M1_TOKEN_VERIFICATION_FAILURE_STAGE=' +
            (Get-Marker $output `
                'TUSHARE_M1_TOKEN_VERIFICATION_FAILURE_STAGE' `
                'FAILED_VALIDATION'))
        Write-Output ('STOCK_QUANT_M1_TOKEN_VERIFICATION_FAILURE_REASON=' +
            (Get-Marker $output `
                'TUSHARE_M1_TOKEN_VERIFICATION_FAILURE_REASON' `
                'TUSHARE_M1_TOKEN_VERIFICATION_FAILED'))
        exit 20
    }
    $stage = 'RESULT_VALIDATION'
    $sanitized = Get-Content -Raw -Encoding UTF8 -LiteralPath $result |
        ConvertFrom-Json
    if ($sanitized.status -ne 'SUCCEEDED' -or
        [int]$sanitized.providerCallCount -ne 1 -or
        [int]$sanitized.retryCount -ne 0 -or
        $sanitized.endpoint -ne 'daily' -or
        -not $sanitized.targetRowPresent -or
        -not $sanitized.responseJsonValid -or
        [int]$sanitized.providerCode -ne 0 -or
        -not $sanitized.outputAudit.clean -or
        $sanitized.prohibitedEffects.databaseConnected -or
        $sanitized.prohibitedEffects.databaseWritten) {
        throw 'TUSHARE_M1_TOKEN_VERIFICATION_RESULT_INVALID'
    }
    Write-Output 'STOCK_QUANT_M1_TOKEN_VERIFICATION_STATUS=SUCCEEDED'
    Write-Output "STOCK_QUANT_M1_TOKEN_VERIFICATION_RESULT=$result"
    exit 0
} catch {
    $reason = if ($_.Exception.Message -match
            '^[A-Z][A-Z0-9_]{7,127}$') {
        $_.Exception.Message
    } else { 'TUSHARE_M1_TOKEN_VERIFICATION_AUTOMATION_FAILED' }
    Write-Output "STOCK_QUANT_M1_TOKEN_VERIFICATION_FAILURE_STAGE=$stage"
    Write-Output "STOCK_QUANT_M1_TOKEN_VERIFICATION_FAILURE_REASON=$reason"
    exit 20
} finally { Pop-Location }
