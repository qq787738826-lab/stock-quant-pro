[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedCommit,
    [ValidateSet('M6_RESEARCH_PRODUCTION', 'M4_SHADOW_RESEARCH')]
    [string] $RunnerProfile = 'M6_RESEARCH_PRODUCTION'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ($RunnerProfile -eq 'M6_RESEARCH_PRODUCTION') {
    Push-Location (Join-Path $repoRoot 'quant-web')
    try {
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) {
            throw 'STOCK_QUANT_M6_PRODUCTION_WEB_BUILD_FAILED'
        }
    } finally {
        Pop-Location
    }
}

$branch = (& git -C $repoRoot branch --show-current).Trim()
$mode = if ($branch -eq 'feature/1.4.0-agent-team') {
    'CONTROLLED_BUILD_ARTIFACT'
} else { 'M6_STAGE_CONTROLLED_BUILD_ARTIFACT' }

& "$PSScriptRoot\prepare-f1f-b2-build-proof.ps1" `
    -ExpectedCommit $ExpectedCommit `
    -Mode $mode `
    -RunnerProfile $RunnerProfile
exit $LASTEXITCODE
