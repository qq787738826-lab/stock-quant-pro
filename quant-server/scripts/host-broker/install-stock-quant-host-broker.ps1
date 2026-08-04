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
$brokerScript = (Resolve-Path -LiteralPath (
    Join-Path $PSScriptRoot 'stock-quant-host-broker.ps1')).Path
$repoRoot = (Resolve-Path -LiteralPath (
    Join-Path $PSScriptRoot '..\..\..')).Path
$credentialStatusScript = Join-Path $repoRoot `
    'quant-server\scripts\set-stock-quant-secrets.ps1'
$powershellExe = Join-Path $env:SystemRoot `
    'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskArguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass ' +
    '-File "' + $brokerScript + '"'
$hostIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()

function Get-StockQuantSandboxIdentity {
    $target = Join-Path $repoRoot 'quant-server\target'
    if (-not (Test-Path -LiteralPath $target -PathType Container)) {
        New-Item -ItemType Directory -Path $target | Out-Null
    }
    $scratch = Join-Path $target `
        ('stock-quant-broker-install-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $scratch | Out-Null
    $oldTemp = $env:TEMP
    $oldTmp = $env:TMP
    try {
        $env:TEMP = $scratch
        $env:TMP = $scratch
        $identityOutput = @(& codex sandbox -P stock_quant_local `
            -C $repoRoot powershell -NoProfile -NonInteractive -Command `
            '[Security.Principal.WindowsIdentity]::GetCurrent().Name; [Security.Principal.WindowsIdentity]::GetCurrent().User.Value' `
            2>&1)
        if ($LASTEXITCODE -ne 0 -or $identityOutput.Count -ne 2 -or
            ([string]$identityOutput[0]) -notmatch '(?i)CodexSandbox' -or
            ([string]$identityOutput[1]) -notmatch '^S-1-5-[0-9-]+$') {
            throw 'STOCK_QUANT_HOST_BROKER_SANDBOX_IDENTITY_INVALID'
        }
        [pscustomobject]@{
            Name = [string]$identityOutput[0]
            Sid = [string]$identityOutput[1]
        }
    } finally {
        $env:TEMP = $oldTemp
        $env:TMP = $oldTmp
        if (Test-Path -LiteralPath $scratch) {
            $resolved = [IO.Path]::GetFullPath($scratch)
            $targetPrefix = [IO.Path]::GetFullPath($target).TrimEnd('\') + '\'
            if (-not $resolved.StartsWith(
                    $targetPrefix, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'STOCK_QUANT_HOST_BROKER_INSTALL_TEMP_PATH_INVALID'
            }
            Remove-Item -LiteralPath $resolved -Recurse -Force `
                -WhatIf:$false
        }
    }
}

function Set-BrokerScriptSandboxWriteDenied {
    param(
        [Parameter(Mandatory = $true)]
        [string] $SandboxSid,
        [Parameter(Mandatory = $true)]
        [bool] $Denied
    )
    $sid = [Security.Principal.SecurityIdentifier]::new($SandboxSid)
    $rights = [Security.AccessControl.FileSystemRights]::WriteData -bor
        [Security.AccessControl.FileSystemRights]::AppendData -bor
        [Security.AccessControl.FileSystemRights]::WriteExtendedAttributes -bor
        [Security.AccessControl.FileSystemRights]::WriteAttributes -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        $sid,
        $rights,
        [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Deny)
    $acl = Get-Acl -LiteralPath $PSScriptRoot
    if ($Denied) {
        $acl.SetAccessRule($rule)
    } else {
        [void]$acl.RemoveAccessRuleSpecific($rule)
    }
    Set-Acl -LiteralPath $PSScriptRoot -AclObject $acl
}

function Set-TaskRunAcl {
    param(
        [Parameter(Mandatory = $true)]
        [string] $OwnerSid,
        [Parameter(Mandatory = $true)]
        [string] $SandboxSid
    )
    $service = New-Object -ComObject 'Schedule.Service'
    $service.Connect()
    $registered = $service.GetFolder('\').GetTask("\$taskName")
    $sddl = 'D:P' +
        '(A;;GA;;;SY)' +
        '(A;;GA;;;BA)' +
        "(A;;GA;;;$OwnerSid)" +
        "(A;;GRGX;;;$SandboxSid)"
    $registered.SetSecurityDescriptor($sddl, 0)
}

function Assert-InstalledTask {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ExpectedUser
    )
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction Stop
    if ($task.Actions.Count -ne 1 -or
        -not ([string]$task.Actions[0].Execute).Equals(
            $powershellExe, [StringComparison]::OrdinalIgnoreCase) -or
        [string]$task.Actions[0].Arguments -cne $taskArguments -or
        $task.Triggers.Count -ne 0 -or
        -not ([string]$task.Principal.UserId).Equals(
            $ExpectedUser, [StringComparison]::OrdinalIgnoreCase) -or
        [string]$task.Principal.LogonType -ne 'Interactive' -or
        [string]$task.Principal.RunLevel -ne 'Limited') {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_DEFINITION_INVALID'
    }
}

if ($hostIdentity.Name -match '(?i)CodexSandbox') {
    throw 'STOCK_QUANT_HOST_BROKER_REAL_USER_REQUIRED'
}
if (-not (Test-Path -LiteralPath $credentialStatusScript -PathType Leaf) -or
    -not (Test-Path -LiteralPath $powershellExe -PathType Leaf)) {
    throw 'STOCK_QUANT_HOST_BROKER_INSTALL_PREREQUISITE_MISSING'
}

$sandboxIdentity = Get-StockQuantSandboxIdentity

if ($Uninstall) {
    Write-Output "STOCK_QUANT_HOST_BROKER_TASK=$taskName"
    Write-Output "STOCK_QUANT_HOST_BROKER_FIXED_SCRIPT=$brokerScript"
    Write-Output "STOCK_QUANT_HOST_BROKER_ACCOUNT=$($hostIdentity.Name)"
    if ($PSCmdlet.ShouldProcess($taskName, 'Uninstall fixed host broker')) {
        $existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($null -ne $existing) {
            Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        }
        Set-BrokerScriptSandboxWriteDenied `
            -SandboxSid $sandboxIdentity.Sid -Denied $false
        Write-Output 'STOCK_QUANT_HOST_BROKER_UNINSTALLED=true'
    }
    exit 0
}

$credentialStatus = @(& $credentialStatusScript -Status 2>&1)
if ($LASTEXITCODE -ne 0 -or
    $credentialStatus -notcontains 'STOCK_QUANT_CREDENTIALS_READY=True') {
    throw 'STOCK_QUANT_HOST_BROKER_CREDENTIALS_MISSING'
}

Write-Output "STOCK_QUANT_HOST_BROKER_TASK=$taskName"
Write-Output "STOCK_QUANT_HOST_BROKER_FIXED_SCRIPT=$brokerScript"
Write-Output "STOCK_QUANT_HOST_BROKER_ACCOUNT=$($hostIdentity.Name)"
Write-Output 'STOCK_QUANT_HOST_BROKER_LOGON_TYPE=Interactive'
Write-Output 'STOCK_QUANT_HOST_BROKER_RUN_LEVEL=Limited'
Write-Output 'STOCK_QUANT_HOST_BROKER_TRIGGERS=0'
Write-Output 'STOCK_QUANT_HOST_BROKER_DEMAND_START_ONLY=true'
Write-Output 'STOCK_QUANT_HOST_BROKER_CREDENTIALS_READY=true'
Write-Output "STOCK_QUANT_HOST_BROKER_TRIGGER_SID=$($sandboxIdentity.Sid)"

if ($PSCmdlet.ShouldProcess($taskName, 'Install or update fixed host broker')) {
    $registered = $false
    $writeDenied = $false
    try {
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
        Register-ScheduledTask -TaskName $taskName -InputObject $definition `
            -Force | Out-Null
        $registered = $true
        Set-TaskRunAcl -OwnerSid $hostIdentity.User.Value `
            -SandboxSid $sandboxIdentity.Sid
        Set-BrokerScriptSandboxWriteDenied -SandboxSid $sandboxIdentity.Sid `
            -Denied $true
        $writeDenied = $true
        Assert-InstalledTask -ExpectedUser $hostIdentity.Name
        Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALLED=true'
        Write-Output 'STOCK_QUANT_HOST_BROKER_PASSWORD_STORED_IN_TASK=false'
        Write-Output 'STOCK_QUANT_HOST_BROKER_PROVIDER_AUTOSTART=false'
    } catch {
        if ($registered) {
            Unregister-ScheduledTask -TaskName $taskName `
                -Confirm:$false -ErrorAction SilentlyContinue
        }
        if ($writeDenied) {
            Set-BrokerScriptSandboxWriteDenied `
                -SandboxSid $sandboxIdentity.Sid -Denied $false
        }
        throw
    }
}
