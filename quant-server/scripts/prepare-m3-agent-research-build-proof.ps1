[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedCommit,

    [ValidateSet('PREPARATION_ONLY', 'CONTROLLED_BUILD_ARTIFACT',
        'M3_STAGE_CONTROLLED_BUILD_ARTIFACT', 'E2E_DRY_RUN')]
    [string] $Mode = 'PREPARATION_ONLY'
)

$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\prepare-f1f-b2-build-proof.ps1" `
    -ExpectedCommit $ExpectedCommit -Mode $Mode `
    -RunnerProfile M3_AGENT_RESEARCH
exit $LASTEXITCODE
