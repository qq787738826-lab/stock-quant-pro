Set-StrictMode -Version Latest

$script:FormalTaskName = 'StockQuantLocalBroker'
$script:RoundTripTaskPattern =
    '^StockQuantHostBrokerRoundTrip_[A-F0-9]{32}$'
$script:ResidentExecutionTimeLimit = [TimeSpan]::Zero
$script:LegacyExecutionTimeLimit = [TimeSpan]::FromMinutes(45)
$script:ExpectedRestartCount = 3
$script:ExpectedRestartInterval = [TimeSpan]::FromMinutes(1)

function Assert-StockQuantAllowedTaskName {
    param(
        [Parameter(Mandatory = $true)]
        [string] $TaskName
    )
    if ($TaskName -cne $script:FormalTaskName -and
        $TaskName -notmatch $script:RoundTripTaskPattern) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_NAME_NOT_ALLOWED'
    }
}

function ConvertTo-StockQuantCanonicalPath {
    param(
        [AllowNull()]
        [object] $Value,
        [Parameter(Mandatory = $true)]
        [string] $FailureCode
    )
    try {
        $text = ([string]$Value).Trim()
        if ([string]::IsNullOrWhiteSpace($text)) { throw 'EMPTY' }
        if ($text.StartsWith('"') -or $text.EndsWith('"')) {
            if ($text.Length -lt 2 -or -not $text.StartsWith('"') -or
                -not $text.EndsWith('"')) {
                throw 'UNBALANCED_QUOTES'
            }
            $text = $text.Substring(1, $text.Length - 2)
        }
        $expanded = [Environment]::ExpandEnvironmentVariables($text)
        return [IO.Path]::GetFullPath($expanded).TrimEnd('\', '/')
    } catch {
        throw $FailureCode
    }
}

function ConvertTo-StockQuantPrincipalSid {
    param(
        [AllowNull()]
        [object] $Value
    )
    $text = ([string]$Value).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_PRINCIPAL_USER_MISMATCH'
    }
    try {
        if ($text -match '^S-1-5-[0-9-]+$') {
            return ([Security.Principal.SecurityIdentifier]::new($text)).Value
        }
        $candidates = [Collections.Generic.List[string]]::new()
        $candidates.Add($text)
        if ($text -notmatch '\\') {
            $candidates.Add("$env:COMPUTERNAME\$text")
        }
        foreach ($candidate in $candidates) {
            try {
                $account = [Security.Principal.NTAccount]::new($candidate)
                $sid = $account.Translate(
                    [Security.Principal.SecurityIdentifier])
                return $sid.Value
            } catch {
                # Try the next safe local account representation.
            }
        }
    } catch {
        # The caller receives one stable sanitized reason below.
    }
    throw 'STOCK_QUANT_HOST_BROKER_TASK_PRINCIPAL_USER_MISMATCH'
}

function ConvertTo-StockQuantDuration {
    param(
        [AllowNull()]
        [object] $Value,
        [string] $FailureCode =
            'STOCK_QUANT_HOST_BROKER_TASK_EXECUTION_TIME_LIMIT_MISMATCH'
    )
    try {
        if ($Value -is [TimeSpan]) { return [TimeSpan]$Value }
        $text = ([string]$Value).Trim()
        if ([string]::IsNullOrWhiteSpace($text)) { throw 'EMPTY' }
        if ($text.StartsWith('P', [StringComparison]::OrdinalIgnoreCase)) {
            return [Xml.XmlConvert]::ToTimeSpan($text)
        }
        return [TimeSpan]::Parse($text)
    } catch {
        throw $FailureCode
    }
}

function ConvertTo-StockQuantBoolean {
    param(
        [AllowNull()]
        [object] $Value,
        [Parameter(Mandatory = $true)]
        [string] $FailureCode
    )
    try {
        if ($Value -is [bool]) { return [bool]$Value }
        return [Convert]::ToBoolean(([string]$Value).Trim())
    } catch {
        throw $FailureCode
    }
}

function Get-StockQuantBrokerPathFromArguments {
    param(
        [AllowNull()]
        [object] $Arguments
    )
    $text = ([string]$Arguments).Trim()
    $pattern = '^(?i:-NoProfile)\s+(?i:-NonInteractive)\s+' +
        '(?i:-ExecutionPolicy)\s+(?i:Bypass)\s+(?i:-File)\s+' +
        '(?:"(?<quoted>[^"]+)"|(?<bare>[^\s"]+))\s*$'
    $match = [regex]::Match($text, $pattern)
    if (-not $match.Success) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_ACTION_ARGUMENTS_MISMATCH'
    }
    $path = $(if ($match.Groups['quoted'].Success) {
        $match.Groups['quoted'].Value
    } else {
        $match.Groups['bare'].Value
    })
    return ConvertTo-StockQuantCanonicalPath -Value $path `
        -FailureCode `
            'STOCK_QUANT_HOST_BROKER_TASK_ACTION_ARGUMENTS_MISMATCH'
}

