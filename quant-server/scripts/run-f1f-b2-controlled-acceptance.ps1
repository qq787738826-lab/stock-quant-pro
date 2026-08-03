param(
    [Parameter(Mandatory = $true)]
    [string] $AuthorizationFile
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$artifact = Join-Path $repoRoot `
    'quant-server\target\quant-server-1.3.1-f1f-b2-runner.jar'
$proof = "$artifact.f1f-b2-proof.properties"
if (-not (Test-Path -LiteralPath $artifact) -or
    -not (Test-Path -LiteralPath $proof) -or
    -not (Test-Path -LiteralPath $AuthorizationFile)) {
    throw 'TUSHARE_CONTROLLED_ACCEPTANCE_LAUNCH_INPUT_MISSING'
}

& java -jar $artifact `
    "--authorization-file=$((Resolve-Path -LiteralPath $AuthorizationFile).Path)"
exit $LASTEXITCODE
