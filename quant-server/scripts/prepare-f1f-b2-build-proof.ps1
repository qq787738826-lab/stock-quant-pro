param(
    [Parameter(Mandatory = $true)] [string] $ExpectedCommit
)

$ErrorActionPreference = 'Stop'
$requiredBranch = 'feature/1.4.0-agent-team'
if ($ExpectedCommit -notmatch '^[0-9a-f]{40}$') {
    throw 'TUSHARE_CONTROLLED_ACCEPTANCE_COMMIT_INVALID'
}
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$manifestFragment = $null
Push-Location $repoRoot
try {
    $actualCommit = (git rev-parse HEAD).Trim()
    $actualBranch = (git branch --show-current).Trim()
    $remoteCommit = (git rev-parse "refs/remotes/origin/$requiredBranch").Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_REMOTE_BASELINE_MISSING'
    }
    if ($actualCommit -ne $ExpectedCommit) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_BASELINE_MISMATCH'
    }
    if ($actualBranch -ne $requiredBranch -or $remoteCommit -ne $ExpectedCommit) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_INTEGRATION_BASELINE_REQUIRED'
    }
    $statusLines = @(git status --porcelain=v1 --untracked-files=all)
    $unexpected = @($statusLines | Where-Object {
        $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)'
    })
    $indexChanges = @(git diff --cached --name-only)
    if ($unexpected.Count -ne 0 -or $indexChanges.Count -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_WORKSPACE_NOT_CLEAN'
    }

    mvn -pl quant-server -am clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_FAILED'
    }
    $artifact = (Resolve-Path (Join-Path $repoRoot 'quant-server\target\quant-server-1.3.1.jar')).Path
    $manifestFragment = Join-Path $env:TEMP (
        'stock-quant-f1f-b2-manifest-' + [Guid]::NewGuid().ToString('N') + '.mf')
    $javaVersionLine = (& java -version 2>&1 | Select-Object -First 1).ToString()
    $javaVersion = ($javaVersionLine -replace '[^A-Za-z0-9_.-]', '_')
    $buildTime = [DateTimeOffset]::UtcNow.ToString('o')
    $manifestContent = @(
        "Stock-Quant-Git-Commit: $actualCommit"
        "Stock-Quant-Git-Branch: $actualBranch"
        'Stock-Quant-Tracked-Clean: true'
        'Stock-Quant-Untracked-Scope-Clean: true'
        "Stock-Quant-Build-Time: $buildTime"
        "Stock-Quant-Java-Version: $javaVersion"
        'Stock-Quant-Module-Version: 1.3.1'
        'Stock-Quant-Executor-Version: TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTOR_V1'
        'Stock-Quant-Qualification-Rule-Version: TUSHARE_CONTROLLED_ACCEPTANCE_RULE_V1'
        ''
    ) -join "`r`n"
    [IO.File]::WriteAllText(
        $manifestFragment, $manifestContent, [Text.UTF8Encoding]::new($false))
    & jar --update --file $artifact --manifest $manifestFragment
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_MANIFEST_BIND_FAILED'
    }

    $artifactHash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    $proofPath = "$artifact.f1f-b2-proof.properties"
    $content = @(
        "git.commit=$actualCommit"
        "git.branch=$actualBranch"
        'git.trackedClean=true'
        'git.untrackedScopeClean=true'
        "artifact.sha256=$artifactHash"
        "build.time=$buildTime"
        "java.version=$javaVersion"
        'module.version=1.3.1'
        'executor.version=TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTOR_V1'
        'qualification.rule.version=TUSHARE_CONTROLLED_ACCEPTANCE_RULE_V1'
    ) -join "`n"
    [IO.File]::WriteAllText(
        $proofPath, "$content`n", [Text.UTF8Encoding]::new($false))
    Write-Output 'F1F_B2_BUILD_PROOF_CREATED=true'
    Write-Output "ARTIFACT_SHA256=$artifactHash"
} finally {
    Pop-Location
    if ($manifestFragment -and (Test-Path -LiteralPath $manifestFragment)) {
        Remove-Item -LiteralPath $manifestFragment -Force
    }
}
