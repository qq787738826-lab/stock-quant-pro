[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedCommit,

    [ValidateSet('PREPARATION_ONLY',
        'M4_STAGE_CONTROLLED_BUILD_ARTIFACT', 'E2E_DRY_RUN')]
    [string] $Mode = 'PREPARATION_ONLY'
)

$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\prepare-f1f-b2-build-proof.ps1" `
    -ExpectedCommit $ExpectedCommit -Mode $Mode `
    -RunnerProfile M4_SHADOW_RESEARCH
exit $LASTEXITCODE
