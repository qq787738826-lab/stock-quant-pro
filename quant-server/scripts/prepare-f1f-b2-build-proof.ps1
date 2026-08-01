param(
    [Parameter(Mandatory = $true)] [string] $ExpectedCommit,
    [Parameter(Mandatory = $true)] [string] $ArtifactPath
)

$ErrorActionPreference = 'Stop'
if ($ExpectedCommit -notmatch '^[0-9a-f]{40}$') {
    throw 'TUSHARE_CONTROLLED_ACCEPTANCE_COMMIT_INVALID'
}
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $repoRoot
try {
    $actualCommit = (git rev-parse HEAD).Trim()
    if ($actualCommit -ne $ExpectedCommit) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_BASELINE_MISMATCH'
    }
    $trackedChanges = git status --porcelain=v1 --untracked-files=no
    $indexChanges = git diff --cached --name-only
    if ($trackedChanges -or $indexChanges) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_WORKSPACE_NOT_CLEAN'
    }
    mvn -pl quant-server -am clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_FAILED' }
    $artifact = (Resolve-Path $ArtifactPath).Path
    $artifactHash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    $javaVersionLine = (& java -version 2>&1 | Select-Object -First 1).ToString()
    $javaVersion = ($javaVersionLine -replace '[^A-Za-z0-9_.-]', '_')
    $proofPath = "$artifact.f1f-b2-proof.properties"
    $content = @(
        "git.commit=$actualCommit"
        'git.trackedClean=true'
        "artifact.sha256=$artifactHash"
        "build.time=$([DateTimeOffset]::UtcNow.ToString('o'))"
        "java.version=$javaVersion"
        'module.version=1.3.1'
        'executor.version=TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTOR_V1'
        'qualification.rule.version=TUSHARE_CONTROLLED_ACCEPTANCE_RULE_V1'
    ) -join "`n"
    [IO.File]::WriteAllText($proofPath, "$content`n", [Text.UTF8Encoding]::new($false))
    Write-Output 'F1F_B2_BUILD_PROOF_CREATED=true'
    Write-Output "ARTIFACT_SHA256=$artifactHash"
} finally {
    Pop-Location
}
