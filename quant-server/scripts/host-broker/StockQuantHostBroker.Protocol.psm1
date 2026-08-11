Set-StrictMode -Version Latest

$script:ProtocolVersion = 'STOCK_QUANT_HOST_BROKER_REQUEST_V1'
$script:ResultVersion = 'STOCK_QUANT_HOST_BROKER_RESULT_V1'
$script:HeartbeatVersion = 'STOCK_QUANT_HOST_BROKER_HEARTBEAT_V1'
$script:BrokerVersion = 'STOCK_QUANT_HOST_BROKER_RESIDENT_V1'
$script:TaskName = 'StockQuantLocalBroker'
$script:AllowedOperations = @(
    'CHECK_CREDENTIAL_STATUS'
    'DIAGNOSE_TUSHARE_CREDENTIAL'
    'RUN_FAKE_E2E'
    'RUN_DAY001'
    'RUN_M1_RESEARCH_DATA'
    'VERIFY_M1_TUSHARE_TOKEN'
    'RUN_M2_STRATEGY_RESEARCH_SMOKE'
    'CHECK_BAILIAN_CREDENTIAL_STATUS'
    'RUN_M3_AGENT_RESEARCH_SMOKE'
    'READ_SANITIZED_RESULT'
)
$script:RequiredKeys = @(
    'schema.version'
    'request.id'
    'operation'
    'git.commit'
    'jar.path'
    'jar.sha256'
    'authorization.file'
    'day001.mode'
    'security.symbol'
    'security.exchange'
    'trade.date'
    'database.host'
    'database.port'
    'database.name'
    'database.user'
    'schema.name'
    'provider'
    'provider.endpoints'
    'endpoint.daily.requests'
    'endpoint.adj_factor.requests'
    'endpoint.trade_cal.requests'
    'maximum.provider.requests'
    'retry.budget'
    'redirects'
    'created.at'
    'expires.at'
    'execution.source'
    'no.retry'
    'source.request.id'
)
$script:AuthorizationKeys = @(
    'authorization.status'
    'authorization.version'
    'run.id'
    'git.commit'
    'artifact.sha256'
    'build.proof.path'
    'provider'
    'security.symbol'
    'security.exchange'
    'trade.date'
    'day001.mode'
    'endpoints'
    'endpoint.daily.requests'
    'endpoint.adj_factor.requests'
    'endpoint.trade_cal.requests'
    'maximum.provider.requests'
    'retry.budget'
    'redirects'
    'database.host'
    'database.port'
    'database.name'
    'database.user'
    'database.ssl.mode'
    'schema.name'
    'issued.at'
    'expires.at'
    'purpose'
    'execution.source'
    'user.approval.reference'
)
$script:M1RequiredKeys = @(
    'schema.version'
    'request.id'
    'operation'
    'git.commit'
    'jar.path'
    'jar.sha256'
    'authorization.file'
    'm1.mode'
    'securities'
    'range.start'
    'range.end'
    'anchor.trade.date'
    'database.host'
    'database.port'
    'database.name'
    'database.user'
    'schema.name'
    'provider'
    'provider.endpoints'
    'endpoint.daily.requests'
    'endpoint.adj_factor.requests'
    'endpoint.trade_cal.requests'
    'maximum.provider.requests'
    'retry.budget'
    'redirects'
    'provider.historical.baseline'
    'provider.stage.limit'
    'provider.cumulative.limit'
    'provider.stage.calls.before'
    'created.at'
    'expires.at'
    'execution.source'
    'no.retry'
    'source.request.id'
)
$script:M1AuthorizationKeys = @(
    'authorization.status'
    'authorization.version'
    'run.id'
    'git.commit'
    'artifact.sha256'
    'build.proof.path'
    'provider'
    'securities'
    'range.start'
    'range.end'
    'anchor.trade.date'
    'mode'
    'endpoints'
    'endpoint.daily.requests'
    'endpoint.adj_factor.requests'
    'endpoint.trade_cal.requests'
    'maximum.provider.requests'
    'retry.budget'
    'redirects'
    'provider.historical.baseline'
    'provider.stage.limit'
    'provider.cumulative.limit'
    'provider.stage.calls.before'
    'database.host'
    'database.port'
    'database.name'
    'database.user'
    'database.ssl.mode'
    'schema.name'
    'issued.at'
    'expires.at'
    'purpose'
    'execution.source'
    'user.approval.reference'
)

$script:M1TokenVerificationRequiredKeys = @(
    'schema.version'
    'request.id'
    'operation'
    'git.commit'
    'jar.path'
    'jar.sha256'
    'authorization.file'
    'security.symbol'
    'security.exchange'
    'trade.date'
    'provider'
    'provider.endpoints'
    'endpoint.daily.requests'
    'maximum.provider.requests'
    'retry.budget'
    'redirects'
    'provider.historical.baseline'
    'provider.stage.limit'
    'provider.cumulative.limit'
    'provider.stage.calls.before'
    'created.at'
    'expires.at'
    'execution.source'
    'no.retry'
    'source.request.id'
)

$script:M1TokenVerificationAuthorizationKeys = @(
    'authorization.status'
    'authorization.version'
    'verification.id'
    'git.commit'
    'artifact.sha256'
    'build.proof.path'
    'provider'
    'endpoint'
    'security.symbol'
    'security.exchange'
    'trade.date'
    'endpoint.daily.requests'
    'maximum.provider.requests'
    'retry.budget'
    'redirects'
    'provider.historical.baseline'
    'provider.stage.limit'
    'provider.cumulative.limit'
    'provider.stage.calls.before'
    'issued.at'
    'expires.at'
    'purpose'
    'execution.source'
    'user.approval.reference'
)

$script:M2RequiredKeys = @(
    'schema.version'
    'request.id'
    'operation'
    'git.commit'
    'jar.path'
    'jar.sha256'
    'authorization.file'
    'm2.dataset.contract'
    'm2.strategy.engine'
    'm2.backtest.engine'
    'm2.research.api'
    'securities'
    'range.start'
    'range.end'
    'anchor.trade.date'
    'database.host'
    'database.port'
    'database.name'
    'database.user'
    'schema.name'
    'database.read.only'
    'provider'
    'provider.endpoints'
    'maximum.provider.requests'
    'retry.budget'
    'redirects'
    'created.at'
    'expires.at'
    'execution.source'
    'no.retry'
    'source.request.id'
)

$script:M3BailianCredentialRequiredKeys = @(
    'schema.version'
    'request.id'
    'operation'
    'git.commit'
    'jar.path'
    'jar.sha256'
    'authorization.file'
    'provider'
    'model'
    'provider.endpoint'
    'maximum.model.calls'
    'maximum.cost.cny'
    'retry.budget'
    'redirects'
    'created.at'
    'expires.at'
    'execution.source'
    'no.retry'
    'source.request.id'
)

$script:M3RequiredKeys = @(
    'schema.version'
    'request.id'
    'operation'
    'git.commit'
    'jar.path'
    'jar.sha256'
    'authorization.file'
    'm3.dataset.contract'
    'm3.strategy.engine'
    'm3.backtest.engine'
    'm3.research.api'
    'm3.agent.runtime'
    'm3.agent.team'
    'm3.tool.gateway'
    'm3.agent.eval'
    'm3.research.report'
    'securities'
    'range.start'
    'range.end'
    'anchor.trade.date'
    'database.host'
    'database.port'
    'database.name'
    'database.user'
    'schema.name'
    'database.read.only'
    'provider'
    'model'
    'provider.endpoint'
    'maximum.model.calls'
    'maximum.output.tokens.per.call'
    'maximum.cost.cny'
    'retry.budget'
    'redirects'
    'user.approval.reference'
    'created.at'
    'expires.at'
    'execution.source'
    'no.retry'
    'source.request.id'
)