function Get-StockQuantCanonicalXml {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Xml
    )
    $document = [Xml.XmlDocument]::new()
    $document.PreserveWhitespace = $false
    $document.LoadXml($Xml)
    return $document.OuterXml
}

function New-StockQuantHostBrokerTaskDefinition {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $PowerShellExecutable,
        [Parameter(Mandatory = $true)]
        [string] $BrokerScript,
        [Parameter(Mandatory = $true)]
        [string] $WorkingDirectory,
        [Parameter(Mandatory = $true)]
        [string] $UserId,
        [string] $Description =
            'Fixed resident Stock Quant host broker; request-driven only and no secrets in task arguments.'
    )
    $arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass ' +
        '-File "' + $BrokerScript + '"'
    $action = New-ScheduledTaskAction -Execute $PowerShellExecutable `
        -Argument $arguments -WorkingDirectory $WorkingDirectory
    $principal = New-ScheduledTaskPrincipal -UserId $UserId `
        -LogonType Interactive -RunLevel Limited
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $UserId
    $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew `
        -ExecutionTimeLimit $script:ResidentExecutionTimeLimit `
        -RestartCount $script:ExpectedRestartCount `
        -RestartInterval $script:ExpectedRestartInterval `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    return New-ScheduledTask -Action $action -Principal $principal `
        -Trigger $trigger -Settings $settings -Description $Description
}

function Assert-StockQuantHostBrokerTaskDefinition {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object] $Task,
        [Parameter(Mandatory = $true)]
        [string] $ExpectedPowerShellExecutable,
        [Parameter(Mandatory = $true)]
        [string] $ExpectedBrokerScript,
        [Parameter(Mandatory = $true)]
        [string] $ExpectedWorkingDirectory,
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^S-1-5-[0-9-]+$')]
        [string] $ExpectedUserSid,
        [string] $ExpectedTaskName = '',
        [switch] $AllowLegacyOnDemand
    )
    if (-not [string]::IsNullOrWhiteSpace($ExpectedTaskName)) {
        Assert-StockQuantAllowedTaskName -TaskName $ExpectedTaskName
        if ([string]$Task.TaskName -cne $ExpectedTaskName -or
            [string]$Task.TaskPath -cne '\') {
            throw 'STOCK_QUANT_HOST_BROKER_TASK_NAME_MISMATCH'
        }
    }

    $actions = @($Task.Actions | Where-Object { $null -ne $_ })
    if ($actions.Count -ne 1) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_ACTION_COUNT_MISMATCH'
    }
    $actualExecutable = ConvertTo-StockQuantCanonicalPath `
        -Value $actions[0].Execute `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_TASK_ACTION_EXECUTE_MISMATCH'
    $expectedExecutable = ConvertTo-StockQuantCanonicalPath `
        -Value $ExpectedPowerShellExecutable `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_TASK_ACTION_EXECUTE_MISMATCH'
    if (-not $actualExecutable.Equals(
            $expectedExecutable, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_ACTION_EXECUTE_MISMATCH'
    }

    $actualBroker = Get-StockQuantBrokerPathFromArguments `
        -Arguments $actions[0].Arguments
    $expectedBroker = ConvertTo-StockQuantCanonicalPath `
        -Value $ExpectedBrokerScript `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_TASK_ACTION_ARGUMENTS_MISMATCH'
    if (-not $actualBroker.Equals(
            $expectedBroker, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_ACTION_ARGUMENTS_MISMATCH'
    }

    $actualWorkingDirectory = ConvertTo-StockQuantCanonicalPath `
        -Value $actions[0].WorkingDirectory -FailureCode `
            'STOCK_QUANT_HOST_BROKER_TASK_ACTION_WORKING_DIRECTORY_MISMATCH'
    $expectedWorkingDirectory = ConvertTo-StockQuantCanonicalPath `
        -Value $ExpectedWorkingDirectory -FailureCode `
            'STOCK_QUANT_HOST_BROKER_TASK_ACTION_WORKING_DIRECTORY_MISMATCH'
    if (-not $actualWorkingDirectory.Equals(
            $expectedWorkingDirectory,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw `
            'STOCK_QUANT_HOST_BROKER_TASK_ACTION_WORKING_DIRECTORY_MISMATCH'
    }

    $actualUserSid = ConvertTo-StockQuantPrincipalSid `
        -Value $Task.Principal.UserId
    if ($actualUserSid -cne $ExpectedUserSid) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_PRINCIPAL_USER_MISMATCH'
    }
    if ([string]$Task.Principal.LogonType -notin
            @('Interactive', 'InteractiveToken')) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_LOGON_TYPE_MISMATCH'
    }
    if ([string]$Task.Principal.RunLevel -notin
            @('Limited', 'LeastPrivilege')) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_RUN_LEVEL_MISMATCH'
    }

    $triggers = @($Task.Triggers | Where-Object { $null -ne $_ })
    $legacyOnDemand = $AllowLegacyOnDemand -and $triggers.Count -eq 0
    if (-not $legacyOnDemand -and $triggers.Count -ne 1) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_TRIGGER_COUNT_MISMATCH'
    }
    if (-not $legacyOnDemand) {
        $triggerType = [string]$triggers[0].CimClass.CimClassName
        if ($triggerType -cne 'MSFT_TaskLogonTrigger') {
            throw 'STOCK_QUANT_HOST_BROKER_TASK_TRIGGER_TYPE_MISMATCH'
        }
        $triggerUserSid = ConvertTo-StockQuantPrincipalSid `
            -Value $triggers[0].UserId
        if ($triggerUserSid -cne $ExpectedUserSid) {
            throw 'STOCK_QUANT_HOST_BROKER_TASK_TRIGGER_USER_MISMATCH'
        }
        $triggerEnabled = ConvertTo-StockQuantBoolean `
            -Value $triggers[0].Enabled `
            -FailureCode 'STOCK_QUANT_HOST_BROKER_TASK_TRIGGER_ENABLED_MISMATCH'
        if (-not $triggerEnabled) {
            throw 'STOCK_QUANT_HOST_BROKER_TASK_TRIGGER_ENABLED_MISMATCH'
        }
    }

    $duration = ConvertTo-StockQuantDuration `
        -Value $Task.Settings.ExecutionTimeLimit
    $expectedDuration = $(if ($legacyOnDemand) {
        $script:LegacyExecutionTimeLimit
    } else { $script:ResidentExecutionTimeLimit })
    if ($duration -ne $expectedDuration) {
        throw `
            'STOCK_QUANT_HOST_BROKER_TASK_EXECUTION_TIME_LIMIT_MISMATCH'
    }
    $allowDemandStart = ConvertTo-StockQuantBoolean `
        -Value $Task.Settings.AllowDemandStart -FailureCode `
            'STOCK_QUANT_HOST_BROKER_TASK_ALLOW_DEMAND_START_MISMATCH'
    if (-not $allowDemandStart) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_ALLOW_DEMAND_START_MISMATCH'
    }
    $startWhenAvailable = ConvertTo-StockQuantBoolean `
        -Value $Task.Settings.StartWhenAvailable -FailureCode `
            'STOCK_QUANT_HOST_BROKER_TASK_START_WHEN_AVAILABLE_MISMATCH'
    if ($startWhenAvailable) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_START_WHEN_AVAILABLE_MISMATCH'
    }
    $startOnBattery = ConvertTo-StockQuantBoolean `
        -Value $Task.Settings.DisallowStartIfOnBatteries `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_TASK_SETTINGS_MISMATCH'
    $stopOnBattery = ConvertTo-StockQuantBoolean `
        -Value $Task.Settings.StopIfGoingOnBatteries `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_TASK_SETTINGS_MISMATCH'
    $enabled = ConvertTo-StockQuantBoolean -Value $Task.Settings.Enabled `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_TASK_SETTINGS_MISMATCH'
    $hidden = ConvertTo-StockQuantBoolean -Value $Task.Settings.Hidden `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_TASK_SETTINGS_MISMATCH'
    if ([string]$Task.Settings.MultipleInstances -ne 'IgnoreNew' -or
        $startOnBattery -or $stopOnBattery -or -not $enabled -or $hidden) {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_SETTINGS_MISMATCH'
    }
    if (-not $legacyOnDemand) {
        $restartCount = [int]$Task.Settings.RestartCount
        $restartInterval = ConvertTo-StockQuantDuration `
            -Value $Task.Settings.RestartInterval -FailureCode `
                'STOCK_QUANT_HOST_BROKER_TASK_RESTART_SETTINGS_MISMATCH'
        if ($restartCount -ne $script:ExpectedRestartCount -or
            $restartInterval -ne $script:ExpectedRestartInterval) {
            throw 'STOCK_QUANT_HOST_BROKER_TASK_RESTART_SETTINGS_MISMATCH'
        }
    }
}

function Invoke-StockQuantHostBrokerTaskRegistrationTransaction {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $TaskName,
        [Parameter(Mandatory = $true)]
        [object] $Definition,
        [Parameter(Mandatory = $true)]
        [string] $ExpectedPowerShellExecutable,
        [Parameter(Mandatory = $true)]
        [string] $ExpectedBrokerScript,
        [Parameter(Mandatory = $true)]
        [string] $ExpectedWorkingDirectory,
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^S-1-5-[0-9-]+$')]
        [string] $ExpectedUserSid
    )
    Assert-StockQuantAllowedTaskName -TaskName $TaskName
    $existing = Get-ScheduledTask -TaskName $TaskName `
        -ErrorAction SilentlyContinue
    $existingXml = $null
    if ($null -ne $existing) {
        Assert-StockQuantHostBrokerTaskDefinition -Task $existing `
            -ExpectedPowerShellExecutable $ExpectedPowerShellExecutable `
            -ExpectedBrokerScript $ExpectedBrokerScript `
            -ExpectedWorkingDirectory $ExpectedWorkingDirectory `
            -ExpectedUserSid $ExpectedUserSid `
            -ExpectedTaskName $TaskName -AllowLegacyOnDemand
        $existingXml = [string](Export-ScheduledTask -TaskName $TaskName)
    }

    $registrationAttempted = $false
    try {
        $registrationAttempted = $true
        Register-ScheduledTask -TaskName $TaskName `
            -InputObject $Definition -Force | Out-Null
        $registered = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
        Assert-StockQuantHostBrokerTaskDefinition -Task $registered `
            -ExpectedPowerShellExecutable $ExpectedPowerShellExecutable `
            -ExpectedBrokerScript $ExpectedBrokerScript `
            -ExpectedWorkingDirectory $ExpectedWorkingDirectory `
            -ExpectedUserSid $ExpectedUserSid `
            -ExpectedTaskName $TaskName
        return [pscustomobject]@{
            TaskName = $TaskName
            Created = ($null -eq $existing)
            Updated = ($null -ne $existing)
            ExistingDefinitionPreserved = ($null -ne $existing)
        }
    } catch {
        $originalError = $_
        if ($registrationAttempted) {
            try {
                if ($null -eq $existingXml) {
                    $createdTask = Get-ScheduledTask -TaskName $TaskName `
                        -ErrorAction SilentlyContinue
                    if ($null -ne $createdTask) {
                        Unregister-ScheduledTask -TaskName $TaskName `
                            -Confirm:$false -ErrorAction Stop
                    }
                    if ($null -ne (Get-ScheduledTask -TaskName $TaskName `
                            -ErrorAction SilentlyContinue)) {
                        throw 'REMOVE_FAILED'
                    }
                } else {
                    Register-ScheduledTask -TaskName $TaskName `
                        -Xml $existingXml -Force | Out-Null
                    $restored = Get-ScheduledTask -TaskName $TaskName `
                        -ErrorAction Stop
                    Assert-StockQuantHostBrokerTaskDefinition `
                        -Task $restored `
                        -ExpectedPowerShellExecutable `
                            $ExpectedPowerShellExecutable `
                        -ExpectedBrokerScript $ExpectedBrokerScript `
                        -ExpectedWorkingDirectory $ExpectedWorkingDirectory `
                        -ExpectedUserSid $ExpectedUserSid `
                        -ExpectedTaskName $TaskName -AllowLegacyOnDemand
                    $restoredXml = [string](Export-ScheduledTask `
                        -TaskName $TaskName)
                    if ((Get-StockQuantCanonicalXml -Xml $restoredXml) -cne
                        (Get-StockQuantCanonicalXml -Xml $existingXml)) {
                        throw 'RESTORE_MISMATCH'
                    }
                }
            } catch {
                throw 'STOCK_QUANT_HOST_BROKER_TASK_ROLLBACK_FAILED'
            }
        }
        throw $originalError
    }
}

Export-ModuleMember -Function @(
    'New-StockQuantHostBrokerTaskDefinition',
    'Assert-StockQuantHostBrokerTaskDefinition',
    'Invoke-StockQuantHostBrokerTaskRegistrationTransaction'
)
