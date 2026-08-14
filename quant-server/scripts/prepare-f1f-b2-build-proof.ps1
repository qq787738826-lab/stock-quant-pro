param(
    [Parameter(Mandatory = $true)] [string] $ExpectedCommit,
    [ValidateSet('PREPARATION_ONLY', 'CONTROLLED_BUILD_ARTIFACT',
        'M1_STAGE_CONTROLLED_BUILD_ARTIFACT',
        'M2_STAGE_CONTROLLED_BUILD_ARTIFACT',
        'M3_STAGE_CONTROLLED_BUILD_ARTIFACT',
        'M4_STAGE_CONTROLLED_BUILD_ARTIFACT',
        'M6_STAGE_CONTROLLED_BUILD_ARTIFACT',
        'RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT', 'E2E_DRY_RUN')]
    [string] $Mode = 'PREPARATION_ONLY',

    [ValidateSet('F1F_B2', 'REDUCED_RESEARCH_DAY001', 'M1_RESEARCH_DATA',
        'M2_STRATEGY_RESEARCH', 'M3_AGENT_RESEARCH', 'M4_SHADOW_RESEARCH',
        'M6_RESEARCH_PRODUCTION', 'RESEARCH_SELECTION')]
    [string] $RunnerProfile = 'F1F_B2'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$requiredBranch = 'feature/1.4.0-agent-team'
$artifactName = if ($RunnerProfile -eq 'RESEARCH_SELECTION') {
    'quant-server-1.3.1-research-selection-runner.jar'
} elseif ($RunnerProfile -eq 'M6_RESEARCH_PRODUCTION') {
    'quant-server-1.3.1-research-production.jar'
} elseif ($RunnerProfile -eq 'M4_SHADOW_RESEARCH') {
    'quant-server-1.3.1-m4-shadow-research-runner.jar'
} elseif ($RunnerProfile -eq 'M3_AGENT_RESEARCH') {
    'quant-server-1.3.1-m3-agent-research-runner.jar'
} elseif ($RunnerProfile -eq 'M2_STRATEGY_RESEARCH') {
    'quant-server-1.3.1-m2-strategy-research-runner.jar'
} elseif ($RunnerProfile -eq 'M1_RESEARCH_DATA') {
    'quant-server-1.3.1-m1-research-data-runner.jar'
} elseif ($RunnerProfile -eq 'REDUCED_RESEARCH_DAY001') {
    'quant-server-1.3.1-reduced-research-day001-runner.jar'
} else {
    'quant-server-1.3.1-f1f-b2-runner.jar'
}
$runnerStartClass = if ($RunnerProfile -eq 'RESEARCH_SELECTION') {
    'com.stockquant.server.agent.marketfacts.TushareResearchSelectionManualRunner'
} elseif ($RunnerProfile -eq 'M6_RESEARCH_PRODUCTION') {
    'com.stockquant.server.production.StockQuantResearchProductionRunner'
} elseif ($RunnerProfile -eq 'M4_SHADOW_RESEARCH') {
    'com.stockquant.server.agent.marketfacts.TushareM4ShadowResearchManualRunner'
} elseif ($RunnerProfile -eq 'M3_AGENT_RESEARCH') {
    'com.stockquant.server.agent.marketfacts.TushareM3AgentResearchManualRunner'
} elseif ($RunnerProfile -eq 'M2_STRATEGY_RESEARCH') {
    'com.stockquant.server.agent.marketfacts.TushareM2StrategyResearchManualRunner'
} elseif ($RunnerProfile -eq 'M1_RESEARCH_DATA') {
    'com.stockquant.server.agent.marketfacts.TushareM1ResearchDataManualRunner'
} elseif ($RunnerProfile -eq 'REDUCED_RESEARCH_DAY001') {
    'com.stockquant.server.agent.marketfacts.TushareReducedResearchManualRunner'
} else {
    'com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceRunner'
}
$temporaryPrefix = 'stock-quant-f1f-b2-build-'
$tempBase = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'quant-server\target')).TrimEnd('\', '/')
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
    } elseif ($Mode -eq 'M1_STAGE_CONTROLLED_BUILD_ARTIFACT') {
        if ($actualBranch -ne 'codex/1.4.0-m1-research-data-ready' -or
            $remoteCommit -ne $ExpectedCommit -or
            $RunnerProfile -ne 'M1_RESEARCH_DATA') {
            throw 'TUSHARE_M1_STAGE_BUILD_BASELINE_REQUIRED'
        }
    } elseif ($Mode -eq 'M2_STAGE_CONTROLLED_BUILD_ARTIFACT') {
        if ($actualBranch -ne 'codex/1.4.0-m2-strategy-engine-ready' -or
            $remoteCommit -ne $ExpectedCommit -or
            $RunnerProfile -ne 'M2_STRATEGY_RESEARCH') {
            throw 'STOCK_QUANT_M2_STAGE_BUILD_BASELINE_REQUIRED'
        }
    } elseif ($Mode -eq 'M3_STAGE_CONTROLLED_BUILD_ARTIFACT') {
        if ($actualBranch -ne 'codex/1.4.0-m3-agent-research-ready' -or
            $remoteCommit -ne $ExpectedCommit -or
            $RunnerProfile -ne 'M3_AGENT_RESEARCH') {
            throw 'STOCK_QUANT_M3_STAGE_BUILD_BASELINE_REQUIRED'
        }
    } elseif ($Mode -eq 'M4_STAGE_CONTROLLED_BUILD_ARTIFACT') {
        if ($actualBranch -ne 'codex/1.4.0-m4-shadow-research-ready' -or
            $remoteCommit -ne $ExpectedCommit -or
            $RunnerProfile -ne 'M4_SHADOW_RESEARCH') {
            throw 'STOCK_QUANT_M4_STAGE_BUILD_BASELINE_REQUIRED'
        }
    } elseif ($Mode -eq 'M6_STAGE_CONTROLLED_BUILD_ARTIFACT') {
        if ($actualBranch -ne 'codex/1.4.0-m6-research-production-ready' -or
            $remoteCommit -ne $ExpectedCommit -or
            $RunnerProfile -notin @(
                'M6_RESEARCH_PRODUCTION', 'M4_SHADOW_RESEARCH')) {
            throw 'STOCK_QUANT_M6_STAGE_BUILD_BASELINE_REQUIRED'
        }
    } elseif ($Mode -eq 'RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT') {
        if ($actualBranch -notin @(
                'codex/1.4.0-v1.0.1-research-selection-usability',
                'codex/1.4.0-v1.0.2-startup-self-heal-fix',
                'codex/1.4.0-v1.0.3-research-selection-runtime-fix') -or
            $remoteCommit -ne $ExpectedCommit -or
            $RunnerProfile -notin @(
                'RESEARCH_SELECTION', 'M6_RESEARCH_PRODUCTION')) {
            throw 'RESEARCH_SELECTION_BUILD_BASELINE_REQUIRED'
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

    if ($RunnerProfile -eq 'M6_RESEARCH_PRODUCTION') {
        $webDist = Join-Path $repoRoot 'quant-web\dist'
        $generatedWeb = Join-Path $sourceRoot `
            'quant-server\target\generated-resources\production-web'
        if (-not (Test-Path -LiteralPath (Join-Path $webDist 'index.html') `
                -PathType Leaf)) {
            throw 'STOCK_QUANT_M6_PRODUCTION_WEB_BUILD_MISSING'
        }
        New-Item -ItemType Directory -Path $generatedWeb -Force | Out-Null
        Copy-Item -Path (Join-Path $webDist '*') `
            -Destination $generatedWeb -Recurse -Force
    }

    $mavenWrapper = if ([Environment]::OSVersion.Platform -eq
            [PlatformID]::Win32NT) {
        Join-Path $sourceRoot 'mvnw.cmd'
    } else {
        Join-Path $sourceRoot 'mvnw'
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
        & $mavenWrapper -o -pl quant-server -am package -DskipTests `
            "-Dstart-class=$runnerStartClass"
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
    $runnerEntry = 'BOOT-INF/classes/' + ($runnerStartClass -replace '\.', '/') +
        '.class'
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
        $manifest -notmatch ('(?m)^Start-Class: ' +
            [regex]::Escape($runnerStartClass) + '\s*$')) {
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
    Write-Output "STOCK_QUANT_RUNNER_PROFILE=$RunnerProfile"
    Write-Output "STOCK_QUANT_RUNNER_START_CLASS=$runnerStartClass"
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