function Get-StockQuantHostBrokerPaths {
    $repoRoot = [IO.Path]::GetFullPath(
        (Join-Path $PSScriptRoot '..\..\..')).TrimEnd('\', '/')
    $base = Join-Path $repoRoot `
        'quant-server\target\stock-quant-host-broker'
    [pscustomobject]@{
        RepositoryRoot = $repoRoot
        TargetRoot = Join-Path $repoRoot 'quant-server\target'
        Base = $base
        Requests = Join-Path $base 'requests'
        Results = Join-Path $base 'results'
        Heartbeat = Join-Path $base 'heartbeat.json'
        TaskName = $script:TaskName
        BrokerScript = Join-Path $PSScriptRoot 'stock-quant-host-broker.ps1'
    }
}

function Initialize-StockQuantHostBrokerDirectories {
    $paths = Get-StockQuantHostBrokerPaths
    foreach ($directory in @($paths.Base, $paths.Requests, $paths.Results)) {
        if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
            New-Item -ItemType Directory -Path $directory | Out-Null
        }
    }
    return $paths
}

function ConvertTo-StockQuantSafeCode {
    param(
        [AllowNull()]
        [object] $ErrorValue,
        [string] $Fallback = 'STOCK_QUANT_HOST_BROKER_FAILED'
    )
    $current = $ErrorValue
    if ($current -is [System.Management.Automation.ErrorRecord]) {
        $current = $current.Exception
    }
    while ($null -ne $current) {
        $message = [string]$current.Message
        if ($message -match '^[A-Z][A-Z0-9_]{7,127}$') {
            return $message
        }
        $current = $current.InnerException
    }
    return $Fallback
}

function Assert-StockQuantPathInside {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [Parameter(Mandatory = $true)]
        [string] $Root,
        [string] $FailureCode = 'STOCK_QUANT_HOST_BROKER_PATH_INVALID',
        [switch] $MustExist,
        [ValidateSet('Any', 'Leaf', 'Container')]
        [string] $PathType = 'Any'
    )
    if ([string]::IsNullOrWhiteSpace($Path) -or
        -not [IO.Path]::IsPathRooted($Path)) {
        throw $FailureCode
    }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $prefix = $rootFull + [IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith(
            $prefix, [StringComparison]::OrdinalIgnoreCase) -or
        ($full -split '[\\/]') -contains '.ai') {
        throw $FailureCode
    }
    if ($MustExist) {
        $exists = switch ($PathType) {
            'Leaf' { Test-Path -LiteralPath $full -PathType Leaf }
            'Container' { Test-Path -LiteralPath $full -PathType Container }
            default { Test-Path -LiteralPath $full }
        }
        if (-not $exists) { throw $FailureCode }
    }
    return $full
}

function Read-StrictStockQuantProperties {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 1 -or $bytes.Length -gt 16384 -or
        ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and
            $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_ENCODING_INVALID'
    }
    $strictUtf8 = [Text.UTF8Encoding]::new($false, $true)
    try {
        $content = $strictUtf8.GetString($bytes)
    } catch {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_ENCODING_INVALID'
    }
    if ($content.IndexOf([char]0) -ge 0 -or
        $content -match '(?i)(database\.password|jdbc\.password|provider\.token|token\.sha|credentialblob)') {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_SECRET_FIELD_FORBIDDEN'
    }
    $values = [ordered]@{}
    foreach ($line in ($content -split "`r?`n")) {
        if ([string]::IsNullOrEmpty($line)) { continue }
        if ($line.StartsWith('#') -or $line.StartsWith(';')) {
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_LINE_INVALID'
        }
        $separator = $line.IndexOf('=')
        if ($separator -le 0 -or $separator -eq $line.Length - 1 -or
            $line.IndexOf('=', $separator + 1) -ge 0) {
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_LINE_INVALID'
        }
        $key = $line.Substring(0, $separator)
        $value = $line.Substring($separator + 1)
        if ($key -notmatch '^[a-z][a-z0-9._]{1,63}$' -or
            $value -match '[\x00-\x1F\x7F]' -or
            $values.Contains($key)) {
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_LINE_INVALID'
        }
        $values[$key] = $value
    }
    return $values
}

function ConvertTo-StockQuantTimestamp {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Value
    )
    [DateTimeOffset] $parsed = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse(
            $Value,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind,
            [ref] $parsed) -or $parsed.Offset -ne [TimeSpan]::Zero) {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_TIME_INVALID'
    }
    return $parsed
}

function Assert-StockQuantAuthorizationNonSensitive {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [Parameter(Mandatory = $true)]
        [string] $ExpectedGitCommit,
        [Parameter(Mandatory = $true)]
        [string] $ExpectedArtifactHash,
        [Parameter(Mandatory = $true)]
        [string] $ExpectedBuildProof,
        [DateTimeOffset] $Now = [DateTimeOffset]::UtcNow
    )
    $content = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
    if ($content -match '(?im)^\s*(database\.password|jdbc\.password|provider\.token|token|password)\s*=') {
        throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_SECRET_FORBIDDEN'
    }
    $values = Read-StrictStockQuantProperties -Path $Path
    if ($values.Count -ne $script:AuthorizationKeys.Count) {
        throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_INVALID'
    }
    foreach ($key in $script:AuthorizationKeys) {
        if (-not $values.Contains($key) -or
            [string]::IsNullOrWhiteSpace([string]$values[$key])) {
            throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_INVALID'
        }
    }
    foreach ($key in $values.Keys) {
        if ($key -notin $script:AuthorizationKeys) {
            throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_INVALID'
        }
    }
    $status = [string]$values['authorization.status']
    if ($status -notin @('USER_APPROVED', 'E2E_DRY_RUN') -or
        $values['authorization.version'] -ne
            'REDUCED_RESEARCH_DAY001_AUTHORIZATION_V1' -or
        $values['run.id'] -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{7,95}$' -or
        $values['git.commit'] -cne $ExpectedGitCommit -or
        $values['artifact.sha256'] -cne $ExpectedArtifactHash -or
        $values['provider'] -ne 'TUSHARE' -or
        $values['security.symbol'] -ne '600000' -or
        $values['security.exchange'] -ne 'SSE' -or
        $values['trade.date'] -ne '2025-01-03' -or
        $values['day001.mode'] -ne 'IDEMPOTENCY_VERIFICATION' -or
        $values['endpoints'] -ne 'daily,adj_factor,trade_cal' -or
        $values['endpoint.daily.requests'] -ne '1' -or
        $values['endpoint.adj_factor.requests'] -ne '1' -or
        $values['endpoint.trade_cal.requests'] -ne '1' -or
        $values['maximum.provider.requests'] -ne '3' -or
        $values['retry.budget'] -ne '0' -or
        $values['redirects'] -ne 'NEVER' -or
        $values['database.host'] -ne '127.0.0.1' -or
        $values['database.name'] -ne 'stock_quant_research' -or
        $values['database.user'] -ne 'stock_quant_research' -or
        $values['database.ssl.mode'] -ne 'DISABLE_LOCAL_ONLY' -or
        $values['schema.name'] -ne 'tushare_research' -or
        $values['purpose'] -ne '3A_R3B_RR_DAY001' -or
        $values['execution.source'] -ne
            'REDUCED_RESEARCH_MANUAL_DAY001') {
        throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_INVALID'
    }
    $actualProof = ([IO.Path]::GetFullPath(
            $values['build.proof.path'])).TrimEnd('\', '/')
    $expectedProof = ([IO.Path]::GetFullPath(
            $ExpectedBuildProof)).TrimEnd('\', '/')
    if (-not $actualProof.Equals(
            $expectedProof, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_INVALID'
    }
    $issued = ConvertTo-StockQuantTimestamp $values['issued.at']
    $expires = ConvertTo-StockQuantTimestamp $values['expires.at']
    if ($expires -le $issued -or
        $expires - $issued -gt [TimeSpan]::FromMinutes(30) -or
        $Now -lt $issued.AddMinutes(-1) -or $Now -ge $expires) {
        throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_EXPIRED'
    }
    if ($status -eq 'USER_APPROVED') {
        if ($values['database.port'] -ne '38432' -or
            $values['user.approval.reference'] -eq
                'NOT_APPLICABLE_E2E_DRY_RUN') {
            throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_INVALID'
        }
    } elseif ($values['database.port'] -eq '38432' -or
        $values['user.approval.reference'] -ne
            'NOT_APPLICABLE_E2E_DRY_RUN') {
        throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_INVALID'
    }
    return $status
}

function Assert-StockQuantM1AuthorizationNonSensitive {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [string] $ExpectedGitCommit,
        [Parameter(Mandatory = $true)] [string] $ExpectedArtifactHash,
        [Parameter(Mandatory = $true)] [string] $ExpectedBuildProof,
        [DateTimeOffset] $Now = [DateTimeOffset]::UtcNow
    )
    $content = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
    if ($content -match '(?im)^\s*(database\.password|jdbc\.password|provider\.token|token|password)\s*=') {
        throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_SECRET_FORBIDDEN'
    }
    $values = Read-StrictStockQuantProperties -Path $Path
    if ($values.Count -ne $script:M1AuthorizationKeys.Count) {
        throw 'STOCK_QUANT_HOST_BROKER_M1_AUTHORIZATION_INVALID'
    }
    foreach ($key in $script:M1AuthorizationKeys) {
        if (-not $values.Contains($key) -or
            [string]::IsNullOrWhiteSpace([string]$values[$key])) {
            throw 'STOCK_QUANT_HOST_BROKER_M1_AUTHORIZATION_INVALID'
        }
    }
    foreach ($key in $values.Keys) {
        if ($key -notin $script:M1AuthorizationKeys) {
            throw 'STOCK_QUANT_HOST_BROKER_M1_AUTHORIZATION_INVALID'
        }
    }
    if ($values['authorization.status'] -ne 'USER_APPROVED' -or
        $values['authorization.version'] -ne
            'M1_RESEARCH_DATA_AUTHORIZATION_V1' -or
        $values['run.id'] -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{7,95}$' -or
        $values['git.commit'] -cne $ExpectedGitCommit -or
        $values['artifact.sha256'] -cne $ExpectedArtifactHash -or
        $values['provider'] -ne 'TUSHARE' -or
        $values['securities'] -ne '600000:SSE,000001:SZSE' -or
        $values['mode'] -notin @('CAPTURE', 'IDEMPOTENCY_VERIFICATION') -or
        $values['endpoints'] -ne 'daily,adj_factor,trade_cal' -or
        $values['endpoint.daily.requests'] -ne '2' -or
        $values['endpoint.adj_factor.requests'] -ne '2' -or
        $values['endpoint.trade_cal.requests'] -ne '2' -or
        $values['maximum.provider.requests'] -ne '6' -or
        $values['retry.budget'] -ne '0' -or
        $values['redirects'] -ne 'NEVER' -or
        $values['provider.historical.baseline'] -ne '34' -or
        $values['provider.stage.limit'] -ne '30' -or
        $values['provider.cumulative.limit'] -ne '64' -or
        $values['database.host'] -ne '127.0.0.1' -or
        $values['database.port'] -ne '38432' -or
        $values['database.name'] -ne 'stock_quant_research' -or
        $values['database.user'] -ne 'stock_quant_research' -or
        $values['database.ssl.mode'] -ne 'DISABLE_LOCAL_ONLY' -or
        $values['schema.name'] -ne 'tushare_research' -or
        $values['purpose'] -ne 'M1_RESEARCH_DATA_READY' -or
        $values['execution.source'] -ne 'M1_RESEARCH_DATA_MANUAL' -or
        $values['user.approval.reference'] -eq
            'NOT_APPLICABLE_E2E_DRY_RUN') {
        throw 'STOCK_QUANT_HOST_BROKER_M1_AUTHORIZATION_INVALID'
    }
    [int] $callsBefore = -1
    if (-not [int]::TryParse(
            [string]$values['provider.stage.calls.before'],
            [Globalization.NumberStyles]::None,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$callsBefore) -or $callsBefore -lt 0 -or
        $callsBefore + 6 -gt 30) {
        throw 'STOCK_QUANT_HOST_BROKER_M1_PROVIDER_BUDGET_INVALID'
    }
    [DateTime] $rangeStart = [DateTime]::MinValue
    [DateTime] $rangeEnd = [DateTime]::MinValue
    [DateTime] $anchor = [DateTime]::MinValue
    $dateStyle = [Globalization.DateTimeStyles]::None
    $culture = [Globalization.CultureInfo]::InvariantCulture
    if (-not [DateTime]::TryParseExact(
            [string]$values['range.start'], 'yyyy-MM-dd', $culture,
            $dateStyle, [ref]$rangeStart) -or
        -not [DateTime]::TryParseExact(
            [string]$values['range.end'], 'yyyy-MM-dd', $culture,
            $dateStyle, [ref]$rangeEnd) -or
        -not [DateTime]::TryParseExact(
            [string]$values['anchor.trade.date'], 'yyyy-MM-dd', $culture,
            $dateStyle, [ref]$anchor) -or $rangeEnd -lt $rangeStart -or
        ($rangeEnd - $rangeStart).TotalDays + 1 -gt 31 -or
        $anchor -lt $rangeStart -or $anchor -gt $rangeEnd) {
        throw 'STOCK_QUANT_HOST_BROKER_M1_RANGE_INVALID'
    }
    $actualProof = ([IO.Path]::GetFullPath(
            $values['build.proof.path'])).TrimEnd('\', '/')
    $expectedProof = ([IO.Path]::GetFullPath(
            $ExpectedBuildProof)).TrimEnd('\', '/')
    if (-not $actualProof.Equals(
            $expectedProof, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'STOCK_QUANT_HOST_BROKER_M1_AUTHORIZATION_INVALID'
    }
    $issued = ConvertTo-StockQuantTimestamp $values['issued.at']
    $expires = ConvertTo-StockQuantTimestamp $values['expires.at']
    if ($expires -le $issued -or
        $expires - $issued -gt [TimeSpan]::FromMinutes(30) -or
        $Now -lt $issued.AddMinutes(-1) -or $Now -ge $expires) {
        throw 'STOCK_QUANT_HOST_BROKER_M1_AUTHORIZATION_EXPIRED'
    }
    return $values
}

function Assert-StockQuantM1TokenVerificationAuthorizationNonSensitive {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [string] $ExpectedGitCommit,
        [Parameter(Mandatory = $true)] [string] $ExpectedArtifactHash,
        [Parameter(Mandatory = $true)] [string] $ExpectedBuildProof,
        [DateTimeOffset] $Now = [DateTimeOffset]::UtcNow
    )
    $content = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
    if ($content -match '(?im)^\s*(database\.password|jdbc\.password|provider\.token|token|password)\s*=') {
        throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_SECRET_FORBIDDEN'
    }
    $values = Read-StrictStockQuantProperties -Path $Path
    if ($values.Count -ne
            $script:M1TokenVerificationAuthorizationKeys.Count) {
        throw 'STOCK_QUANT_HOST_BROKER_M1_TOKEN_AUTH_INVALID'
    }
    foreach ($key in $script:M1TokenVerificationAuthorizationKeys) {
        if (-not $values.Contains($key) -or
            [string]::IsNullOrWhiteSpace([string]$values[$key])) {
            throw 'STOCK_QUANT_HOST_BROKER_M1_TOKEN_AUTH_INVALID'
        }
    }
    foreach ($key in $values.Keys) {
        if ($key -notin $script:M1TokenVerificationAuthorizationKeys) {
            throw 'STOCK_QUANT_HOST_BROKER_M1_TOKEN_AUTH_INVALID'
        }
    }
    [int] $callsBefore = -1
    if ($values['authorization.status'] -ne 'USER_APPROVED' -or
        $values['authorization.version'] -ne
            'M1_TUSHARE_TOKEN_VERIFICATION_V1' -or
        $values['verification.id'] -notmatch '^M1TOKEN_[A-Z0-9_]{8,55}$' -or
        $values['git.commit'] -cne $ExpectedGitCommit -or
        $values['artifact.sha256'] -cne $ExpectedArtifactHash -or
        $values['provider'] -ne 'TUSHARE' -or
        $values['endpoint'] -ne 'daily' -or
        $values['security.symbol'] -ne '600000' -or
        $values['security.exchange'] -ne 'SSE' -or
        $values['trade.date'] -ne '2025-01-03' -or
        $values['endpoint.daily.requests'] -ne '1' -or
        $values['maximum.provider.requests'] -ne '1' -or
        $values['retry.budget'] -ne '0' -or
        $values['redirects'] -ne 'NEVER' -or
        $values['provider.historical.baseline'] -ne '34' -or
        $values['provider.stage.limit'] -ne '30' -or
        $values['provider.cumulative.limit'] -ne '64' -or
        -not [int]::TryParse(
            [string]$values['provider.stage.calls.before'],
            [Globalization.NumberStyles]::None,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$callsBefore) -or $callsBefore -lt 0 -or
        $callsBefore + 1 -gt 30 -or
        $values['purpose'] -ne
            'M1_RESEARCH_DATA_READY_TOKEN_VERIFICATION' -or
        $values['execution.source'] -ne
            'M1_TUSHARE_TOKEN_VERIFICATION_MANUAL' -or
        $values['user.approval.reference'] -ne
            'USER_APPROVED_M1_TOKEN_VERIFICATION') {
        throw 'STOCK_QUANT_HOST_BROKER_M1_TOKEN_AUTH_INVALID'
    }
    $actualProof = ([IO.Path]::GetFullPath(
            $values['build.proof.path'])).TrimEnd('\', '/')
    $expectedProof = ([IO.Path]::GetFullPath(
            $ExpectedBuildProof)).TrimEnd('\', '/')
    if (-not $actualProof.Equals(
            $expectedProof, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'STOCK_QUANT_HOST_BROKER_M1_TOKEN_AUTH_INVALID'
    }
    $issued = ConvertTo-StockQuantTimestamp $values['issued.at']
    $expires = ConvertTo-StockQuantTimestamp $values['expires.at']
    if ($expires -le $issued -or
        $expires - $issued -gt [TimeSpan]::FromMinutes(30) -or
        $Now -lt $issued.AddMinutes(-1) -or $Now -ge $expires) {
        throw 'STOCK_QUANT_HOST_BROKER_M1_TOKEN_AUTH_EXPIRED'
    }
    return $values
}

function Read-StockQuantHostBrokerRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [DateTimeOffset] $Now = [DateTimeOffset]::UtcNow
    )
    $paths = Get-StockQuantHostBrokerPaths
    $full = Assert-StockQuantPathInside -Path $Path -Root $paths.Requests `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_REQUEST_PATH_INVALID' `
        -MustExist -PathType Leaf
    if ([IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ne
        $paths.Requests.TrimEnd('\', '/') -or
        [IO.Path]::GetFileName($full) -notmatch
        '^SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}\.(request|processing)\.properties$') {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_PATH_INVALID'
    }
    $values = Read-StrictStockQuantProperties -Path $full
    $requestKeys = if ($values.Contains('operation')) {
        switch ([string]$values['operation']) {
            'RUN_M1_RESEARCH_DATA' { $script:M1RequiredKeys; break }
            'VERIFY_M1_TUSHARE_TOKEN' {
                $script:M1TokenVerificationRequiredKeys
                break
            }
            'RUN_M2_STRATEGY_RESEARCH_SMOKE' {
                $script:M2RequiredKeys
                break
            }
            'CHECK_BAILIAN_CREDENTIAL_STATUS' {
                $script:M3BailianCredentialRequiredKeys
                break
            }
            'RUN_M3_AGENT_RESEARCH_SMOKE' {
                $script:M3RequiredKeys
                break
            }
            default { $script:RequiredKeys }
        }
    } else { $script:RequiredKeys }
    if ($values.Count -ne $requestKeys.Count) {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'
    }
    foreach ($key in $requestKeys) {
        if (-not $values.Contains($key) -or
            [string]::IsNullOrWhiteSpace([string]$values[$key])) {
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'
        }
    }
    foreach ($key in $values.Keys) {
        if ($key -notin $requestKeys) {
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'
        }
    }
    if ($values['schema.version'] -ne $script:ProtocolVersion -or
        $values['request.id'] -notmatch
            '^SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$' -or
        $values['operation'] -notin $script:AllowedOperations -or
        $values['git.commit'] -notmatch '^[0-9a-f]{40}$' -or
        $values['jar.sha256'] -notmatch '^[0-9a-f]{64}$') {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'
    }
    $fileRequestId = ([IO.Path]::GetFileName($full) -split '\.')[0]
    if ($fileRequestId -cne $values['request.id']) {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_ID_MISMATCH'
    }
    if ($values['operation'] -eq 'RUN_M2_STRATEGY_RESEARCH_SMOKE') {
        if ($values['m2.dataset.contract'] -ne
                'M1_RESEARCH_DATASET_V1' -or
            $values['m2.strategy.engine'] -ne 'STRATEGY_ENGINE_V1' -or
            $values['m2.backtest.engine'] -ne 'BACKTEST_ENGINE_V1' -or
            $values['m2.research.api'] -ne 'STRATEGY_RESEARCH_API_V1' -or
            $values['securities'] -ne '600000:SSE,000001:SZSE' -or
            $values['range.start'] -ne '2025-01-02' -or
            $values['range.end'] -ne '2025-01-10' -or
            $values['anchor.trade.date'] -ne '2025-01-10' -or
            $values['database.host'] -ne '127.0.0.1' -or
            $values['database.port'] -ne '38432' -or
            $values['database.name'] -ne 'stock_quant_research' -or
            $values['database.user'] -ne 'stock_quant_research' -or
            $values['schema.name'] -ne 'tushare_research' -or
            $values['database.read.only'] -ne 'true' -or
            $values['provider'] -ne 'NONE' -or
            $values['provider.endpoints'] -ne 'NONE' -or
            $values['maximum.provider.requests'] -ne '0' -or
            $values['retry.budget'] -ne '0' -or
            $values['redirects'] -ne 'NEVER' -or
            $values['execution.source'] -ne
                'M2_STRATEGY_RESEARCH_READ_ONLY' -or
            $values['no.retry'] -ne 'true') {
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'
        }
    } elseif ($values['operation'] -eq
            'CHECK_BAILIAN_CREDENTIAL_STATUS') {
        if ($values['authorization.file'] -ne 'NONE' -or
            $values['provider'] -ne 'BAILIAN' -or
            $values['model'] -ne 'qwen3.7-plus' -or
            $values['provider.endpoint'] -ne 'NONE' -or
            $values['maximum.model.calls'] -ne '0' -or
            $values['maximum.cost.cny'] -ne '0.00' -or
            $values['retry.budget'] -ne '0' -or
            $values['redirects'] -ne 'NEVER' -or
            $values['execution.source'] -ne
                'M3_BAILIAN_CREDENTIAL_READABILITY_CHECK' -or
            $values['no.retry'] -ne 'true') {
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'
        }
    } elseif ($values['operation'] -eq
            'RUN_M3_AGENT_RESEARCH_SMOKE') {
        if ($values['authorization.file'] -ne 'NONE' -or
            $values['m3.dataset.contract'] -ne
                'M1_RESEARCH_DATASET_V1' -or
            $values['m3.strategy.engine'] -ne 'STRATEGY_ENGINE_V1' -or
            $values['m3.backtest.engine'] -ne 'BACKTEST_ENGINE_V1' -or
            $values['m3.research.api'] -ne
                'STRATEGY_RESEARCH_API_V1' -or
            $values['m3.agent.runtime'] -ne 'AGENT_RUNTIME_V1' -or
            $values['m3.agent.team'] -ne 'AGENT_RESEARCH_TEAM_V1' -or
            $values['m3.tool.gateway'] -ne 'AGENT_TOOL_GATEWAY_V1' -or
            $values['m3.agent.eval'] -ne 'AGENT_EVAL_V1' -or
            $values['m3.research.report'] -ne 'RESEARCH_REPORT_V1' -or
            $values['securities'] -ne '600000:SSE,000001:SZSE' -or
            $values['range.start'] -ne '2025-01-02' -or
            $values['range.end'] -ne '2025-01-10' -or
            $values['anchor.trade.date'] -ne '2025-01-10' -or
            $values['database.host'] -ne '127.0.0.1' -or
            $values['database.port'] -ne '38432' -or
            $values['database.name'] -ne 'stock_quant_research' -or
            $values['database.user'] -ne 'stock_quant_research' -or
            $values['schema.name'] -ne 'tushare_research' -or
            $values['database.read.only'] -ne 'true' -or
            $values['provider'] -ne 'BAILIAN' -or
            $values['model'] -ne 'qwen3.7-plus' -or
            $values['provider.endpoint'] -ne
                'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions' -or
            $values['maximum.model.calls'] -ne '13' -or
            $values['maximum.output.tokens.per.call'] -ne '600' -or
            $values['maximum.cost.cny'] -ne '5.00' -or
            $values['retry.budget'] -ne '0' -or
            $values['redirects'] -ne 'NEVER' -or
            $values['user.approval.reference'] -ne
                'USER_APPROVED_M3_BAILIAN_SMOKE_TRANCHE_2_CNY_5_00' -or
            $values['execution.source'] -ne
                'M3_AGENT_RESEARCH_REAL_LLM_SMOKE' -or
            $values['no.retry'] -ne 'true') {
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'
        }
    } elseif ($values['operation'] -eq 'RUN_M1_RESEARCH_DATA') {
        [int] $m1CallsBefore = -1
        if ($values['m1.mode'] -notin @(
                'CAPTURE', 'IDEMPOTENCY_VERIFICATION') -or
            $values['securities'] -ne '600000:SSE,000001:SZSE' -or
            $values['database.host'] -ne '127.0.0.1' -or
            $values['database.port'] -ne '38432' -or
            $values['database.name'] -ne 'stock_quant_research' -or
            $values['database.user'] -ne 'stock_quant_research' -or
            $values['schema.name'] -ne 'tushare_research' -or
            $values['provider'] -ne 'TUSHARE' -or
            $values['provider.endpoints'] -ne
                'daily,adj_factor,trade_cal' -or
            $values['endpoint.daily.requests'] -ne '2' -or
            $values['endpoint.adj_factor.requests'] -ne '2' -or
            $values['endpoint.trade_cal.requests'] -ne '2' -or
            $values['maximum.provider.requests'] -ne '6' -or
            $values['retry.budget'] -ne '0' -or
            $values['redirects'] -ne 'NEVER' -or
            $values['provider.historical.baseline'] -ne '34' -or
            $values['provider.stage.limit'] -ne '30' -or
            $values['provider.cumulative.limit'] -ne '64' -or
            -not [int]::TryParse(
                [string]$values['provider.stage.calls.before'],
                [Globalization.NumberStyles]::None,
                [Globalization.CultureInfo]::InvariantCulture,
                [ref]$m1CallsBefore) -or $m1CallsBefore -lt 0 -or
            $m1CallsBefore + 6 -gt 30 -or
            $values['execution.source'] -ne 'M1_RESEARCH_DATA_MANUAL' -or
            $values['no.retry'] -ne 'true') {
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'
        }
    } elseif ($values['operation'] -eq 'VERIFY_M1_TUSHARE_TOKEN') {
        [int] $verificationCallsBefore = -1
        if ($values['security.symbol'] -ne '600000' -or
            $values['security.exchange'] -ne 'SSE' -or
            $values['trade.date'] -ne '2025-01-03' -or
            $values['provider'] -ne 'TUSHARE' -or
            $values['provider.endpoints'] -ne 'daily' -or
            $values['endpoint.daily.requests'] -ne '1' -or
            $values['maximum.provider.requests'] -ne '1' -or
            $values['retry.budget'] -ne '0' -or
            $values['redirects'] -ne 'NEVER' -or
            $values['provider.historical.baseline'] -ne '34' -or
            $values['provider.stage.limit'] -ne '30' -or
            $values['provider.cumulative.limit'] -ne '64' -or
            -not [int]::TryParse(
                [string]$values['provider.stage.calls.before'],
                [Globalization.NumberStyles]::None,
                [Globalization.CultureInfo]::InvariantCulture,
                [ref]$verificationCallsBefore) -or
            $verificationCallsBefore -lt 0 -or
            $verificationCallsBefore + 1 -gt 30 -or
            $values['execution.source'] -ne
                'M1_TUSHARE_TOKEN_VERIFICATION_MANUAL' -or
            $values['no.retry'] -ne 'true') {
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'
        }
    } elseif ($values['day001.mode'] -ne 'IDEMPOTENCY_VERIFICATION' -or
        $values['security.symbol'] -ne '600000' -or
        $values['security.exchange'] -ne 'SSE' -or
        $values['trade.date'] -ne '2025-01-03' -or
        $values['database.host'] -ne '127.0.0.1' -or
        $values['database.port'] -ne '38432' -or
        $values['database.name'] -ne 'stock_quant_research' -or
        $values['database.user'] -ne 'stock_quant_research' -or
        $values['schema.name'] -ne 'tushare_research' -or
        $values['provider'] -ne 'TUSHARE' -or
        $values['provider.endpoints'] -ne 'daily,adj_factor,trade_cal' -or
        $values['endpoint.daily.requests'] -ne '1' -or
        $values['endpoint.adj_factor.requests'] -ne '1' -or
        $values['endpoint.trade_cal.requests'] -ne '1' -or
        $values['maximum.provider.requests'] -ne '3' -or
        $values['retry.budget'] -ne '0' -or
        $values['redirects'] -ne 'NEVER' -or
        $values['execution.source'] -ne 'REDUCED_RESEARCH_MANUAL_DAY001' -or
        $values['no.retry'] -ne 'true') {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_SCOPE_INVALID'
    }
    if ($values['operation'] -in @(
            'READ_SANITIZED_RESULT',
            'DIAGNOSE_TUSHARE_CREDENTIAL')) {
        if ($values['source.request.id'] -notmatch
                '^SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$') {
            throw 'STOCK_QUANT_HOST_BROKER_SOURCE_REQUEST_INVALID'
        }
    } elseif ($values['operation'] -eq
            'RUN_M3_AGENT_RESEARCH_SMOKE') {
        if ($values['source.request.id'] -notmatch
                '^SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$') {
            throw 'STOCK_QUANT_HOST_BROKER_SOURCE_REQUEST_INVALID'
        }
    } elseif ($values['source.request.id'] -ne 'NONE') {
        throw 'STOCK_QUANT_HOST_BROKER_SOURCE_REQUEST_INVALID'
    }
    $created = ConvertTo-StockQuantTimestamp $values['created.at']
    $expires = ConvertTo-StockQuantTimestamp $values['expires.at']
    $validity = $expires - $created
    if ($expires -le $created -or $validity -gt [TimeSpan]::FromMinutes(15) -or
        $Now -lt $created.AddMinutes(-1) -or $Now -ge $expires) {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_EXPIRED'
    }
    $jar = Assert-StockQuantPathInside -Path $values['jar.path'] `
        -Root $paths.TargetRoot `
        -FailureCode 'STOCK_QUANT_HOST_BROKER_JAR_PATH_INVALID' `
        -MustExist -PathType Leaf
    if ([IO.Path]::GetExtension($jar) -ne '.jar') {
        throw 'STOCK_QUANT_HOST_BROKER_JAR_PATH_INVALID'
    }
    $actualHash = ((Get-FileHash -LiteralPath $jar `
        -Algorithm SHA256).Hash).ToLowerInvariant()
    if ($actualHash -cne $values['jar.sha256']) {
        throw 'STOCK_QUANT_HOST_BROKER_JAR_HASH_MISMATCH'
    }
    $proof = "$jar.f1f-b2-proof.properties"
    if (-not (Test-Path -LiteralPath $proof -PathType Leaf)) {
        throw 'STOCK_QUANT_HOST_BROKER_BUILD_PROOF_MISSING'
    }
    $authorization = $null
    $authorizationStatus = 'NOT_REQUIRED_ZERO_PROVIDER_DIAGNOSTIC'
    if ($values['operation'] -eq 'RUN_M2_STRATEGY_RESEARCH_SMOKE') {
        if ($values['authorization.file'] -ne 'NONE') {
            throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_MODE_INVALID'
        }
        $authorizationStatus =
            'M2_STAGE_APPROVED_ZERO_PROVIDER_READ_ONLY'
    } elseif ($values['operation'] -eq
            'CHECK_BAILIAN_CREDENTIAL_STATUS') {
        if ($values['authorization.file'] -ne 'NONE') {
            throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_MODE_INVALID'
        }
        $authorizationStatus =
            'M3_BAILIAN_CREDENTIAL_READABILITY_ZERO_NETWORK'
    } elseif ($values['operation'] -eq
            'RUN_M3_AGENT_RESEARCH_SMOKE') {
        if ($values['authorization.file'] -ne 'NONE') {
            throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_MODE_INVALID'
        }
        $credentialResultPath = Join-Path $paths.Results `
            "$($values['source.request.id']).result.json"
        if (-not (Test-Path -LiteralPath $credentialResultPath `
                -PathType Leaf)) {
            throw 'STOCK_QUANT_HOST_BROKER_M3_CREDENTIAL_SOURCE_INVALID'
        }
        try {
            $credentialResult = Get-Content `
                -LiteralPath $credentialResultPath -Raw -Encoding UTF8 |
                ConvertFrom-Json
            $credentialCompleted = ConvertTo-StockQuantTimestamp `
                ([string]$credentialResult.completedAt)
        } catch {
            throw 'STOCK_QUANT_HOST_BROKER_M3_CREDENTIAL_SOURCE_INVALID'
        }
        if ($credentialResult.schemaVersion -ne
                'STOCK_QUANT_HOST_BROKER_RESULT_V1' -or
            $credentialResult.requestId -ne $values['source.request.id'] -or
            $credentialResult.gitCommit -ne $values['git.commit'] -or
            $credentialResult.operation -ne
                'CHECK_BAILIAN_CREDENTIAL_STATUS' -or
            $credentialResult.status -ne 'SUCCEEDED' -or
            [int]$credentialResult.providerCallCount -ne 0 -or
            [int]$credentialResult.retryCount -ne 0 -or
            -not $credentialResult.summary.credentialReady -or
            $credentialResult.summary.readStatus -ne 'SUCCESS' -or
            [int]$credentialResult.summary.networkCallCount -ne 0 -or
            $credentialResult.summary.outputAudit -ne 'PASSED' -or
            $credentialCompleted -gt $Now.AddSeconds(1) -or
            $Now - $credentialCompleted -gt [TimeSpan]::FromMinutes(15)) {
            throw 'STOCK_QUANT_HOST_BROKER_M3_CREDENTIAL_SOURCE_INVALID'
        }
        $authorizationStatus =
            'M3_USER_APPROVED_BAILIAN_SMOKE_TRANCHE_2_CNY_5_00'
    } elseif ($values['operation'] -eq 'DIAGNOSE_TUSHARE_CREDENTIAL') {
        if ($values['authorization.file'] -ne 'NONE') {
            throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_MODE_INVALID'
        }
        $sourceResultPath = Join-Path $paths.Results `
            "$($values['source.request.id']).result.json"
        $sourceDay001Path = Join-Path $paths.Results `
            "$($values['source.request.id']).day001.json"
        $sourceM1Path = Join-Path $paths.Results `
            "$($values['source.request.id']).m1.json"
        $payloadPaths = @($sourceDay001Path, $sourceM1Path | Where-Object {
            Test-Path -LiteralPath $_ -PathType Leaf
        })
        if (-not (Test-Path -LiteralPath $sourceResultPath -PathType Leaf) -or
            $payloadPaths.Count -ne 1) {
            throw 'STOCK_QUANT_HOST_BROKER_DIAGNOSTIC_SOURCE_INVALID'
        }
        try {
            $sourceResult = Get-Content -LiteralPath $sourceResultPath `
                -Raw -Encoding UTF8 | ConvertFrom-Json
            $sourcePayload = Get-Content -LiteralPath $payloadPaths[0] `
                -Raw -Encoding UTF8 | ConvertFrom-Json
        } catch {
            throw 'STOCK_QUANT_HOST_BROKER_DIAGNOSTIC_SOURCE_INVALID'
        }
        $sourceOperation = if ($payloadPaths[0] -eq $sourceM1Path) {
            'RUN_M1_RESEARCH_DATA'
        } else { 'RUN_DAY001' }
        $sourceStatus = if ($sourceOperation -eq 'RUN_M1_RESEARCH_DATA') {
            'FAILED_PROVIDER'
        } else { 'FAILED_VALIDATION' }
        if ($sourceResult.schemaVersion -ne
                'STOCK_QUANT_HOST_BROKER_RESULT_V1' -or
            $sourceResult.requestId -ne $values['source.request.id'] -or
            $sourceResult.operation -ne $sourceOperation -or
            $sourceResult.status -ne 'FAILED' -or
            [int]$sourceResult.providerCallCount -ne 1 -or
            [int]$sourceResult.retryCount -ne 0 -or
            $sourcePayload.status -ne $sourceStatus -or
            $sourcePayload.safeFailureCode -ne 'TUSHARE_API_ERROR_40101' -or
            [int]$sourcePayload.providerCallCount -ne 1 -or
            [int]$sourcePayload.retryCount -ne 0) {
            throw 'STOCK_QUANT_HOST_BROKER_DIAGNOSTIC_SOURCE_INVALID'
        }
    } else {
        $authorization = Assert-StockQuantPathInside `
            -Path $values['authorization.file'] -Root $paths.TargetRoot `
            -FailureCode 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_PATH_INVALID' `
            -MustExist -PathType Leaf
        if ([IO.Path]::GetExtension($authorization) -ne '.properties') {
            throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_PATH_INVALID'
        }
        if ($values['operation'] -eq 'RUN_M1_RESEARCH_DATA') {
            $m1Authorization = Assert-StockQuantM1AuthorizationNonSensitive `
                -Path $authorization `
                -ExpectedGitCommit $values['git.commit'] `
                -ExpectedArtifactHash $values['jar.sha256'] `
                -ExpectedBuildProof $proof -Now $Now
            foreach ($binding in @(
                    @('mode', 'm1.mode'),
                    @('securities', 'securities'),
                    @('range.start', 'range.start'),
                    @('range.end', 'range.end'),
                    @('anchor.trade.date', 'anchor.trade.date'),
                    @('endpoint.daily.requests', 'endpoint.daily.requests'),
                    @('endpoint.adj_factor.requests', 'endpoint.adj_factor.requests'),
                    @('endpoint.trade_cal.requests', 'endpoint.trade_cal.requests'),
                    @('maximum.provider.requests', 'maximum.provider.requests'),
                    @('provider.stage.calls.before', 'provider.stage.calls.before'))) {
                if ([string]$m1Authorization[$binding[0]] -cne
                    [string]$values[$binding[1]]) {
                    throw 'STOCK_QUANT_HOST_BROKER_M1_BINDING_INVALID'
                }
            }
            $authorizationStatus = 'USER_APPROVED'
        } elseif ($values['operation'] -eq 'VERIFY_M1_TUSHARE_TOKEN') {
            $tokenAuthorization =
                Assert-StockQuantM1TokenVerificationAuthorizationNonSensitive `
                    -Path $authorization `
                    -ExpectedGitCommit $values['git.commit'] `
                    -ExpectedArtifactHash $values['jar.sha256'] `
                    -ExpectedBuildProof $proof -Now $Now
            foreach ($binding in @(
                    @('security.symbol', 'security.symbol'),
                    @('security.exchange', 'security.exchange'),
                    @('trade.date', 'trade.date'),
                    @('endpoint', 'provider.endpoints'),
                    @('endpoint.daily.requests', 'endpoint.daily.requests'),
                    @('maximum.provider.requests', 'maximum.provider.requests'),
                    @('provider.stage.calls.before',
                        'provider.stage.calls.before'))) {
                if ([string]$tokenAuthorization[$binding[0]] -cne
                    [string]$values[$binding[1]]) {
                    throw 'STOCK_QUANT_HOST_BROKER_M1_TOKEN_BINDING_INVALID'
                }
            }
            $authorizationStatus = 'USER_APPROVED'
        } else {
            $authorizationStatus = Assert-StockQuantAuthorizationNonSensitive `
                -Path $authorization `
                -ExpectedGitCommit $values['git.commit'] `
                -ExpectedArtifactHash $values['jar.sha256'] `
                -ExpectedBuildProof $proof -Now $Now
        }
        if (($values['operation'] -in @(
                'RUN_DAY001', 'RUN_M1_RESEARCH_DATA',
                'VERIFY_M1_TUSHARE_TOKEN') -and
                $authorizationStatus -ne 'USER_APPROVED') -or
            ($values['operation'] -eq 'RUN_FAKE_E2E' -and
                $authorizationStatus -ne 'E2E_DRY_RUN')) {
            throw 'STOCK_QUANT_HOST_BROKER_AUTHORIZATION_MODE_INVALID'
        }
    }
    [pscustomobject]@{
        SchemaVersion = $values['schema.version']
        RequestId = $values['request.id']
        Operation = $values['operation']
        GitCommit = $values['git.commit']
        JarPath = $jar
        JarSha256 = $values['jar.sha256']
        BuildProofPath = $proof
        AuthorizationFile = $authorization
        AuthorizationStatus = $authorizationStatus
        CreatedAt = $created
        ExpiresAt = $expires
        SourceRequestId = $values['source.request.id']
        NoRetry = $true
    }
}

