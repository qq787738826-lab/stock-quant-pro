[CmdletBinding(
    SupportsShouldProcess = $true,
    ConfirmImpact = 'High',
    DefaultParameterSetName = 'Install'
)]
param(
    [Parameter(ParameterSetName = 'Uninstall', Mandatory = $true)]
    [switch] $Uninstall
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$taskName = 'StockQuantLocalBroker'
$repoRoot = (Resolve-Path -LiteralPath (
    Join-Path $PSScriptRoot '..\..\..')).Path
$brokerScriptCandidate = Join-Path $PSScriptRoot `
    'stock-quant-host-broker.ps1'
$taskDefinitionModule = Join-Path $PSScriptRoot `
    'StockQuantHostBroker.TaskDefinition.psm1'
$credentialStatusScript = Join-Path $repoRoot `
    'quant-server\scripts\set-stock-quant-secrets.ps1'
$powershellExe = Join-Path $env:SystemRoot `
    'System32\WindowsPowerShell\v1.0\powershell.exe'
$hostIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$hostPrincipal = [Security.Principal.WindowsPrincipal]::new($hostIdentity)
$isAdministrator = $hostPrincipal.IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)

if ($hostIdentity.Name -match '(?i)CodexSandbox') {
    throw 'STOCK_QUANT_HOST_BROKER_REAL_USER_REQUIRED'
}
if (-not (Test-Path -LiteralPath $brokerScriptCandidate -PathType Leaf) -or
    -not (Test-Path -LiteralPath $taskDefinitionModule -PathType Leaf) -or
    -not (Test-Path -LiteralPath $credentialStatusScript -PathType Leaf) -or
    -not (Test-Path -LiteralPath $powershellExe -PathType Leaf)) {
    throw 'STOCK_QUANT_HOST_BROKER_INSTALL_PREREQUISITE_MISSING'
}
$brokerScript = (Resolve-Path -LiteralPath $brokerScriptCandidate).Path
if (-not $isAdministrator -and -not $WhatIfPreference) {
    throw 'STOCK_QUANT_HOST_BROKER_ADMINISTRATOR_REQUIRED'
}

if ($Uninstall) {
    Write-Output "STOCK_QUANT_HOST_BROKER_TASK=$taskName"
    Write-Output "STOCK_QUANT_HOST_BROKER_FIXED_SCRIPT=$brokerScript"
    Write-Output "STOCK_QUANT_HOST_BROKER_ACCOUNT=$($hostIdentity.Name)"
    Write-Output "STOCK_QUANT_HOST_BROKER_ADMINISTRATOR=$isAdministrator"
    if ($PSCmdlet.ShouldProcess($taskName, 'Uninstall fixed host broker')) {
        $existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($null -ne $existing) {
            Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        }
        Write-Output 'STOCK_QUANT_HOST_BROKER_UNINSTALLED=true'
    }
    exit 0
}

$credentialStatus = @(& $credentialStatusScript -Status 2>&1)
$databaseCredentialPresent = $credentialStatus -contains `
    'StockQuant/ResearchDbPassword=PRESENT'
$tushareCredentialPresent = $credentialStatus -contains `
    'StockQuant/TushareToken=PRESENT'
Write-Output ('StockQuant/ResearchDbPassword=' +
    $(if ($databaseCredentialPresent) { 'PRESENT' } else { 'MISSING' }))
Write-Output ('StockQuant/TushareToken=' +
    $(if ($tushareCredentialPresent) { 'PRESENT' } else { 'MISSING' }))
if ($LASTEXITCODE -ne 0 -or $credentialStatus.Count -ne 3 -or
    -not $databaseCredentialPresent -or -not $tushareCredentialPresent -or
    $credentialStatus -notcontains 'STOCK_QUANT_CREDENTIALS_READY=True') {
    throw 'STOCK_QUANT_HOST_BROKER_CREDENTIALS_MISSING'
}

Write-Output "STOCK_QUANT_HOST_BROKER_TASK=$taskName"
Write-Output "STOCK_QUANT_HOST_BROKER_FIXED_SCRIPT=$brokerScript"
Write-Output "STOCK_QUANT_HOST_BROKER_ACCOUNT=$($hostIdentity.Name)"
Write-Output "STOCK_QUANT_HOST_BROKER_ADMINISTRATOR=$isAdministrator"
Write-Output 'STOCK_QUANT_HOST_BROKER_ADMINISTRATOR_REQUIRED=true'
Write-Output 'STOCK_QUANT_HOST_BROKER_LOGON_TYPE=Interactive'
Write-Output 'STOCK_QUANT_HOST_BROKER_RUN_LEVEL=Limited'
Write-Output 'STOCK_QUANT_HOST_BROKER_TRIGGERS=0'
Write-Output 'STOCK_QUANT_HOST_BROKER_DEMAND_START_ONLY=true'
Write-Output 'STOCK_QUANT_HOST_BROKER_CREDENTIALS_READY=true'
Write-Output 'STOCK_QUANT_HOST_BROKER_CODEX_CLI_REQUIRED=false'

Import-Module ScheduledTasks -ErrorAction Stop
Import-Module $taskDefinitionModule -Force -ErrorAction Stop
$definition = New-StockQuantHostBrokerTaskDefinition `
    -PowerShellExecutable $powershellExe -BrokerScript $brokerScript `
    -WorkingDirectory $repoRoot -UserId $hostIdentity.Name
Assert-StockQuantHostBrokerTaskDefinition -Task $definition `
    -ExpectedPowerShellExecutable $powershellExe `
    -ExpectedBrokerScript $brokerScript `
    -ExpectedWorkingDirectory $repoRoot `
    -ExpectedUserSid $hostIdentity.User.Value
$existingBeforeInstall = Get-ScheduledTask -TaskName $taskName `
    -ErrorAction SilentlyContinue
if ($null -ne $existingBeforeInstall) {
    Assert-StockQuantHostBrokerTaskDefinition -Task $existingBeforeInstall `
        -ExpectedPowerShellExecutable $powershellExe `
        -ExpectedBrokerScript $brokerScript `
        -ExpectedWorkingDirectory $repoRoot `
        -ExpectedUserSid $hostIdentity.User.Value `
        -ExpectedTaskName $taskName
}
Write-Output ("STOCK_QUANT_HOST_BROKER_EXISTING_TASK=" +
    $(if ($null -ne $existingBeforeInstall) { 'VALID' } else { 'ABSENT' }))

if ($PSCmdlet.ShouldProcess($taskName, 'Install or update fixed host broker')) {
    $transaction = Invoke-StockQuantHostBrokerTaskRegistrationTransaction `
        -TaskName $taskName -Definition $definition `
        -ExpectedPowerShellExecutable $powershellExe `
        -ExpectedBrokerScript $brokerScript `
        -ExpectedWorkingDirectory $repoRoot `
        -ExpectedUserSid $hostIdentity.User.Value
    Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALLED=true'
    Write-Output "STOCK_QUANT_HOST_BROKER_CREATED=$($transaction.Created)"
    Write-Output "STOCK_QUANT_HOST_BROKER_UPDATED=$($transaction.Updated)"
    Write-Output 'STOCK_QUANT_HOST_BROKER_PASSWORD_STORED_IN_TASK=false'
    Write-Output 'STOCK_QUANT_HOST_BROKER_PROVIDER_AUTOSTART=false'
} else {
    Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALL_PREFLIGHT=PASS'
    Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALLED=false'
}
