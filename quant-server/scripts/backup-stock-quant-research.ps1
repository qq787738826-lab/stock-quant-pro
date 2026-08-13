[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\start-stock-quant-pro.ps1" -Action Backup
exit $LASTEXITCODE