function Assert-StockQuantHostBrokerRequestIdAvailable {
    param(
        [Parameter(Mandatory = $true)]
        [string] $RequestId
    )
    if ($RequestId -notmatch
            '^SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$') {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_ID_INVALID'
    }
    $paths = Initialize-StockQuantHostBrokerDirectories
    if (@(Get-ChildItem -LiteralPath $paths.Requests -File -Filter "$RequestId.*").Count -gt 0 -or
        @(Get-ChildItem -LiteralPath $paths.Results -File -Filter "$RequestId.*").Count -gt 0) {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_ID_ALREADY_USED'
    }
}

function Write-StockQuantHostBrokerRequest {
    param(
        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary] $Values
    )
    $requestKeys = if ($Values.Contains('operation')) {
        switch ([string]$Values['operation']) {
            'RUN_M1_RESEARCH_DATA' { $script:M1RequiredKeys; break }
            'VERIFY_M1_TUSHARE_TOKEN' {
                $script:M1TokenVerificationRequiredKeys
                break
            }
            'RUN_M2_STRATEGY_RESEARCH_SMOKE' {
                $script:M2RequiredKeys
                break
            }
            'CHECK_BAILIAN_CREDENTIAL_STATUS' {
                $script:M3BailianCredentialRequiredKeys
                break
            }
            'RUN_M3_AGENT_RESEARCH_SMOKE' {
                $script:M3RequiredKeys
                break
            }
            default { $script:RequiredKeys }
        }
    } else { $script:RequiredKeys }
    foreach ($key in $requestKeys) {
        if (-not $Values.Contains($key)) {
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'
        }
    }
    if ($Values.Count -ne $requestKeys.Count) {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'
    }
    $requestId = [string]$Values['request.id']
    Assert-StockQuantHostBrokerRequestIdAvailable -RequestId $requestId
    $paths = Initialize-StockQuantHostBrokerDirectories
    $destination = Join-Path $paths.Requests "$requestId.request.properties"
    $temporary = Join-Path $paths.Requests `
        (".$requestId." + [Guid]::NewGuid().ToString('N') + '.tmp')
    $lines = foreach ($key in $requestKeys) {
        $value = [string]$Values[$key]
        if ([string]::IsNullOrWhiteSpace($value) -or
            $value -match '[\x00-\x1F\x7F]') {
            throw 'STOCK_QUANT_HOST_BROKER_REQUEST_FIELDS_INVALID'
        }
        "$key=$value"
    }
    $content = ($lines -join "`n") + "`n"
    if ($content -match '(?i)(database\.password|jdbc\.password|provider\.token|token\.sha|credentialblob|api[._]?key)') {
        throw 'STOCK_QUANT_HOST_BROKER_REQUEST_SECRET_FIELD_FORBIDDEN'
    }
    try {
        [IO.File]::WriteAllText(
            $temporary, $content, [Text.UTF8Encoding]::new($false))
        [IO.File]::Move($temporary, $destination)
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
    Read-StockQuantHostBrokerRequest -Path $destination | Out-Null
    return $destination
}

function Write-StockQuantHostBrokerResult {
    param(
        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary] $Result
    )
    $requestId = [string]$Result['requestId']
    if ($requestId -notmatch
            '^SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}$') {
        throw 'STOCK_QUANT_HOST_BROKER_RESULT_ID_INVALID'
    }
    $paths = Initialize-StockQuantHostBrokerDirectories
    $destination = Join-Path $paths.Results "$requestId.result.json"
    if (Test-Path -LiteralPath $destination) {
        throw 'STOCK_QUANT_HOST_BROKER_RESULT_ALREADY_EXISTS'
    }
    $Result['schemaVersion'] = $script:ResultVersion
    $json = $Result | ConvertTo-Json -Depth 8
    if ($json -match '(?i)"[^"\r\n]*(password|token|credentialblob|jdbc|api.?key)[^"\r\n]*"\s*:' -or
        $json -match '(?i)StockQuant/(TushareToken|[A-Za-z0-9_-]+ApiKey)') {
        throw 'STOCK_QUANT_HOST_BROKER_RESULT_SECRET_FIELD_FORBIDDEN'
    }
    $temporary = Join-Path $paths.Results `
        (".$requestId." + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText(
            $temporary, $json + "`n", [Text.UTF8Encoding]::new($false))
        [IO.File]::Move($temporary, $destination)
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
    return $destination
}

