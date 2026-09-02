[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedCommit,
    [ValidateSet('RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT',
        'CONTROLLED_BUILD_ARTIFACT', 'E2E_DRY_RUN')]
    [string] $Mode = 'E2E_DRY_RUN'
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'prepare-f1f-b2-build-proof.ps1') `
    -ExpectedCommit $ExpectedCommit -Mode $Mode `
    -RunnerProfile MAINBOARD_TRADE_CAL_BACKFILL
