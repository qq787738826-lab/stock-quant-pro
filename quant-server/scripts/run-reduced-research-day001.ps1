[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $AuthorizationFile,

    [Parameter(Mandatory = $true)]
    [string] $ResultFile,

    [string] $ArtifactPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($ArtifactPath)) {
    $ArtifactPath = Join-Path $repoRoot `
        'quant-server\target\quant-server-1.3.1-f1f-b2-runner.jar'
}

function Resolve-RequiredFile([string] $Path, [string] $FailureCode) {
    if ([string]::IsNullOrWhiteSpace($Path) -or
        -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw $FailureCode
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Assert-OutsideAi([string] $Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $segments = $full -split '[\\/]'
    if ($segments -contains '.ai') {
        throw 'TUSHARE_REDUCED_RESEARCH_AI_PATH_FORBIDDEN'
    }
}

$authorization = Resolve-RequiredFile $AuthorizationFile `
    'TUSHARE_REDUCED_RESEARCH_AUTHORIZATION_MISSING'
$artifact = Resolve-RequiredFile $ArtifactPath `
    'TUSHARE_REDUCED_RESEARCH_ARTIFACT_MISSING'
$proof = Resolve-RequiredFile "$artifact.f1f-b2-proof.properties" `
    'TUSHARE_REDUCED_RESEARCH_BUILD_PROOF_MISSING'
$result = [IO.Path]::GetFullPath($ResultFile)

Assert-OutsideAi $authorization
Assert-OutsideAi $artifact
Assert-OutsideAi $proof
Assert-OutsideAi $result
if (Test-Path -LiteralPath $result) {
    throw 'TUSHARE_REDUCED_RESEARCH_RESULT_ALREADY_EXISTS'
}

$runnerClass = 'com.stockquant.server.agent.marketfacts.' +
    'TushareReducedResearchManualRunner'
& java "-Dloader.main=$runnerClass" -cp $artifact `
    'org.springframework.boot.loader.launch.PropertiesLauncher' `
    "--authorization-file=$authorization" "--result-file=$result"
$runnerExit = $LASTEXITCODE
if ($runnerExit -ne 0) {
    exit $runnerExit
}
exit 0
