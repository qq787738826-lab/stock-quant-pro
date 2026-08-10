[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedCommit
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path -LiteralPath (
    Join-Path $PSScriptRoot '..\..\..')).Path
$installer = Join-Path $PSScriptRoot `
    'install-stock-quant-host-broker.ps1'
$hostSmoke = Join-Path $PSScriptRoot `
    'test-stock-quant-host-broker-host-smoke.ps1'
$taskName = 'StockQuantLocalBroker'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$originalPath = $env:PATH
$passed = 0

function Get-TaskSnapshot {
    $task = Get-ScheduledTask -TaskName $taskName `
        -ErrorAction SilentlyContinue
    if ($null -eq $task) { return 'ABSENT' }
    return [string](Export-ScheduledTask -TaskName $taskName)
}

if ($identity -match '(?i)CodexSandbox') {
    throw 'STOCK_QUANT_HOST_BROKER_REAL_USER_REQUIRED'
}
if (-not (Test-Path -LiteralPath $installer -PathType Leaf) -or
    -not (Test-Path -LiteralPath $hostSmoke -PathType Leaf)) {
    throw 'STOCK_QUANT_HOST_BROKER_NO_CODEX_TEST_PREREQUISITE_MISSING'
}

Import-Module ScheduledTasks -ErrorAction Stop
$taskBefore = Get-TaskSnapshot
$gitExecutable = (Get-Command git.exe -ErrorAction Stop).Source
$minimalPath = @(
    [IO.Path]::GetDirectoryName($gitExecutable)
    (Join-Path $env:SystemRoot 'System32')
    $env:SystemRoot
    (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0')
) | Select-Object -Unique

try {
    $env:PATH = $minimalPath -join ';'
    if ($null -ne (Get-Command codex -ErrorAction SilentlyContinue)) {
        throw 'STOCK_QUANT_HOST_BROKER_CODEX_CLI_STILL_VISIBLE'
    }
    $passed++

    $preflight = @(& $installer -WhatIf 2>&1 |
        ForEach-Object { [string]$_ })
    foreach ($expected in @(
            "STOCK_QUANT_HOST_BROKER_TASK=$taskName",
            "STOCK_QUANT_HOST_BROKER_ACCOUNT=$identity",
            'STOCK_QUANT_HOST_BROKER_LOGON_TYPE=Interactive',
            'STOCK_QUANT_HOST_BROKER_RUN_LEVEL=Limited',
            'STOCK_QUANT_HOST_BROKER_TRIGGERS=2',
            'STOCK_QUANT_HOST_BROKER_TRIGGER_1=AT_LOGON_CURRENT_USER',
            'STOCK_QUANT_HOST_BROKER_TRIGGER_2=WATCHDOG_EVERY_1_MINUTE',
            'STOCK_QUANT_HOST_BROKER_WATCHDOG_INTERVAL=PT1M',
            'STOCK_QUANT_HOST_BROKER_MULTIPLE_INSTANCES=IgnoreNew',
            'STOCK_QUANT_HOST_BROKER_AUTOSTART=true',
            'STOCK_QUANT_HOST_BROKER_PROVIDER_AUTOSTART=false',
            'StockQuant/ResearchDbPassword=PRESENT',
            'StockQuant/TushareToken=PRESENT',
            'STOCK_QUANT_HOST_BROKER_CREDENTIALS_READY=true',
            'STOCK_QUANT_HOST_BROKER_CODEX_CLI_REQUIRED=false',
            'STOCK_QUANT_HOST_BROKER_INSTALL_PREFLIGHT=PASS',
            'STOCK_QUANT_HOST_BROKER_INSTALLED=false')) {
        if ($preflight -notcontains $expected) {
            throw 'STOCK_QUANT_HOST_BROKER_NO_CODEX_PREFLIGHT_INVALID'
        }
    }
    $passed++

    $fixedScript = (Resolve-Path -LiteralPath (
        Join-Path $PSScriptRoot 'stock-quant-host-broker.ps1')).Path
    if ($preflight -notcontains `
            "STOCK_QUANT_HOST_BROKER_FIXED_SCRIPT=$fixedScript") {
        throw 'STOCK_QUANT_HOST_BROKER_FIXED_SCRIPT_INVALID'
    }
    $passed++

    $hostOutput = @(& $hostSmoke -ExpectedCommit $ExpectedCommit 2>&1 |
        ForEach-Object { [string]$_ })
    foreach ($expected in @(
            'STOCK_QUANT_HOST_BROKER_HOST_CREDENTIAL_STATUS=PASS',
            'STOCK_QUANT_HOST_BROKER_RESIDENT_AUTO_CLAIM=PASS',
            'STOCK_QUANT_HOST_BROKER_IDLE_CREDENTIAL_READS=0',
            'STOCK_QUANT_HOST_BROKER_IDLE_PROVIDER_CALLS=0',
            "STOCK_QUANT_HOST_BROKER_HOST_ACCOUNT=$identity",
            'STOCK_QUANT_HOST_BROKER_CODEX_CLI_REQUIRED=false',
            'STOCK_QUANT_HOST_BROKER_HOST_SMOKE_PROVIDER_CALLS=0',
            'STOCK_QUANT_HOST_BROKER_HOST_SMOKE_PERMANENT_DATABASE_WRITES=0')) {
        if ($hostOutput -notcontains $expected) {
            throw 'STOCK_QUANT_HOST_BROKER_NO_CODEX_HOST_SMOKE_INVALID'
        }
    }
    $passed++

    $safeOutput = ($preflight + $hostOutput) -join "`n"
    if ($safeOutput -match '(?i)(credentialblob|jdbc\.password|' +
            'provider\.token|secret\.value|token\.value)') {
        throw 'STOCK_QUANT_HOST_BROKER_NO_CODEX_OUTPUT_NOT_SANITIZED'
    }
    $passed++
} finally {
    $env:PATH = $originalPath
}

$taskAfter = Get-TaskSnapshot
if ($taskAfter -cne $taskBefore) {
    throw 'STOCK_QUANT_HOST_BROKER_WHATIF_CHANGED_TASK'
}
$passed++

Write-Output "STOCK_QUANT_HOST_BROKER_NO_CODEX_TESTS=$passed/0/0/0"
Write-Output 'STOCK_QUANT_HOST_BROKER_NO_CODEX_PROVIDER_CALLS=0'
Write-Output 'STOCK_QUANT_HOST_BROKER_NO_CODEX_PERMANENT_DATABASE_WRITES=0'
if ($passed -ne 6) {
    throw 'STOCK_QUANT_HOST_BROKER_NO_CODEX_TEST_COUNT_INVALID'
}
