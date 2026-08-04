param(
    [Parameter(Mandatory = $true)] [string] $ExpectedCommit,
    [ValidateSet('PREPARATION_ONLY', 'CONTROLLED_BUILD_ARTIFACT', 'E2E_DRY_RUN')]
    [string] $Mode = 'PREPARATION_ONLY'
)

$ErrorActionPreference = 'Stop'
$requiredBranch = 'feature/1.4.0-agent-team'
$artifactName = 'quant-server-1.3.1-f1f-b2-runner.jar'
$temporaryPrefix = 'stock-quant-f1f-b2-build-'
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$tempRoot = $null
$tempArtifact = $null
$tempProof = $null
$completed = $false

function Remove-VerifiedBuildRoot([string] $Path) {
    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) {
        return
    }
    $resolved = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    $parent = [IO.Path]::GetDirectoryName($resolved).TrimEnd('\', '/')
    $leaf = [IO.Path]::GetFileName($resolved)
    if ($parent -ne $tempBase -or -not $leaf.StartsWith($temporaryPrefix)) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_TEMP_ROOT_INVALID'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function Read-ZipEntryText([string] $Archive, [string] $EntryName) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) {
            throw 'TUSHARE_CONTROLLED_ACCEPTANCE_ARTIFACT_MANIFEST_INVALID'
        }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
        try { return $reader.ReadToEnd() }
        finally { $reader.Dispose() }
    } finally {
        $zip.Dispose()
    }
}

