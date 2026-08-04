[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $AuthorizationFile,

    [Parameter(Mandatory = $true)]
    [string] $ResultFile,

    [string] $ArtifactPath,

    [ValidateSet('WINDOWS_CREDENTIAL_MANAGER', 'CONSOLE')]
    [string] $SecretMode = 'WINDOWS_CREDENTIAL_MANAGER'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$integrationBranch = 'feature/1.4.0-agent-team'
$runnerStartClass = 'com.stockquant.server.agent.marketfacts.' +
    'TushareReducedResearchManualRunner'
$preflightClass = 'com.stockquant.server.agent.marketfacts.' +
    'TushareReducedResearchDay001Preflight'
$stage = 'INITIALIZATION'

if ([string]::IsNullOrWhiteSpace($ArtifactPath)) {
    $ArtifactPath = Join-Path $repoRoot `
        'quant-server\target\quant-server-1.3.1-reduced-research-day001-runner.jar'
}

function Resolve-RequiredFile([string] $Path, [string] $Code) {
    if ([string]::IsNullOrWhiteSpace($Path) -or
        -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw $Code
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Assert-OutsideAi([string] $Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $rootPrefix = $repoRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith(
            $rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'STOCK_QUANT_LOCAL_AUTOMATION_REPOSITORY_PATH_REQUIRED'
    }
    if (($full -split '[\\/]') -contains '.ai') {
        throw 'STOCK_QUANT_LOCAL_AUTOMATION_AI_PATH_FORBIDDEN'
    }
}

function Read-ZipEntryText([string] $Archive, [string] $EntryName) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) {
            throw 'STOCK_QUANT_LOCAL_AUTOMATION_MANIFEST_INVALID'
        }
        $reader = [IO.StreamReader]::new(
            $entry.Open(), [Text.Encoding]::UTF8)
        try { return $reader.ReadToEnd() }
        finally { $reader.Dispose() }
    } finally {
        $zip.Dispose()
    }
}

function Get-SafeMarker(
    [object[]] $Lines,
    [string] $Name,
    [string] $Fallback
) {
    $prefix = "$Name="
    $line = @($Lines | Where-Object {
        $null -ne $_ -and ([string]$_).StartsWith($prefix)
    } | Select-Object -Last 1)
    if ($line.Count -eq 0) { return $Fallback }
    $value = ([string]$line[0]).Substring($prefix.Length)
    return $(if ($value -match '^[A-Z][A-Z0-9_]{2,127}$') {
        $value
    } else {
        $Fallback
    })
}

function Get-RequiredMarker(
    [object[]] $Lines,
    [string] $Name,
    [string] $FailureCode
) {
    $prefix = "$Name="
    $line = @($Lines | Where-Object {
        $null -ne $_ -and ([string]$_).StartsWith($prefix)
    })
    if ($line.Count -ne 1) { throw $FailureCode }
    return ([string]$line[0]).Substring($prefix.Length)
}

function Assert-PostgresListening {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync('127.0.0.1', 38432)
        if (-not $connect.Wait([TimeSpan]::FromSeconds(3)) -or
            -not $client.Connected) {
            throw 'STOCK_QUANT_RESEARCH_POSTGRES_NOT_LISTENING'
        }
    } catch {
        throw 'STOCK_QUANT_RESEARCH_POSTGRES_NOT_LISTENING'
    } finally {
        $client.Dispose()
    }
}

Push-Location $repoRoot
try {
    $stage = 'PATH_VALIDATION'
    $authorization = Resolve-RequiredFile $AuthorizationFile `
        'STOCK_QUANT_LOCAL_AUTOMATION_AUTHORIZATION_MISSING'
    $artifact = Resolve-RequiredFile $ArtifactPath `
        'STOCK_QUANT_LOCAL_AUTOMATION_ARTIFACT_MISSING'
    $proof = Resolve-RequiredFile "$artifact.f1f-b2-proof.properties" `
        'STOCK_QUANT_LOCAL_AUTOMATION_BUILD_PROOF_MISSING'
    $result = [IO.Path]::GetFullPath($ResultFile)
    foreach ($path in @($authorization, $artifact, $proof, $result)) {
        Assert-OutsideAi $path
    }
    if (Test-Path -LiteralPath $result) {
        throw 'STOCK_QUANT_LOCAL_AUTOMATION_RESULT_ALREADY_EXISTS'
    }

    $stage = 'ARTIFACT_PREFLIGHT'
    $manifest = (Read-ZipEntryText $artifact 'META-INF/MANIFEST.MF') `
        -replace "`r?`n ", ''
    if ($manifest -notmatch ('(?m)^Start-Class: ' +
            [regex]::Escape($runnerStartClass) + '\s*$') -or
        $manifest -notmatch '(?m)^Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher\s*$') {
        throw 'STOCK_QUANT_LOCAL_AUTOMATION_RUNNER_MANIFEST_INVALID'
    }
    $preflightOutput = @(& java "-Dloader.main=$preflightClass" `
        -cp $artifact 'org.springframework.boot.loader.launch.PropertiesLauncher' `
        "--authorization-file=$authorization" 2>&1)
    $preflightExit = $LASTEXITCODE
    if ($preflightExit -ne 0) {
        $reason = Get-SafeMarker $preflightOutput `
            'TUSHARE_REDUCED_RESEARCH_PREFLIGHT_REASON' `
            'TUSHARE_REDUCED_RESEARCH_PREFLIGHT_FAILED'
        throw $reason
    }
    $expectedCommit = Get-RequiredMarker $preflightOutput `
        'TUSHARE_REDUCED_RESEARCH_GIT_COMMIT' `
        'STOCK_QUANT_LOCAL_AUTOMATION_PREFLIGHT_OUTPUT_INVALID'
    $authorizedArtifactHash = Get-RequiredMarker $preflightOutput `
        'TUSHARE_REDUCED_RESEARCH_ARTIFACT_SHA256' `
        'STOCK_QUANT_LOCAL_AUTOMATION_PREFLIGHT_OUTPUT_INVALID'
    $authorizedProofPath = Get-RequiredMarker $preflightOutput `
        'TUSHARE_REDUCED_RESEARCH_BUILD_PROOF_PATH' `
        'STOCK_QUANT_LOCAL_AUTOMATION_PREFLIGHT_OUTPUT_INVALID'
    if ($expectedCommit -notmatch '^[0-9a-f]{40}$' -or
        $authorizedArtifactHash -notmatch '^[0-9a-f]{64}$') {
        throw 'STOCK_QUANT_LOCAL_AUTOMATION_PREFLIGHT_OUTPUT_INVALID'
    }
    $resolvedAuthorizedProof = Resolve-RequiredFile $authorizedProofPath `
        'STOCK_QUANT_LOCAL_AUTOMATION_AUTHORIZED_BUILD_PROOF_MISSING'
    Assert-OutsideAi $resolvedAuthorizedProof
    $actualArtifactHash = (Get-FileHash -LiteralPath $artifact `
        -Algorithm SHA256).Hash.ToLowerInvariant()
    if (-not $resolvedAuthorizedProof.Equals(
            $proof, [StringComparison]::OrdinalIgnoreCase) -or
        $actualArtifactHash -ne $authorizedArtifactHash) {
        throw 'STOCK_QUANT_LOCAL_AUTOMATION_ARTIFACT_BINDING_INVALID'
    }

    $stage = 'GIT_BASELINE'
    git fetch --quiet origin $integrationBranch
    if ($LASTEXITCODE -ne 0) {
        throw 'STOCK_QUANT_LOCAL_AUTOMATION_GIT_FETCH_FAILED'
    }
    $localHead = (git rev-parse HEAD).Trim()
    $branch = (git branch --show-current).Trim()
    $remoteHead = (git rev-parse "refs/remotes/origin/$integrationBranch").Trim()
    $divergence = (git rev-list --left-right --count `
        "$integrationBranch...origin/$integrationBranch").Trim() -split '\s+'
    $unexpected = @(git status --porcelain=v1 --untracked-files=normal |
        Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' })
    if ($branch -ne $integrationBranch -or $localHead -ne $expectedCommit -or
        $remoteHead -ne $expectedCommit -or $divergence.Count -ne 2 -or
        $divergence[0] -ne '0' -or $divergence[1] -ne '0' -or
        $unexpected.Count -ne 0 -or
        @(git diff --cached --name-only).Count -ne 0) {
        throw 'STOCK_QUANT_LOCAL_AUTOMATION_GIT_BASELINE_INVALID'
    }

    $stage = 'POSTGRES_HEALTH'
    Assert-PostgresListening

    if ($SecretMode -eq 'WINDOWS_CREDENTIAL_MANAGER') {
        $stage = 'CREDENTIAL_STATUS'
        $credentialStatus = @(& "$PSScriptRoot\set-stock-quant-secrets.ps1" `
            -Status 2>&1)
        if ($LASTEXITCODE -ne 0 -or
            $credentialStatus -notcontains 'STOCK_QUANT_CREDENTIALS_READY=True') {
            throw 'STOCK_QUANT_LOCAL_AUTOMATION_CREDENTIALS_MISSING'
        }
    }

    $stage = 'DAY001_RUNNER'
    $runnerOutput = @(& "$PSScriptRoot\run-reduced-research-day001.ps1" `
        -AuthorizationFile $authorization -ResultFile $result `
        -ArtifactPath $artifact -SecretMode $SecretMode 2>&1)
    $runnerExit = $LASTEXITCODE
    if ($runnerExit -ne 0) {
        $runnerStage = Get-SafeMarker $runnerOutput `
            'TUSHARE_REDUCED_RESEARCH_FAILURE_STAGE' 'FAILED_VALIDATION'
        $runnerReason = Get-SafeMarker $runnerOutput `
            'TUSHARE_REDUCED_RESEARCH_FAILURE_REASON' `
            'TUSHARE_REDUCED_RESEARCH_EXECUTION_FAILED'
        Write-Output "STOCK_QUANT_AUTOMATION_FAILURE_STAGE=$runnerStage"
        Write-Output "STOCK_QUANT_AUTOMATION_FAILURE_REASON=$runnerReason"
        Write-Output 'STOCK_QUANT_LOCAL_AUTOMATION_STATUS=FAILED'
        exit $runnerExit
    }

    $stage = 'RESULT_READBACK'
    $sanitizedResult = Get-Content -LiteralPath $result -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($sanitizedResult.status -ne 'SUCCEEDED' -or
        $sanitizedResult.providerCallCount -ne 3 -or
        $sanitizedResult.retryCount -ne 0 -or
        -not $sanitizedResult.outputAudit.clean -or
        $sanitizedResult.typedFactReadback -ne 'PASSED' -or
        $sanitizedResult.systemKnowledgeReadback -ne 'PASSED') {
        throw 'STOCK_QUANT_LOCAL_AUTOMATION_RESULT_VALIDATION_FAILED'
    }
    Write-Output 'STOCK_QUANT_LOCAL_AUTOMATION_STATUS=SUCCEEDED'
    Write-Output "STOCK_QUANT_LOCAL_AUTOMATION_RUN_ID=$($sanitizedResult.runId)"
    Write-Output "STOCK_QUANT_LOCAL_AUTOMATION_RESULT=$result"
    Write-Output 'STOCK_QUANT_LOCAL_AUTOMATION_PROVIDER_CALLS=3'
    Write-Output 'STOCK_QUANT_LOCAL_AUTOMATION_RETRIES=0'
    exit 0
} catch {
    $reason = if ($_.Exception.Message -match '^[A-Z][A-Z0-9_]{7,127}$') {
        $_.Exception.Message
    } else {
        'STOCK_QUANT_LOCAL_AUTOMATION_FAILED'
    }
    Write-Output "STOCK_QUANT_AUTOMATION_FAILURE_STAGE=$stage"
    Write-Output "STOCK_QUANT_AUTOMATION_FAILURE_REASON=$reason"
    Write-Output 'STOCK_QUANT_LOCAL_AUTOMATION_STATUS=FAILED'
    exit 20
} finally {
    Pop-Location
}