function ConvertTo-StockQuantM3CallTelemetrySummary {
    param(
        [Parameter(Mandatory = $true)]
        [object[]] $Telemetry
    )
    foreach ($entry in $Telemetry) {
        [int]$inputUnits = [int]$entry.inputTokenCount
        [int]$outputUnits = [int]$entry.outputTokenCount
        [int]$reasoningUnits = [int]$entry.reasoningTokenCount
        [int]$totalUnits = [int]$entry.totalTokenCount
        [decimal]$estimatedCost = [decimal]::Parse(
            [string]$entry.estimatedCost,
            [Globalization.NumberStyles]::Number,
            [Globalization.CultureInfo]::InvariantCulture)
        [decimal]$accountedCost = [decimal]::Parse(
            [string]$entry.accountedCost,
            [Globalization.NumberStyles]::Number,
            [Globalization.CultureInfo]::InvariantCulture)
        $actualStatus = [string]$entry.actualCostStatus
        $actualValue = $entry.providerReportedActualCostCny
        if ([int]$entry.callNumber -lt 1 -or
            [int]$entry.callNumber -gt 16 -or
            [string]$entry.status -notin @(
                'COMPLETED', 'RESPONSE_REJECTED', 'USAGE_REJECTED',
                'USAGE_UNAVAILABLE') -or
            $inputUnits -lt 0 -or $outputUnits -lt 0 -or
            $reasoningUnits -lt 0 -or $reasoningUnits -gt $outputUnits -or
            $totalUnits -ne ($inputUnits + $outputUnits) -or
            $estimatedCost -lt 0 -or $accountedCost -lt 0 -or
            $actualStatus -notin @('PROVIDED', 'NOT_PROVIDED_BY_API') -or
            ($actualStatus -eq 'PROVIDED') -ne ($null -ne $actualValue)) {
            throw 'STOCK_QUANT_HOST_BROKER_M3_TELEMETRY_INVALID'
        }
        $actualCost = $null
        if ($null -ne $actualValue) {
            $actualCost = [decimal]::Parse(
                [string]$actualValue,
                [Globalization.NumberStyles]::Number,
                [Globalization.CultureInfo]::InvariantCulture)
            if ($actualCost -lt 0) {
                throw 'STOCK_QUANT_HOST_BROKER_M3_TELEMETRY_INVALID'
            }
        }
        [ordered]@{
            callNumber = [int]$entry.callNumber
            status = [string]$entry.status
            inputUnits = $inputUnits
            outputUnits = $outputUnits
            reasoningUnits = $reasoningUnits
            totalUnits = $totalUnits
            estimatedCostCny = $estimatedCost.ToString(
                [Globalization.CultureInfo]::InvariantCulture)
            accountedCostCny = $accountedCost.ToString(
                [Globalization.CultureInfo]::InvariantCulture)
            actualCostCny = $(if ($null -eq $actualCost) {
                $null
            } else {
                $actualCost.ToString(
                    [Globalization.CultureInfo]::InvariantCulture)
            })
            actualCostStatus = $actualStatus
        }
    }
}

