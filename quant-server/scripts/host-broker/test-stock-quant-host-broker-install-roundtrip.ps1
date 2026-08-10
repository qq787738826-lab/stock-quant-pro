[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$formalTaskName = 'StockQuantLocalBroker'
$probePrefix = 'StockQuantHostBrokerRoundTrip_'
$probeTaskName = $probePrefix +
    [Guid]::NewGuid().ToString('N').ToUpperInvariant()
$repoRoot = (Resolve-Path -LiteralPath (
    Join-Path $PSScriptRoot '..\..\..')).Path
$targetRoot = (Resolve-Path -LiteralPath (
    Join-Path $repoRoot 'quant-server\target')).Path
$brokerScript = (Resolve-Path -LiteralPath (
    Join-Path $PSScriptRoot 'stock-quant-host-broker.ps1')).Path
$taskModule = Join-Path $PSScriptRoot `
    'StockQuantHostBroker.TaskDefinition.psm1'
$powershellExe = Join-Path $env:SystemRoot `
    'System32\WindowsPowerShell\v1.0\powershell.exe'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$created = $false
$passed = 0
$formalTaskBefore = $null
$formalTaskWasPresent = $false

function Get-CanonicalXml {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Xml
    )
    $document = [Xml.XmlDocument]::new()
    $document.PreserveWhitespace = $false
    $document.LoadXml($Xml)
    return $document.OuterXml
}

function Assert-ExpectedFailure {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock] $Action,
        [Parameter(Mandatory = $true)]
        [string] $ExpectedReason
    )
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -ceq $ExpectedReason) {
            $script:passed++
            return
        }
        throw
    }
    throw "STOCK_QUANT_HOST_BROKER_EXPECTED_FAILURE_MISSING_$ExpectedReason"
}

function Assert-ProbeDefinition {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Task,
        [switch] $Registered,
        [switch] $LegacyOnDemand
    )
    $arguments = @{
        Task = $Task
        ExpectedPowerShellExecutable = $powershellExe
        ExpectedBrokerScript = $brokerScript
        ExpectedWorkingDirectory = $repoRoot
        ExpectedUserSid = $identity.User.Value
    }
    if ($Registered) { $arguments['ExpectedTaskName'] = $probeTaskName }
    if ($LegacyOnDemand) { $arguments['AllowLegacyOnDemand'] = $true }
    Assert-StockQuantHostBrokerTaskDefinition @arguments
}

