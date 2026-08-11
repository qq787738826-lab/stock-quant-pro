[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedCommit
)

$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\run-m2-strategy-research-e2e.ps1" `
    -ExpectedCommit $ExpectedCommit -IncludeM3
if ($LASTEXITCODE -ne 0) {
    throw 'M3_AGENT_RESEARCH_E2E_FAILED'
}
Write-Output 'M3_AGENT_RESEARCH_E2E=PASS'
exit 0
