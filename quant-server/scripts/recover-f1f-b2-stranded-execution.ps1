param(
    [Parameter(Mandatory = $true)] [string] $ExpectedCommit,
    [Parameter(Mandatory = $true)] [string] $AcceptanceId,
    [Parameter(Mandatory = $true)] [int] $DatabasePort,
    [Parameter(Mandatory = $true)] [string] $ResultFile
)

$ErrorActionPreference = 'Stop'
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$tempRoot = Join-Path $tempBase (
    'stock-quant-f1f-b2-recovery-' + [Guid]::NewGuid().ToString('N'))

if ($ExpectedCommit -notmatch '^[0-9a-f]{40}$' -or
    $AcceptanceId -notmatch '^[A-Z0-9_-]{8,64}$' -or
    $DatabasePort -le 0 -or $DatabasePort -gt 65535) {
    throw 'TUSHARE_CONTROLLED_ACCEPTANCE_RECOVERY_ARGUMENTS_INVALID'
}
$resolvedResult = [IO.Path]::GetFullPath($ResultFile)
if (-not $resolvedResult.StartsWith($tempBase,
        [StringComparison]::OrdinalIgnoreCase) -or
    -not [IO.Path]::GetFileName($resolvedResult).StartsWith(
        'stock-quant-f1f-b2-recovery-result-')) {
    throw 'TUSHARE_CONTROLLED_ACCEPTANCE_RECOVERY_RESULT_INVALID'
}
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $repoRoot
try {
    $actualCommit = (git rev-parse HEAD).Trim()
    $branch = (git branch --show-current).Trim()
    $statusLines = @(git status --porcelain=v1 --untracked-files=normal)
    $unexpected = @($statusLines | Where-Object {
        $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)'
    })
    if ($actualCommit -ne $ExpectedCommit -or
        ($branch -ne 'feature/1.4.0-agent-team' -and
         -not $branch.StartsWith('codex/')) -or
        $unexpected.Count -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_RECOVERY_BASELINE_INVALID'
    }
    $active = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -and
        $_.CommandLine.Contains($AcceptanceId)
    })
    if ($active.Count -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_RUNNER_STILL_ACTIVE'
    }

    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    $classpathFile = Join-Path $tempRoot 'classpath.txt'
    & .\mvnw.cmd -q -pl quant-server -am package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_RECOVERY_BUILD_FAILED'
    }
    & .\mvnw.cmd -q -pl quant-server dependency:build-classpath `
        "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $classpathFile)) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_RECOVERY_CLASSPATH_FAILED'
    }
    # Maven writes the dependency classpath as UTF-8. Windows PowerShell 5.1
    # otherwise decodes the non-ASCII user-profile path with the active ANSI
    # code page and silently produces unusable dependency paths.
    $dependencies = (Get-Content -Raw -Encoding UTF8 `
        -LiteralPath $classpathFile).Trim()
    $classpath = (Join-Path $repoRoot 'quant-server\target\classes') + ';' +
        (Join-Path $repoRoot 'quant-core\target\classes') + ';' + $dependencies
    & java -cp $classpath `
        com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceRecoveryRunner `
        "--acceptance-id=$AcceptanceId" "--database-port=$DatabasePort" `
        "--result-file=$resolvedResult"
    $javaExit = $LASTEXITCODE
    Write-Output "F1F_B2_RECOVERY_JAVA_EXIT=$javaExit"
    if ($javaExit -ne 0) {
        throw 'TUSHARE_CONTROLLED_ACCEPTANCE_RECOVERY_FAILED'
    }
} finally {
    Pop-Location
    if (Test-Path -LiteralPath $tempRoot) {
        $resolved = [IO.Path]::GetFullPath($tempRoot).TrimEnd('\', '/')
        if ([IO.Path]::GetDirectoryName($resolved).TrimEnd('\', '/') -ne $tempBase -or
            -not [IO.Path]::GetFileName($resolved).StartsWith(
                'stock-quant-f1f-b2-recovery-')) {
            throw 'TUSHARE_CONTROLLED_ACCEPTANCE_RECOVERY_TEMP_INVALID'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