function Write-StockQuantHostBrokerHeartbeat {
    param(
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[0-9a-f]{40}$')]
        [string] $GitCommit,
        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string] $WindowsUser,
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 2147483647)]
        [int] $ProcessId,
        [Parameter(Mandatory = $true)]
        [DateTimeOffset] $StartedAt,
        [Parameter(Mandatory = $true)]
        [ValidateSet('IDLE', 'BUSY')]
        [string] $State,
        [DateTimeOffset] $Now = [DateTimeOffset]::UtcNow
    )
    if ($StartedAt.Offset -ne [TimeSpan]::Zero -or
        $Now.Offset -ne [TimeSpan]::Zero -or $Now -lt $StartedAt -or
        $WindowsUser -match '[\x00-\x1F\x7F]' -or
        $WindowsUser -match '(?i)(password|token|credentialblob|jdbc)') {
        throw 'STOCK_QUANT_HOST_BROKER_HEARTBEAT_FIELDS_INVALID'
    }
    $paths = Initialize-StockQuantHostBrokerDirectories
    $heartbeat = [ordered]@{
        schemaVersion = $script:HeartbeatVersion
        brokerVersion = $script:BrokerVersion
        gitCommit = $GitCommit
        windowsUser = $WindowsUser
        processId = $ProcessId
        startedAt = $StartedAt.ToString('o')
        lastHeartbeat = $Now.ToString('o')
        state = $State
    }
    $json = $heartbeat | ConvertTo-Json -Compress
    if ($json -match '(?i)(password|token|credentialblob|jdbc)') {
        throw 'STOCK_QUANT_HOST_BROKER_HEARTBEAT_FIELDS_INVALID'
    }
    $writerMutex = [Threading.Mutex]::new(
        $false, 'Local\StockQuantHostBrokerHeartbeatWriter')
    $writerMutexHeld = $false
    $temporary = Join-Path $paths.Base `
        ('.heartbeat.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    $backups = [Collections.Generic.List[string]]::new()
    try {
        $writerMutexHeld = $writerMutex.WaitOne(2000)
        if (-not $writerMutexHeld) {
            throw 'STOCK_QUANT_HOST_BROKER_HEARTBEAT_WRITE_FAILED'
        }
        [IO.File]::WriteAllText(
            $temporary, $json + "`n", [Text.UTF8Encoding]::new($false))
        $written = $false
        foreach ($attempt in 1..20) {
            $backup = Join-Path $paths.Base `
                ('.heartbeat.backup.' +
                    [Guid]::NewGuid().ToString('N') + '.tmp')
            $backups.Add($backup)
            try {
                if (Test-Path -LiteralPath $paths.Heartbeat -PathType Leaf) {
                    [IO.File]::Replace($temporary, $paths.Heartbeat, $backup)
                } else {
                    [IO.File]::Move($temporary, $paths.Heartbeat)
                }
                $written = $true
                break
            } catch [IO.IOException] {
                if ($attempt -eq 20) {
                    throw 'STOCK_QUANT_HOST_BROKER_HEARTBEAT_WRITE_FAILED'
                }
                Start-Sleep -Milliseconds 25
            } catch [UnauthorizedAccessException] {
                if ($attempt -eq 20) {
                    throw 'STOCK_QUANT_HOST_BROKER_HEARTBEAT_WRITE_FAILED'
                }
                Start-Sleep -Milliseconds 25
            }
        }
        if (-not $written) {
            throw 'STOCK_QUANT_HOST_BROKER_HEARTBEAT_WRITE_FAILED'
        }
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
        foreach ($backup in $backups) {
            if (Test-Path -LiteralPath $backup) {
                Remove-Item -LiteralPath $backup -Force `
                    -ErrorAction SilentlyContinue
            }
        }
        if ($writerMutexHeld) { $writerMutex.ReleaseMutex() }
        $writerMutex.Dispose()
    }
    return $paths.Heartbeat
}

