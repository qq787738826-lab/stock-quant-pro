param(
    [ValidateSet('PREPARATION_ONLY', 'CONTROLLED_DATABASE_PREPARATION')]
    [string] $Mode = 'PREPARATION_ONLY',
    [Parameter(Mandatory = $true)] [string] $ExpectedCommit,
    [Parameter(Mandatory = $true)] [int] $DatabasePort,
    [Parameter(Mandatory = $true)] [string] $AdminUser,
    [string] $UserApprovalReference = ''
)

$ErrorActionPreference = 'Stop'
$requiredBranch = 'feature/1.4.0-agent-team'
$entryClass = 'com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDatabasePreparer'
$temporaryPrefix = 'stock-quant-f1f-b2-dbprep-'
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$tempRoot = $null

function Remove-VerifiedPreparationRoot([string] $Path) {
    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) { return }
    $resolved = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    $parent = [IO.Path]::GetDirectoryName($resolved).TrimEnd('\', '/')
    $leaf = [IO.Path]::GetFileName($resolved)
    if ($parent -ne $tempBase -or -not $leaf.StartsWith($temporaryPrefix)) {
        throw 'TUSHARE_DATABASE_PREPARATION_TEMP_ROOT_INVALID'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

if ($ExpectedCommit -notmatch '^[0-9a-f]{40}$') {
    throw 'TUSHARE_DATABASE_PREPARATION_COMMIT_INVALID'
}
if ($DatabasePort -le 0 -or $DatabasePort -gt 65535) {
    throw 'TUSHARE_DATABASE_PREPARATION_PORT_INVALID'
}
if ($AdminUser -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$' -or
    $AdminUser -eq 'stock_quant_research') {
    throw 'TUSHARE_DATABASE_PREPARATION_ADMIN_USER_INVALID'
}
if ($Mode -eq 'CONTROLLED_DATABASE_PREPARATION' -and
    [string]::IsNullOrWhiteSpace($UserApprovalReference)) {
    throw 'TUSHARE_DATABASE_PREPARATION_USER_APPROVAL_REQUIRED'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $repoRoot
try {
    git fetch origin --prune
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_DATABASE_PREPARATION_FETCH_FAILED'
    }
    $actualCommit = (git rev-parse HEAD).Trim()
    $actualBranch = (git branch --show-current).Trim()
    if ($actualCommit -ne $ExpectedCommit) {
        throw 'TUSHARE_DATABASE_PREPARATION_BASELINE_MISMATCH'
    }
    if ($Mode -eq 'CONTROLLED_DATABASE_PREPARATION') {
        $remoteRef = "refs/remotes/origin/$requiredBranch"
        if ($actualBranch -ne $requiredBranch) {
            throw 'TUSHARE_DATABASE_PREPARATION_INTEGRATION_BRANCH_REQUIRED'
        }
    } else {
        if ($actualBranch -ne $requiredBranch -and
            -not $actualBranch.StartsWith('codex/')) {
            throw 'TUSHARE_DATABASE_PREPARATION_BRANCH_INVALID'
        }
        $candidateRef = "refs/remotes/origin/$actualBranch"
        git show-ref --verify --quiet $candidateRef
        $remoteRef = if ($LASTEXITCODE -eq 0) {
            $candidateRef
        } else {
            "refs/remotes/origin/$requiredBranch"
        }
    }
    $remoteCommit = (git rev-parse $remoteRef).Trim()
    if ($LASTEXITCODE -ne 0 -or $remoteCommit -ne $ExpectedCommit) {
        throw 'TUSHARE_DATABASE_PREPARATION_REMOTE_BASELINE_MISMATCH'
    }
    $statusLines = @(git status --porcelain=v1 --untracked-files=normal)
    $unexpected = @($statusLines | Where-Object {
        $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)'
    })
    $indexChanges = @(git diff --cached --name-only)
    if ($unexpected.Count -ne 0 -or $indexChanges.Count -ne 0) {
        throw 'TUSHARE_DATABASE_PREPARATION_WORKSPACE_NOT_CLEAN'
    }

    & .\mvnw.cmd -o -pl quant-server -am clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_DATABASE_PREPARATION_BUILD_FAILED'
    }
    $sourceJar = Join-Path $repoRoot 'quant-server\target\quant-server-1.3.1.jar'
    if (-not (Test-Path -LiteralPath $sourceJar)) {
        throw 'TUSHARE_DATABASE_PREPARATION_ARTIFACT_MISSING'
    }
    $tempRoot = Join-Path $tempBase ($temporaryPrefix + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    $artifact = Join-Path $tempRoot 'quant-server-1.3.1-f1f-b2-dbprep.jar'
    $manifest = Join-Path $tempRoot 'dbprep-manifest.mf'
    Copy-Item -LiteralPath $sourceJar -Destination $artifact
    $manifestContent = @(
        'Main-Class: org.springframework.boot.loader.launch.JarLauncher'
        "Start-Class: $entryClass"
        "Stock-Quant-Database-Preparation-Commit: $actualCommit"
        'Stock-Quant-Database-Preparation-Entry-Version: F1F_B2_DATABASE_PREPARER_V1'
        ''
    ) -join "`r`n"
    [IO.File]::WriteAllText(
        $manifest, $manifestContent, [Text.UTF8Encoding]::new($false))
    & jar --update --file $artifact --manifest $manifest
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_DATABASE_PREPARATION_MANIFEST_BIND_FAILED'
    }
    $entries = @(& jar --list --file $artifact)
    $entryPath = 'BOOT-INF/classes/' + $entryClass.Replace('.', '/') + '.class'
    if ($LASTEXITCODE -ne 0 -or $entries -notcontains $entryPath) {
        throw 'TUSHARE_DATABASE_PREPARATION_ENTRY_MISSING'
    }

    $arguments = @(
        "--mode=$Mode"
        "--expected-commit=$ExpectedCommit"
        "--database-port=$DatabasePort"
        "--admin-user=$AdminUser"
    )
    if (-not [string]::IsNullOrWhiteSpace($UserApprovalReference)) {
        $arguments += "--user-approval-reference=$UserApprovalReference"
    }
    & java -jar $artifact @arguments
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw 'TUSHARE_DATABASE_PREPARATION_PROCESS_REJECTED'
    }
    exit 0
} finally {
    Pop-Location
    Remove-VerifiedPreparationRoot $tempRoot
}
