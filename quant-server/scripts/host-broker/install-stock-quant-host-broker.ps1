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
$credentialStatusScript = Join-Path $repoRoot `
    'quant-server\scripts\set-stock-quant-secrets.ps1'
$powershellExe = Join-Path $env:SystemRoot `
    'System32\WindowsPowerShell\v1.0\powershell.exe'
$hostIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$hostPrincipal = [Security.Principal.WindowsPrincipal]::new($hostIdentity)
$isAdministrator = $hostPrincipal.IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)

function Assert-TaskDefinition {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Task,
        [Parameter(Mandatory = $true)]
        [string] $ExpectedUser
    )
    $actions = @($Task.Actions)
    $triggers = @($Task.Triggers | Where-Object { $null -ne $_ })
    if ($actions.Count -ne 1 -or
        -not ([string]$actions[0].Execute).Equals(
            $powershellExe, [StringComparison]::OrdinalIgnoreCase) -or
        [string]$actions[0].Arguments -cne $taskArguments -or
        $triggers.Count -ne 0 -or
        -not ([string]$Task.Principal.UserId).Equals(
            $ExpectedUser, [StringComparison]::OrdinalIgnoreCase) -or
        [string]$Task.Principal.LogonType -ne 'Interactive' -or
        [string]$Task.Principal.RunLevel -ne 'Limited') {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_DEFINITION_INVALID'
    }
}

function Assert-InstalledTask {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ExpectedUser
    )
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction Stop
    Assert-TaskDefinition -Task $task -ExpectedUser $ExpectedUser
}

if ($hostIdentity.Name -match '(?i)CodexSandbox') {
    throw 'STOCK_QUANT_HOST_BROKER_REAL_USER_REQUIRED'
}
if (-not (Test-Path -LiteralPath $brokerScriptCandidate -PathType Leaf) -or
    -not (Test-Path -LiteralPath $credentialStatusScript -PathType Leaf) -or
    -not (Test-Path -LiteralPath $powershellExe -PathType Leaf)) {
    throw 'STOCK_QUANT_HOST_BROKER_INSTALL_PREREQUISITE_MISSING'
}
$brokerScript = (Resolve-Path -LiteralPath $brokerScriptCandidate).Path
$taskArguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass ' +
    '-File "' + $brokerScript + '"'
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
$action = New-ScheduledTaskAction -Execute $powershellExe `
    -Argument $taskArguments -WorkingDirectory $repoRoot
$principal = New-ScheduledTaskPrincipal -UserId $hostIdentity.Name `
    -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 45) `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$definition = New-ScheduledTask -Action $action -Principal $principal `
    -Settings $settings -Description `
    'Fixed on-demand Stock Quant host broker; no schedule and no secrets in task arguments.'
Assert-TaskDefinition -Task $definition -ExpectedUser $hostIdentity.Name

if ($PSCmdlet.ShouldProcess($taskName, 'Install or update fixed host broker')) {
    $registered = $false
    try {
        Register-ScheduledTask -TaskName $taskName -InputObject $definition `
            -Force | Out-Null
        $registered = $true
        Assert-InstalledTask -ExpectedUser $hostIdentity.Name
        Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALLED=true'
        Write-Output 'STOCK_QUANT_HOST_BROKER_PASSWORD_STORED_IN_TASK=false'
        Write-Output 'STOCK_QUANT_HOST_BROKER_PROVIDER_AUTOSTART=false'
    } catch {
        if ($registered) {
            Unregister-ScheduledTask -TaskName $taskName `
                -Confirm:$false -ErrorAction SilentlyContinue
        }
        throw
    }
} else {
    Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALL_PREFLIGHT=PASS'
    Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALLED=false'
}