function Read-StockQuantHostBrokerHeartbeat {
    param(
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[0-9a-f]{40}$')]
        [string] $ExpectedGitCommit,
        [switch] $AllowAncestorGitCommit,
        [ValidateRange(2, 60)]
        [int] $MaximumAgeSeconds = 6,
        [DateTimeOffset] $Now = [DateTimeOffset]::UtcNow
    )
    $paths = Get-StockQuantHostBrokerPaths
    if (-not (Test-Path -LiteralPath $paths.Heartbeat -PathType Leaf)) {
        throw 'HOST_BROKER_NOT_RUNNING'
    }
    try {
        $share = [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
        $stream = [IO.FileStream]::new(
            $paths.Heartbeat, [IO.FileMode]::Open, [IO.FileAccess]::Read,
            $share)
        try {
            if ($stream.Length -lt 2 -or $stream.Length -gt 4096) {
                throw 'INVALID'
            }
            $memory = [IO.MemoryStream]::new()
            try {
                $stream.CopyTo($memory)
                $bytes = $memory.ToArray()
            } finally {
                $memory.Dispose()
            }
        } finally {
            $stream.Dispose()
        }
        if ($bytes.Length -lt 2 -or $bytes.Length -gt 4096 -or
            ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and
                $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) {
            throw 'INVALID'
        }
        $json = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
        if ($json -match '(?i)(password|token|credentialblob|jdbc)') {
            throw 'INVALID'
        }
        $heartbeat = $json | ConvertFrom-Json
        $expectedFields = @(
            'schemaVersion', 'brokerVersion', 'gitCommit', 'windowsUser',
            'processId', 'startedAt', 'lastHeartbeat', 'state')
        $actualFields = @($heartbeat.PSObject.Properties.Name)
        if ($actualFields.Count -ne $expectedFields.Count) { throw 'INVALID' }
        foreach ($field in $expectedFields) {
            if ($field -notin $actualFields) { throw 'INVALID' }
        }
        $startedAt = ConvertTo-StockQuantTimestamp `
            ([string]$heartbeat.startedAt)
        $lastHeartbeat = ConvertTo-StockQuantTimestamp `
            ([string]$heartbeat.lastHeartbeat)
        $heartbeatGitCommit = [string]$heartbeat.gitCommit
        $gitCommitCompatible = $heartbeatGitCommit -ceq $ExpectedGitCommit
        if (-not $gitCommitCompatible -and $AllowAncestorGitCommit -and
            $heartbeatGitCommit -match '^[0-9a-f]{40}$') {
            & git -C $paths.RepositoryRoot merge-base --is-ancestor `
                $heartbeatGitCommit $ExpectedGitCommit 2>$null | Out-Null
            $gitCommitCompatible = $LASTEXITCODE -eq 0
        }
        if ($heartbeat.schemaVersion -cne $script:HeartbeatVersion -or
            $heartbeat.brokerVersion -cne $script:BrokerVersion -or
            -not $gitCommitCompatible -or
            [string]::IsNullOrWhiteSpace([string]$heartbeat.windowsUser) -or
            [string]$heartbeat.windowsUser -match '[\x00-\x1F\x7F]' -or
            [int64]$heartbeat.processId -lt 1 -or
            [int64]$heartbeat.processId -gt 2147483647 -or
            [string]$heartbeat.state -notin @('IDLE', 'BUSY') -or
            $lastHeartbeat -lt $startedAt -or
            $lastHeartbeat -gt $Now.AddSeconds(1) -or
            $Now - $lastHeartbeat -gt
                [TimeSpan]::FromSeconds($MaximumAgeSeconds)) {
            throw 'INVALID'
        }
        return $heartbeat
    } catch {
        throw 'HOST_BROKER_NOT_RUNNING'
    }
}

function New-StockQuantHostBrokerRequestId {
    $timestamp = [DateTimeOffset]::UtcNow.ToString(
        'yyyyMMddTHHmmssZ', [Globalization.CultureInfo]::InvariantCulture)
    $suffix = ([Guid]::NewGuid().ToString('N').Substring(0, 12)).ToUpperInvariant()
    return "SQHB_${timestamp}_$suffix"
}

Export-ModuleMember -Function @(
    'Get-StockQuantHostBrokerPaths'
    'Initialize-StockQuantHostBrokerDirectories'
    'ConvertTo-StockQuantSafeCode'
    'Assert-StockQuantPathInside'
    'Read-StockQuantHostBrokerRequest'
    'Assert-StockQuantHostBrokerRequestIdAvailable'
    'Write-StockQuantHostBrokerRequest'
    'Write-StockQuantHostBrokerResult'
    'ConvertTo-StockQuantM3CallTelemetrySummary'
    'Write-StockQuantHostBrokerHeartbeat'
    'Read-StockQuantHostBrokerHeartbeat'
    'New-StockQuantHostBrokerRequestId'
)
