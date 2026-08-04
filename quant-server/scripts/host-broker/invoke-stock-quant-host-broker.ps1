[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        'CHECK_CREDENTIAL_STATUS',
        'RUN_FAKE_E2E',
        'RUN_DAY001',
        'READ_SANITIZED_RESULT'
    )]
    [string] $Operation,

    [Parameter(Mandatory = $true)]
    [string] $AuthorizationFile,

    [string] $ArtifactPath,

    [string] $SourceRequestId = 'NONE',

    [ValidateRange(5, 2700)]
    [int] $TimeoutSeconds = 2700
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot `
    'StockQuantHostBroker.Protocol.psm1') -Force

$paths = Initialize-StockQuantHostBrokerDirectories
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$integrationBranch = 'feature/1.4.0-agent-team'
$expectedPowerShell = Join-Path $env:SystemRoot `
    'System32\WindowsPowerShell\v1.0\powershell.exe'
$expectedArguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass ' +
    '-File "' + $paths.BrokerScript + '"'

if ($identity -notmatch '(?i)CodexSandbox') {
    throw 'STOCK_QUANT_HOST_BROKER_CODEX_SANDBOX_REQUIRED'
}
if ([string]::IsNullOrWhiteSpace($ArtifactPath)) {
    $ArtifactPath = Join-Path $paths.RepositoryRoot `
        'quant-server\target\quant-server-1.3.1-reduced-research-day001-runner.jar'
}
if ($Operation -eq 'READ_SANITIZED_RESULT') {
    if ($SourceRequestId -notmatch
            '^SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$') {
        throw 'STOCK_QUANT_HOST_BROKER_SOURCE_REQUEST_INVALID'
    }
} elseif ($SourceRequestId -ne 'NONE') {
    throw 'STOCK_QUANT_HOST_BROKER_SOURCE_REQUEST_INVALID'
}

Push-Location $paths.RepositoryRoot
try {
    git fetch --quiet origin $integrationBranch
    if ($LASTEXITCODE -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_GIT_FETCH_FAILED'
    }
    $branch = (git branch --show-current).Trim()
    $head = (git rev-parse HEAD).Trim()
    $remote = (git rev-parse "refs/remotes/origin/$integrationBranch").Trim()
    $divergence = (git rev-list --left-right --count `
        "$integrationBranch...origin/$integrationBranch").Trim() -split '\s+'
    $unexpected = @(git status --porcelain=v1 --untracked-files=normal |
        Where-Object { $_ -and $_ -notmatch '^\?\? \.ai(?:/|$)' })
    if ($branch -ne $integrationBranch -or $head -ne $remote -or
        $divergence.Count -ne 2 -or $divergence[0] -ne '0' -or
        $divergence[1] -ne '0' -or $unexpected.Count -ne 0 -or
        @(git diff --cached --name-only).Count -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_GIT_BASELINE_INVALID'
    }

    $artifact = Assert-StockQuantPathInside -Path $ArtifactPath `
        -Root $paths.TargetRoot `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_JAR_PATH_INVALID' `
        -MustExist -PathType Leaf
    $authorization = Assert-StockQuantPathInside -Path $AuthorizationFile `
        -Root $paths.TargetRoot `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_PATH_INVALID' `
        -MustExist -PathType Leaf
    $artifactHash = ((Get-FileHash -LiteralPath $artifact `
        -Algorithm SHA256).Hash).ToLowerInvariant()
    $requestId = New-StockQuantHostBrokerRequestId
    $createdAt = [DateTimeOffset]::UtcNow
    $expiresAt = $createdAt.AddMinutes(10)
    $requestValues = [ordered]@{
        'schema.version' = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
        'request.id' = $requestId
        'operation' = $Operation
        'git.commit' = $head
        'jar.path' = $artifact
        'jar.sha256' = $artifactHash
        'authorization.file' = $authorization
        'day001.mode' = 'IDEMPOTENCY_VERIFICATION'
        'security.symbol' = '600000'
        'security.exchange' = 'SSE'
        'trade.date' = '2025-01-03'
        'database.host' = '127.0.0.1'
        'database.port' = '38432'
        'database.name' = 'stock_quant_research'
        'database.user' = 'stock_quant_research'
        'schema.name' = 'tushare_research'
        'provider' = 'TUSHARE'
        'provider.endpoints' = 'daily,adj_factor,trade_cal'
        'endpoint.daily.requests' = '1'
        'endpoint.adj_factor.requests' = '1'
        'endpoint.trade_cal.requests' = '1'
        'maximum.provider.requests' = '3'
        'retry.budget' = '0'
        'redirects' = 'NEVER'
        'created.at' = $createdAt.ToString('o')
        'expires.at' = $expiresAt.ToString('o')
        'execution.source' = 'REDUCED_RESEARCH_MANUAL_DAY001'
        'no.retry' = 'true'
        'source.request.id' = $SourceRequestId
    }
    $requestFile = Write-StockQuantHostBrokerRequest -Values $requestValues

    $task = Get-ScheduledTask -TaskName $paths.TaskName -ErrorAction Stop
    if ($task.Actions.Count -ne 1 -or
        -not ([string]$task.Actions[0].Execute).Equals(
            $expectedPowerShell, [StringComparison]::OrdinalIgnoreCase) -or
        [string]$task.Actions[0].Arguments -cne $expectedArguments -or
        $task.Triggers.Count -ne 0 -or
        [string]$task.Principal.LogonType -ne 'Interactive' -or
        [string]$task.Principal.UserId -match '(?i)CodexSandbox') {
        throw 'STOCK_QUANT_HOST_BROKER_TASK_DEFINITION_INVALID'
    }

    & schtasks.exe /Run /TN 'StockQuantLocalBroker' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'STOCK_QUANT_HOST_BROKER_TRIGGER_FAILED'
    }

    $resultPath = Join-Path $paths.Results "$requestId.result.json"
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
        if ([DateTimeOffset]::UtcNow -ge $deadline) {
            throw 'STOCK_QUANT_HOST_BROKER_RESULT_TIMEOUT'
        }
        Start-Sleep -Milliseconds 250
    }
    $result = Get-Content -LiteralPath $resultPath -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($result.schemaVersion -ne 'STOCK_QUANT_HOST_BROKER_RESULT_V1' -or
        $result.requestId -ne $requestId -or
        $result.operation -ne $Operation -or
        $result.status -notin @('SUCCEEDED', 'FAILED', 'REJECTED')) {
        throw 'STOCK_QUANT_HOST_BROKER_RESULT_INVALID'
    }
    Write-Output "STOCK_QUANT_HOST_BROKER_REQUEST_ID=$requestId"
    Write-Output "STOCK_QUANT_HOST_BROKER_REQUEST=$requestFile"
    Write-Output "STOCK_QUANT_HOST_BROKER_RESULT=$resultPath"
    Write-Output "STOCK_QUANT_HOST_BROKER_STATUS=$($result.status)"
    Write-Output "STOCK_QUANT_HOST_BROKER_STAGE=$($result.stage)"
    Write-Output "STOCK_QUANT_HOST_BROKER_REASON=$($result.reason)"
    if ($result.status -ne 'SUCCEEDED') { exit 20 }
    exit 0
} finally {
    Pop-Location
}