if ($ExpectedCommit -notmatch '^[0-9a-f]{40}$') {
    throw 'TUSHARE_CONTROLLED_ACCEPTANCE_COMMIT_INVALID'
}
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$artifact = Join-Path $repoRoot "quant-server\target\$artifactName"
$proofPath = "$artifact.f1f-b2-proof.properties"
$originalArtifact = "$artifact.original"

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
    $statusLines = @(git status --porcelain=v1 --untracked-files=normal)
    $unexpected = @($statusLines | Where-Object {
        $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)'
    })
    $indexChanges = @(git diff --cached --name-only)
    if ($unexpected.Count -ne 0 -or $indexChanges.Count -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_WORKSPACE_NOT_CLEAN'
    }

    foreach ($old in @($artifact, $proofPath, $originalArtifact)) {
        if (Test-Path -LiteralPath $old) {
            Remove-Item -LiteralPath $old -Force
        }
    }
    $targetDirectory = Split-Path -Parent $artifact
    if (-not (Test-Path -LiteralPath $targetDirectory)) {
        New-Item -ItemType Directory -Path $targetDirectory | Out-Null
    }

    $tempRoot = Join-Path $tempBase ($temporaryPrefix + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    $archivePath = Join-Path $tempRoot 'source.zip'
    $sourceRoot = Join-Path $tempRoot 'source'
    New-Item -ItemType Directory -Path $sourceRoot | Out-Null
    git archive --format=zip --output=$archivePath $actualCommit
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $archivePath)) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_SOURCE_ARCHIVE_FAILED'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($archivePath, $sourceRoot)

    $mavenWrapper = if ($IsLinux -or $IsMacOS) {
        Join-Path $sourceRoot 'mvnw'
    } else {
        Join-Path $sourceRoot 'mvnw.cmd'
    }
    if (-not (Test-Path -LiteralPath $mavenWrapper)) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_MAVEN_WRAPPER_MISSING'
    }
    Push-Location $sourceRoot
    try {
        $mavenVersionOutput = @(& $mavenWrapper -version 2>&1)
        $mavenVersionMatch = [regex]::Match(
            ($mavenVersionOutput -join "`n"),
            'Apache Maven\s+([0-9]+(?:\.[0-9]+)+)')
        if (-not $mavenVersionMatch.Success -or
            $mavenVersionMatch.Groups[1].Value -ne '3.9.16') {
            throw 'TUSHARE_CONTROLLED_ACCEPTANCE_MAVEN_WRAPPER_VERSION_INVALID'
        }
        $mavenWrapperVersion = $mavenVersionMatch.Groups[1].Value
        $mavenJavaVersionMatch = [regex]::Match(
            ($mavenVersionOutput -join "`n"),
            '(?m)^Java version:\s*([A-Za-z0-9._+\-]+)(?:,|\s*$)')
        if (-not $mavenJavaVersionMatch.Success) {
            throw 'TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_JAVA_VERSION_UNAVAILABLE'
        }
        $mavenJavaVersion = $mavenJavaVersionMatch.Groups[1].Value
        & $mavenWrapper -o -pl quant-server -am package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            throw 'TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_FAILED'
        }
    } finally {
        Pop-Location
    }

    $isolatedArtifact = Join-Path $sourceRoot 'quant-server\target\quant-server-1.3.1.jar'
    if (-not (Test-Path -LiteralPath $isolatedArtifact)) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_ARTIFACT_MISSING'
    }
    $temporaryId = [Guid]::NewGuid().ToString('N')
    $tempArtifact = "$artifact.$temporaryId.tmp.jar"
    $tempProof = "$proofPath.$temporaryId.tmp"
    Copy-Item -LiteralPath $isolatedArtifact -Destination $tempArtifact

    $javaVersionOutput = @()
    $javaVersionExitCode = -1
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $javaVersionOutput = @(& java -XshowSettings:properties -version 2>&1)
        $javaVersionExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $javaVersionMatch = [regex]::Match(
        ($javaVersionOutput -join "`n"),
        '(?m)^\s*java\.version\s*=\s*([A-Za-z0-9._+\-]+)\s*$')
    if ($javaVersionExitCode -ne 0 -or -not $javaVersionMatch.Success) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_JAVA_VERSION_UNAVAILABLE'
    }
    $javaVersion = $javaVersionMatch.Groups[1].Value
    if ($mavenJavaVersion -ne $javaVersion) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_JAVA_VERSION_MISMATCH'
    }
    $buildTime = [DateTimeOffset]::UtcNow.ToString('o')
    $manifestFragment = Join-Path $tempRoot 'runner-manifest.mf'
    $manifestContent = @(
        'Main-Class: org.springframework.boot.loader.launch.JarLauncher'
        'Start-Class: com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceRunner'
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
    $runnerEntry = 'BOOT-INF/classes/com/stockquant/server/agent/marketfacts/' +
        'TushareControlledAcceptanceRunner.class'
    $forbiddenEntries = @($jarEntries | Where-Object {
        $_ -match '(^|/)\.ai(/|$)' -or
        $_ -match '(^|/)(test|tests|test-classes)(/|$)' -or
        $_ -match 'Test\.class$' -or
        $_ -match 'BOOT-INF/lib/(junit|mockito|testcontainers)'
    })
    if ($LASTEXITCODE -ne 0 -or $jarEntries -notcontains $runnerEntry -or
        $forbiddenEntries.Count -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_CONTEXT_INVALID'
    }
    $manifest = (Read-ZipEntryText $tempArtifact 'META-INF/MANIFEST.MF') `
        -replace "`r?`n ", ''
    if ($manifest -notmatch '(?m)^Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher\s*$' -or
        $manifest -notmatch '(?m)^Start-Class: com\.stockquant\.server\.agent\.marketfacts\.TushareControlledAcceptanceRunner\s*$') {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_RUNNER_MANIFEST_INVALID'
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
    foreach ($temporary in @($tempArtifact, $tempProof)) {
        if ($temporary -and (Test-Path -LiteralPath $temporary)) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
    if (-not $completed) {
        foreach ($incomplete in @($artifact, $proofPath, $originalArtifact)) {
            if ($incomplete -and (Test-Path -LiteralPath $incomplete)) {
                Remove-Item -LiteralPath $incomplete -Force
            }
        }
    }
    Remove-VerifiedBuildRoot $tempRoot
}
