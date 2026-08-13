[CmdletBinding()]
param(
    [ValidateSet('Start', 'Status', 'Stop', 'Backup')]
    [string] $Action = 'Start',
    [switch] $NoBrowser,
    [ValidateRange(10, 180)]
    [int] $TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$artifact = Join-Path $repoRoot `
    'quant-server\target\quant-server-1.3.1-research-production.jar'
$proof = "$artifact.f1f-b2-proof.properties"
$invokeBroker = Join-Path $PSScriptRoot `
    'host-broker\invoke-stock-quant-host-broker.ps1'

function Get-SystemHealth {
    Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/system/health' `
        -Method Get -TimeoutSec 10
}

Push-Location $repoRoot
try {
    if ($Action -eq 'Backup') {
        $health = Get-SystemHealth
        if (-not $health.success) { throw 'M6_SYSTEM_HEALTH_BLOCKED' }
        $result = Invoke-RestMethod `
            -Uri 'http://127.0.0.1:8080/api/system/backups' `
            -Method Post -TimeoutSec 120
        if (-not $result.success) { throw 'M6_BACKUP_FAILED' }
        Write-Output "STOCK_QUANT_BACKUP_PATH=$($result.data.archivePath)"
        Write-Output "STOCK_QUANT_BACKUP_SHA256=$($result.data.archiveSha256)"
        Write-Output 'STOCK_QUANT_BACKUP_STATUS=SUCCEEDED'
        exit 0
    }
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
        -not (Test-Path -LiteralPath $proof -PathType Leaf)) {
        throw 'M6_FORMAL_ARTIFACT_MISSING'
    }
    if ($Action -eq 'Start') {
        $oldPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $java = @(& java -version 2>&1 | ForEach-Object { [string]$_ })
            $javaExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $oldPreference
        }
        if ($javaExitCode -ne 0 -or ($java -join "`n") -notmatch
                'version "17(?:\.|\")') {
            throw 'M6_JAVA_17_REQUIRED'
        }
        $database = Get-NetTCPConnection -LocalAddress 127.0.0.1 `
            -LocalPort 38432 -State Listen -ErrorAction SilentlyContinue
        if ($null -eq $database) { throw 'M6_DATABASE_NOT_LISTENING' }
    }
    $operation = switch ($Action) {
        'Start' { 'START_RESEARCH_PRODUCTION' }
        'Stop' { 'STOP_RESEARCH_PRODUCTION' }
        'Status' { 'CHECK_RESEARCH_PRODUCTION_STATUS' }
    }
    $output = @(& $invokeBroker -Operation $operation `
        -ArtifactPath $artifact -TimeoutSeconds $TimeoutSeconds 2>&1 |
        ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) {
        throw 'M6_HOST_BROKER_OPERATION_FAILED'
    }
    if ($Action -eq 'Stop') {
        Write-Output 'STOCK_QUANT_PRODUCTION_STATUS=STOPPED'
        exit 0
    }
    $health = Get-SystemHealth
    if (-not $health.success -or $health.data.status -eq 'BLOCKED' -or
        $health.data.gitCommit -ne (git rev-parse HEAD).Trim()) {
        throw 'M6_SYSTEM_HEALTH_BLOCKED'
    }
    Write-Output "STOCK_QUANT_PRODUCTION_HEAD=$($health.data.gitCommit)"
    Write-Output "STOCK_QUANT_PRODUCTION_HEALTH=$($health.data.status)"
    Write-Output "STOCK_QUANT_SCHEDULER_STATE=$($health.data.scheduler.state)"
    Write-Output "STOCK_QUANT_NEXT_SHADOW=$($health.data.scheduler.nextPlannedAt)"
    Write-Output 'STOCK_QUANT_REAL_TRADING=false'
    if ($Action -eq 'Start' -and -not $NoBrowser) {
        Start-Process 'http://127.0.0.1:8080/'
    }
    Write-Output 'STOCK_QUANT_PRODUCTION_STATUS=RUNNING'
} finally {
    Pop-Location
}
