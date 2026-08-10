[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedCommit
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module ScheduledTasks -ErrorAction Stop
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.TaskDefinition.psm1') -Force

$paths = Get-StockQuantHostBrokerPaths
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$powershellExe = Join-Path $env:SystemRoot `
    'System32\WindowsPowerShell\v1.0\powershell.exe'
$credentialStatusScript = Join-Path $paths.RepositoryRoot `
    'quant-server\scripts\set-stock-quant-secrets.ps1'
$passed = 0

if ($identity.Name -match '(?i)CodexSandbox') {
    throw 'STOCK_QUANT_HOST_BROKER_REAL_USER_REQUIRED'
}
$task = Get-ScheduledTask -TaskName $paths.TaskName -ErrorAction Stop
Assert-StockQuantHostBrokerTaskDefinition -Task $task `
    -ExpectedPowerShellExecutable $powershellExe `
    -ExpectedBrokerScript $paths.BrokerScript `
    -ExpectedWorkingDirectory $paths.RepositoryRoot `
    -ExpectedUserSid $identity.User.Value `
    -ExpectedTaskName $paths.TaskName
$passed++

if ([string]$task.State -cne 'Running') {
    throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_NOT_RUNNING'
}
$passed++

$heartbeat = Read-StockQuantHostBrokerHeartbeat `
    -ExpectedGitCommit $ExpectedCommit
if ([string]$heartbeat.windowsUser -cne $identity.Name -or
    [string]$heartbeat.state -notin @('IDLE', 'BUSY')) {
    throw 'STOCK_QUANT_HOST_BROKER_HEARTBEAT_IDENTITY_INVALID'
}
$passed++

$process = Get-Process -Id ([int]$heartbeat.processId) -ErrorAction Stop
if ($process.HasExited) {
    throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_NOT_RUNNING'
}
$passed++

$credentialStatus = @(& $credentialStatusScript -Status 2>&1 |
    ForEach-Object { [string]$_ })
if ($LASTEXITCODE -ne 0 -or $credentialStatus.Count -ne 3 -or
    $credentialStatus -notcontains 'StockQuant/ResearchDbPassword=PRESENT' -or
    $credentialStatus -notcontains 'StockQuant/TushareToken=PRESENT' -or
    $credentialStatus -notcontains 'STOCK_QUANT_CREDENTIALS_READY=True') {
    throw 'STOCK_QUANT_HOST_BROKER_CREDENTIALS_MISSING'
}
$passed++

Write-Output "STOCK_QUANT_HOST_BROKER_RESIDENT_HEALTH_TESTS=$passed/0/0/0"
Write-Output "STOCK_QUANT_HOST_BROKER_ACCOUNT=$($identity.Name)"
Write-Output 'STOCK_QUANT_HOST_BROKER_LOGON_TYPE=Interactive'
Write-Output 'STOCK_QUANT_HOST_BROKER_RUN_LEVEL=Limited'
Write-Output 'STOCK_QUANT_HOST_BROKER_TRIGGERS=2'
Write-Output 'STOCK_QUANT_HOST_BROKER_TRIGGER_1=AT_LOGON_CURRENT_USER'
Write-Output 'STOCK_QUANT_HOST_BROKER_TRIGGER_2=WATCHDOG_EVERY_1_MINUTE'
Write-Output 'STOCK_QUANT_HOST_BROKER_WATCHDOG_INTERVAL=PT1M'
Write-Output 'STOCK_QUANT_HOST_BROKER_MULTIPLE_INSTANCES=IgnoreNew'
Write-Output 'STOCK_QUANT_HOST_BROKER_AUTOSTART=true'
Write-Output 'STOCK_QUANT_HOST_BROKER_PROVIDER_AUTOSTART=false'
Write-Output "STOCK_QUANT_HOST_BROKER_HEARTBEAT_STATE=$($heartbeat.state)"
Write-Output 'STOCK_QUANT_HOST_BROKER_CREDENTIALS_READY=true'
Write-Output 'STOCK_QUANT_HOST_BROKER_CREDENTIAL_CONTENT_READS=0'
Write-Output 'STOCK_QUANT_HOST_BROKER_PROVIDER_CALLS=0'
Write-Output 'STOCK_QUANT_HOST_BROKER_PERMANENT_DATABASE_WRITES=0'
if ($passed -ne 5) {
    throw 'STOCK_QUANT_HOST_BROKER_RESIDENT_HEALTH_COUNT_INVALID'
}
