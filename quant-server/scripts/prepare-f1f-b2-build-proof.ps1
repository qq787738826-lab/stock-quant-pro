param(
    [Parameter(Mandatory = $true)] [string] $ExpectedCommit,
    [ValidateSet('PREPARATION_ONLY', 'CONTROLLED_BUILD_ARTIFACT')]
    [string] $Mode = 'PREPARATION_ONLY'
)

$ErrorActionPreference = 'Stop'
$requiredBranch = 'feature/1.4.0-agent-team'
if ($ExpectedCommit -notmatch '^[0-9a-f]{40}$') {
    throw 'TUSHARE_CONTROLLED_ACCEPTANCE_COMMIT_INVALID'
}
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$manifestFragment = $null
$tempArtifact = $null
$tempProof = $null
$completed = $false
Push-Location $repoRoot
try {
    $actualCommit = (git rev-parse HEAD).Trim()
    $actualBranch = (git branch --show-current).Trim()
    $remoteRef = if ($Mode -eq 'CONTROLLED_BUILD_ARTIFACT') {
        "refs/remotes/origin/$requiredBranch"
    } else {
        $candidateRef = "refs/remotes/origin/$actualBranch"
        git show-ref --verify --quiet $candidateRef
        if ($LASTEXITCODE -eq 0) { $candidateRef }
        else { "refs/remotes/origin/$requiredBranch" }
    }
    $remoteCommit = (git rev-parse $remoteRef).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_REMOTE_BASELINE_MISSING'
    }
    if ($actualCommit -ne $ExpectedCommit) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_BASELINE_MISMATCH'
    }
    if ($Mode -eq 'CONTROLLED_BUILD_ARTIFACT') {
        if ($actualBranch -ne $requiredBranch -or $remoteCommit -ne $ExpectedCommit) {
            throw 'TUSHARE_CONTROLLED_ACCEPTANCE_INTEGRATION_BASELINE_REQUIRED'
        }
    } elseif ($actualBranch -ne $requiredBranch -and
        -not $actualBranch.StartsWith('codex/')) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_PREPARATION_BRANCH_INVALID'
    }
    $statusLines = @(git status --porcelain=v1 --untracked-files=all)
    $unexpected = @($statusLines | Where-Object {
        $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)'
    })
    $indexChanges = @(git diff --cached --name-only)
    if ($unexpected.Count -ne 0 -or $indexChanges.Count -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_WORKSPACE_NOT_CLEAN'
    }

    $artifact = Join-Path $repoRoot 'quant-server\target\quant-server-1.3.1.jar'
    $proofPath = "$artifact.f1f-b2-proof.properties"
    foreach ($old in @($artifact, $proofPath)) {
        if (Test-Path -LiteralPath $old) {
            Remove-Item -LiteralPath $old -Force
        }
    }

    $mavenWrapper = if ($IsLinux -or $IsMacOS) {
        Join-Path $repoRoot 'mvnw'
    } else {
        Join-Path $repoRoot 'mvnw.cmd'
    }
    if (-not (Test-Path -LiteralPath $mavenWrapper)) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_MAVEN_WRAPPER_MISSING'
    }
    $mavenVersionOutput = @(& $mavenWrapper -version 2>&1)
    $mavenVersionMatch = [regex]::Match(
        ($mavenVersionOutput -join "`n"), 'Apache Maven\s+([0-9]+(?:\.[0-9]+)+)')
    if (-not $mavenVersionMatch.Success -or
        $mavenVersionMatch.Groups[1].Value -ne '3.9.16') {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_MAVEN_WRAPPER_VERSION_INVALID'
    }
    $mavenWrapperVersion = $mavenVersionMatch.Groups[1].Value

    & $mavenWrapper -pl quant-server -am clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_FAILED'
    }
    $artifact = (Resolve-Path $artifact).Path
    $proofPath = "$artifact.f1f-b2-proof.properties"
    $temporaryId = [Guid]::NewGuid().ToString('N')
    $tempArtifact = "$artifact.$temporaryId.tmp.jar"
    $tempProof = "$proofPath.$temporaryId.tmp"
    Move-Item -LiteralPath $artifact -Destination $tempArtifact
    $manifestFragment = Join-Path $env:TEMP (
        'stock-quant-f1f-b2-manifest-' + [Guid]::NewGuid().ToString('N') + '.mf')
    $javaVersionOutput = @()
    $javaVersionExitCode = -1
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # The Java launcher writes its version to stderr even on success. Windows
        # PowerShell turns redirected native stderr into ErrorRecord instances
        # when Stop is active, so capture it under Continue and verify the real
        # process exit code before restoring the fail-closed script preference.
        $ErrorActionPreference = 'Continue'
        $javaVersionOutput = @(& java -version 2>&1)
        $javaVersionExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($javaVersionExitCode -ne 0 -or $javaVersionOutput.Count -eq 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_JAVA_VERSION_UNAVAILABLE'
    }
    $javaVersionLine = $javaVersionOutput[0].ToString()
    $javaVersion = ($javaVersionLine -replace '[^A-Za-z0-9_.-]', '_')
    $buildTime = [DateTimeOffset]::UtcNow.ToString('o')
    $manifestContent = @(
        "Stock-Quant-Git-Commit: $actualCommit"
        "Stock-Quant-Git-Remote-Commit: $remoteCommit"
        "Stock-Quant-Git-Branch: $actualBranch"
        'Stock-Quant-Tracked-Clean: true'
        'Stock-Quant-Untracked-Scope-Clean: true'
        "Stock-Quant-Build-Time: $buildTime"
        "Stock-Quant-Java-Version: $javaVersion"
        'Stock-Quant-Module-Version: 1.3.1'
        "Stock-Quant-Maven-Wrapper-Version: $mavenWrapperVersion"
        "Stock-Quant-Build-Mode: $Mode"
        'Stock-Quant-Executor-Version: TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTOR_V1'
        'Stock-Quant-Qualification-Rule-Version: TUSHARE_CONTROLLED_ACCEPTANCE_RULE_V1'
        ''
    ) -join "`r`n"
    [IO.File]::WriteAllText(
        $manifestFragment, $manifestContent, [Text.UTF8Encoding]::new($false))
    & jar --update --file $tempArtifact --manifest $manifestFragment
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_MANIFEST_BIND_FAILED'
    }
    $jarEntries = @(& jar --list --file $tempArtifact)
    if ($LASTEXITCODE -ne 0 -or @($jarEntries | Where-Object {
        $_ -match '(^|/)\.ai(/|$)'
    }).Count -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_CONTEXT_INVALID'
    }

    $artifactHash = (Get-FileHash -LiteralPath $tempArtifact -Algorithm SHA256).Hash.ToLowerInvariant()
    $content = @(
        "git.commit=$actualCommit"
        "git.remote.commit=$remoteCommit"
        "git.branch=$actualBranch"
        'git.trackedClean=true'
        'git.untrackedScopeClean=true'
        "artifact.sha256=$artifactHash"
        "build.time=$buildTime"
        "java.version=$javaVersion"
        'module.version=1.3.1'
        "maven.wrapper.version=$mavenWrapperVersion"
        "build.mode=$Mode"
        'executor.version=TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTOR_V1'
        'qualification.rule.version=TUSHARE_CONTROLLED_ACCEPTANCE_RULE_V1'
    ) -join "`n"
    [IO.File]::WriteAllText(
        $tempProof, "$content`n", [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $tempArtifact -Destination $artifact
    $tempArtifact = $null
    Move-Item -LiteralPath $tempProof -Destination $proofPath
    $tempProof = $null
    $completed = $true
    Write-Output 'F1F_B2_BUILD_PROOF_CREATED=true'
    Write-Output "F1F_B2_BUILD_PROOF_MODE=$Mode"
    Write-Output "ARTIFACT_SHA256=$artifactHash"
} finally {
    Pop-Location
    if ($manifestFragment -and (Test-Path -LiteralPath $manifestFragment)) {
        Remove-Item -LiteralPath $manifestFragment -Force
    }
    foreach ($temporary in @($tempArtifact, $tempProof)) {
        if ($temporary -and (Test-Path -LiteralPath $temporary)) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
    if (-not $completed) {
        foreach ($incomplete in @(
            (Join-Path $repoRoot 'quant-server\target\quant-server-1.3.1.jar'),
            (Join-Path $repoRoot 'quant-server\target\quant-server-1.3.1.jar.f1f-b2-proof.properties')
        )) {
            if (Test-Path -LiteralPath $incomplete) {
                Remove-Item -LiteralPath $incomplete -Force
            }
        }
    }
}