if ($identity.Name -match '(?i)CodexSandbox') {
    throw 'STOCK_QUANT_HOST_BROKER_REAL_USER_REQUIRED'
}
if ($PSVersionTable.PSVersion.Major -ne 5 -or
    $PSVersionTable.PSVersion.Minor -ne 1) {
    throw 'STOCK_QUANT_HOST_BROKER_POWERSHELL_51_REQUIRED'
}
$formalTask = Get-ScheduledTask -TaskName $formalTaskName `
    -ErrorAction SilentlyContinue
if ($null -ne $formalTask) {
    $formalTaskWasPresent = $true
    $formalTaskBefore = [string](Export-ScheduledTask `
        -TaskName $formalTaskName)
}
if ($null -ne (Get-ScheduledTask -TaskName $probeTaskName `
        -ErrorAction SilentlyContinue)) {
    throw 'STOCK_QUANT_HOST_BROKER_ROUNDTRIP_NAME_COLLISION'
}

Import-Module ScheduledTasks -ErrorAction Stop
Import-Module $taskModule -Force -ErrorAction Stop

try {
    $definition = New-StockQuantHostBrokerTaskDefinition `
        -PowerShellExecutable $powershellExe -BrokerScript $brokerScript `
        -WorkingDirectory $repoRoot -UserId $identity.Name `
        -Description 'Stock Quant host broker round-trip test; never executed.'
    Assert-ProbeDefinition -Task $definition
    $passed++

    $invalidDefinition = New-StockQuantHostBrokerTaskDefinition `
        -PowerShellExecutable $powershellExe -BrokerScript $brokerScript `
        -WorkingDirectory $targetRoot -UserId $identity.Name `
        -Description 'Stock Quant rollback test; never executed.'
    Assert-ExpectedFailure -ExpectedReason `
        'STOCK_QUANT_HOST_BROKER_TASK_ACTION_WORKING_DIRECTORY_MISMATCH' `
        -Action {
            Invoke-StockQuantHostBrokerTaskRegistrationTransaction `
                -TaskName $probeTaskName `
                -Definition $invalidDefinition `
                -ExpectedPowerShellExecutable $powershellExe `
                -ExpectedBrokerScript $brokerScript `
                -ExpectedWorkingDirectory $repoRoot `
                -ExpectedUserSid $identity.User.Value | Out-Null
        }
    if ($null -ne (Get-ScheduledTask -TaskName $probeTaskName `
            -ErrorAction SilentlyContinue)) {
        throw 'STOCK_QUANT_HOST_BROKER_NEW_TASK_ROLLBACK_FAILED'
    }
    $passed++

    $transaction =
        Invoke-StockQuantHostBrokerTaskRegistrationTransaction `
            -TaskName $probeTaskName -Definition $definition `
            -ExpectedPowerShellExecutable $powershellExe `
            -ExpectedBrokerScript $brokerScript `
            -ExpectedWorkingDirectory $repoRoot `
            -ExpectedUserSid $identity.User.Value
    $created = $true
    if (-not $transaction.Created -or $transaction.Updated) {
        throw 'STOCK_QUANT_HOST_BROKER_CREATE_TRANSACTION_INVALID'
    }
    $registered = Get-ScheduledTask -TaskName $probeTaskName `
        -ErrorAction Stop
    Assert-ProbeDefinition -Task $registered -Registered
    $passed++

    if ([string]$registered.Principal.UserId -ceq $identity.Name) {
        throw 'STOCK_QUANT_HOST_BROKER_PRINCIPAL_NORMALIZATION_NOT_OBSERVED'
    }
    $passed++

    $beforeUpdateXml = [string](Export-ScheduledTask `
        -TaskName $probeTaskName)
    Assert-ExpectedFailure -ExpectedReason `
        'STOCK_QUANT_HOST_BROKER_TASK_ACTION_WORKING_DIRECTORY_MISMATCH' `
        -Action {
            Invoke-StockQuantHostBrokerTaskRegistrationTransaction `
                -TaskName $probeTaskName `
                -Definition $invalidDefinition `
                -ExpectedPowerShellExecutable $powershellExe `
                -ExpectedBrokerScript $brokerScript `
                -ExpectedWorkingDirectory $repoRoot `
                -ExpectedUserSid $identity.User.Value | Out-Null
        }
    $afterUpdateXml = [string](Export-ScheduledTask `
        -TaskName $probeTaskName)
    if ((Get-CanonicalXml -Xml $beforeUpdateXml) -cne
        (Get-CanonicalXml -Xml $afterUpdateXml)) {
        throw 'STOCK_QUANT_HOST_BROKER_EXISTING_TASK_NOT_PRESERVED'
    }
    $registered = Get-ScheduledTask -TaskName $probeTaskName `
        -ErrorAction Stop
    Assert-ProbeDefinition -Task $registered -Registered
    $passed++

    $serialized = [string](Export-ScheduledTask -TaskName $probeTaskName)
    $serializedDocument = [Xml.XmlDocument]::new()
    $serializedDocument.LoadXml($serialized)
    if ($null -ne $serializedDocument.SelectSingleNode(
            "//*[local-name()='Password']")) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_XML_PASSWORD_FORBIDDEN'
    }
    $logonNode = $serializedDocument.SelectSingleNode(
        "//*[local-name()='LogonType']")
    if ($null -eq $logonNode -or
        $logonNode.InnerText -cne 'InteractiveToken') {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_XML_LOGON_TYPE_MISMATCH'
    }
    $logonTrigger = $serializedDocument.SelectSingleNode(
        "//*[local-name()='Triggers']/*[local-name()='LogonTrigger']")
    if ($null -eq $logonTrigger) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_XML_TRIGGER_MISMATCH'
    }
    $passed++

    Unregister-ScheduledTask -TaskName $probeTaskName `
        -Confirm:$false -ErrorAction Stop
    $created = $false
    if ($null -ne (Get-ScheduledTask -TaskName $probeTaskName `
            -ErrorAction SilentlyContinue)) {
        throw 'STOCK_QUANT_HOST_BROKER_SERIALIZATION_PREP_FAILED'
    }
    Register-ScheduledTask -TaskName $probeTaskName -Xml $serialized `
        -Force | Out-Null
    $created = $true
    $deserialized = Get-ScheduledTask -TaskName $probeTaskName `
        -ErrorAction Stop
    Assert-ProbeDefinition -Task $deserialized -Registered
    $passed++

    $actions = @($deserialized.Actions | Where-Object { $null -ne $_ })
    $triggers = @($deserialized.Triggers | Where-Object { $null -ne $_ })
    if ($actions.Count -ne 1 -or $triggers.Count -ne 1 -or
        [string]$triggers[0].CimClass.CimClassName -ne
            'MSFT_TaskLogonTrigger' -or
        [string]$deserialized.Principal.LogonType -ne 'Interactive' -or
        [string]$deserialized.Principal.RunLevel -ne 'Limited' -or
        -not [bool]$deserialized.Settings.AllowDemandStart -or
        [bool]$deserialized.Settings.StartWhenAvailable -or
        [int]$deserialized.Settings.RestartCount -ne 3 -or
        [string]$deserialized.Settings.RestartInterval -ne 'PT1M' -or
        [string]$deserialized.Settings.ExecutionTimeLimit -ne 'PT0S') {
        throw 'STOCK_QUANT_HOST_BROKER_NORMALIZED_DEFINITION_INVALID'
    }
    $passed++

    Unregister-ScheduledTask -TaskName $probeTaskName `
        -Confirm:$false -ErrorAction Stop
    $created = $false
    $legacyAction = New-ScheduledTaskAction -Execute $powershellExe `
        -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass ' +
            '-File "' + $brokerScript + '"') -WorkingDirectory $repoRoot
    $legacyPrincipal = New-ScheduledTaskPrincipal -UserId $identity.Name `
        -LogonType Interactive -RunLevel Limited
    $legacySettings = New-ScheduledTaskSettingsSet `
        -MultipleInstances IgnoreNew `
        -ExecutionTimeLimit ([TimeSpan]::FromMinutes(45)) `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $legacyDefinition = New-ScheduledTask -Action $legacyAction `
        -Principal $legacyPrincipal -Settings $legacySettings `
        -Description 'Legacy migration probe; never executed.'
    Register-ScheduledTask -TaskName $probeTaskName `
        -InputObject $legacyDefinition -Force | Out-Null
    $created = $true
    $legacyRegistered = Get-ScheduledTask -TaskName $probeTaskName `
        -ErrorAction Stop
    Assert-ProbeDefinition -Task $legacyRegistered -Registered `
        -LegacyOnDemand
    $passed++

    $migration = Invoke-StockQuantHostBrokerTaskRegistrationTransaction `
        -TaskName $probeTaskName -Definition $definition `
        -ExpectedPowerShellExecutable $powershellExe `
        -ExpectedBrokerScript $brokerScript `
        -ExpectedWorkingDirectory $repoRoot `
        -ExpectedUserSid $identity.User.Value
    if ($migration.Created -or -not $migration.Updated) {
        throw 'STOCK_QUANT_HOST_BROKER_LEGACY_MIGRATION_INVALID'
    }
    Assert-ProbeDefinition -Task (Get-ScheduledTask `
        -TaskName $probeTaskName -ErrorAction Stop) -Registered
    $passed++
} finally {
    if ($created -or $null -ne (Get-ScheduledTask `
            -TaskName $probeTaskName -ErrorAction SilentlyContinue)) {
        if ($probeTaskName -notmatch
                '^StockQuantHostBrokerRoundTrip_[A-F0-9]{32}$') {
            throw 'STOCK_QUANT_HOST_BROKER_ROUNDTRIP_CLEANUP_NAME_INVALID'
        }
        Unregister-ScheduledTask -TaskName $probeTaskName `
            -Confirm:$false -ErrorAction Stop
    }
    if ($null -ne (Get-ScheduledTask -TaskName $probeTaskName `
            -ErrorAction SilentlyContinue)) {
        throw 'STOCK_QUANT_HOST_BROKER_ROUNDTRIP_RESIDUAL'
    }
    $formalTaskAfter = Get-ScheduledTask -TaskName $formalTaskName `
        -ErrorAction SilentlyContinue
    if (($formalTaskWasPresent -and $null -eq $formalTaskAfter) -or
        (-not $formalTaskWasPresent -and $null -ne $formalTaskAfter)) {
        throw 'STOCK_QUANT_HOST_BROKER_FORMAL_TASK_TOUCHED'
    }
    if ($formalTaskWasPresent) {
        $formalTaskAfterXml = [string](Export-ScheduledTask `
            -TaskName $formalTaskName)
        if ((Get-CanonicalXml -Xml $formalTaskBefore) -cne
            (Get-CanonicalXml -Xml $formalTaskAfterXml)) {
            throw 'STOCK_QUANT_HOST_BROKER_FORMAL_TASK_TOUCHED'
        }
    }
}

Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALL_ROUNDTRIP=PASS'
Write-Output "STOCK_QUANT_HOST_BROKER_INSTALL_ROUNDTRIP_TESTS=$passed/0/0/0"
Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALL_ROUNDTRIP_MODE=TASK_SCHEDULER_REGISTER_GET_EXPORT_RESTORE'
Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALL_ROUNDTRIP_POWERSHELL=5.1'
Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALL_ROUNDTRIP_PRINCIPAL_NORMALIZATION=PASS'
Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALL_ROUNDTRIP_PROVIDER_CALLS=0'
Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALL_ROUNDTRIP_PERMANENT_DATABASE_WRITES=0'
Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALL_ROUNDTRIP_CREDENTIAL_READS=0'
Write-Output 'STOCK_QUANT_HOST_BROKER_INSTALL_ROUNDTRIP_RESIDUALS=0'
if ($passed -ne 12) {
    throw 'STOCK_QUANT_HOST_BROKER_INSTALL_ROUNDTRIP_COUNT_INVALID'
}
