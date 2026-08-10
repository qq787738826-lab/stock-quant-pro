Set-StrictMode -Version Latest

Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1')

function Start-StockQuantTestResidentBroker {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[0-9a-f]{40}$')]
        [string] $ExpectedCommit,
        [Parameter(Mandatory = $true)]
        [string] $LogDirectory
    )
    $paths = Initialize-StockQuantHostBrokerDirectories
    $logRoot = Assert-StockQuantPathInside -Path $LogDirectory `
        -Root $paths.TargetRoot `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_TEST_LOG_PATH_INVALID' `
        -MustExist -PathType Container
    if (Test-Path -LiteralPath $paths.Heartbeat -PathType Leaf) {
        try {
            $existing = Get-Content -LiteralPath $paths.Heartbeat `
                -Raw -Encoding UTF8 | ConvertFrom-Json
            $last = [DateTimeOffset]::Parse([string]$existing.lastHeartbeat)
            if ([DateTimeOffset]::UtcNow - $last.ToUniversalTime() -lt
                    [TimeSpan]::FromSeconds(6) -and
                $null -ne (Get-Process -Id ([int]$existing.processId) `
                    -ErrorAction SilentlyContinue)) {
                throw 'STOCK_QUANT_HOST_BROKER_TEST_RESIDENT_ALREADY_RUNNING'
            }
        } catch {
            if ($_.Exception.Message -ceq
                    'STOCK_QUANT_HOST_BROKER_TEST_RESIDENT_ALREADY_RUNNING') {
                throw
            }
        }
        Remove-Item -LiteralPath $paths.Heartbeat -Force
    }
    $stdout = Join-Path $logRoot 'resident.stdout.log'
    $stderr = Join-Path $logRoot 'resident.stderr.log'
    $powershell = Join-Path $env:SystemRoot `
        'System32\WindowsPowerShell\v1.0\powershell.exe'
    $arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass ' +
        '-File "' + $paths.BrokerScript + '"'
    $process = Start-Process -FilePath $powershell -ArgumentList $arguments `
        -WorkingDirectory $paths.RepositoryRoot -WindowStyle Hidden `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
        -PassThru
    [void]$process.Handle
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(15)
    try {
        while ([DateTimeOffset]::UtcNow -lt $deadline) {
            if ($process.HasExited) {
                throw 'STOCK_QUANT_HOST_BROKER_TEST_RESIDENT_EXITED'
            }
            try {
                $heartbeat = Read-StockQuantHostBrokerHeartbeat `
                    -ExpectedGitCommit $ExpectedCommit
                if ([int]$heartbeat.processId -eq $process.Id -and
                    [string]$heartbeat.state -eq 'IDLE') {
                    return [pscustomobject]@{
                        Process = $process
                        ProcessId = $process.Id
                        StandardOutput = $stdout
                        StandardError = $stderr
                    }
                }
            } catch {
                if ($_.Exception.Message -cne 'HOST_BROKER_NOT_RUNNING') {
                    throw
                }
            }
            Start-Sleep -Milliseconds 100
        }
        throw 'STOCK_QUANT_HOST_BROKER_TEST_HEARTBEAT_TIMEOUT'
    } catch {
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            $process.WaitForExit()
        }
        throw
    }
}

function Stop-StockQuantTestResidentBroker {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object] $Resident
    )
    $processId = [int]$Resident.ProcessId
    if ($processId -lt 1) {
        throw 'STOCK_QUANT_HOST_BROKER_TEST_PROCESS_INVALID'
    }
    $process = $Resident.Process
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $processId -Force -ErrorAction Stop
        $process.WaitForExit()
    }
    $paths = Get-StockQuantHostBrokerPaths
    if (Test-Path -LiteralPath $paths.Heartbeat -PathType Leaf) {
        try {
            $heartbeat = Get-Content -LiteralPath $paths.Heartbeat `
                -Raw -Encoding UTF8 | ConvertFrom-Json
            if ([int]$heartbeat.processId -eq $processId) {
                Remove-Item -LiteralPath $paths.Heartbeat -Force
            }
        } catch {
            throw 'STOCK_QUANT_HOST_BROKER_TEST_HEARTBEAT_CLEANUP_INVALID'
        }
    }
}

Export-ModuleMember -Function @(
    'Start-StockQuantTestResidentBroker'
    'Stop-StockQuantTestResidentBroker'
)
