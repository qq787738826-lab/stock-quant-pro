[CmdletBinding()]
param(
    [switch]$ForceImportFailure
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if ((Resolve-Path -LiteralPath (Get-Location)).Path -ne (Resolve-Path -LiteralPath $repoRoot).Path) {
    throw 'This script must be run from the repository root.'
}

$requiredDatabaseVariables = @(
    'STOCK_QUANT_TEST_DB_URL',
    'STOCK_QUANT_TEST_DB_USERNAME',
    'STOCK_QUANT_TEST_DB_PASSWORD'
)
foreach ($name in $requiredDatabaseVariables) {
    if (-not (Test-Path ("Env:" + $name)) -or [string]::IsNullOrWhiteSpace((Get-Item ("Env:" + $name)).Value)) {
        throw ("Required test database environment variable is missing: " + $name)
    }
}

$pythonCandidates = @(
    (Join-Path $repoRoot 'quant-ai\.venv\Scripts\python.exe'),
    (Join-Path $repoRoot '.venv\Scripts\python.exe')
)
$python = $pythonCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$pythonLauncher = $null
if (-not $python) {
    $pathPython = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($pathPython) {
        $python = $pathPython.Source
    }
}
if (-not $python) {
    $pythonLauncher = Get-Command py.exe -ErrorAction SilentlyContinue
    if (-not $pythonLauncher) {
        throw 'No existing Python interpreter was found.'
    }
}

$sensitiveNames = @(
    'AI_PROVIDER', 'AI_SERVICE_URL',
    'STOCK_QUANT_PYTHON_BASE_URL',
    'STOCK_QUANT_TEST_DB_URL', 'STOCK_QUANT_TEST_DB_USERNAME', 'STOCK_QUANT_TEST_DB_PASSWORD',
    'DATABASE_URL', 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD', 'PGHOST', 'PGPORT', 'PGDATABASE', 'PGUSER', 'PGPASSWORD'
)
$sensitivePattern = '(?i)(OPENAI|ANTHROPIC|DEEPSEEK|DASHSCOPE|LLM|AKSHARE|TUSHARE|MARKET|QUOTE|BROKER|TRADING|TRADE|API[_-]?KEY|TOKEN|SECRET)'
$sensitiveNames += Get-ChildItem Env: | Where-Object { $_.Name -match $sensitivePattern } |
        Select-Object -ExpandProperty Name
$sensitiveNames = @($sensitiveNames | Sort-Object -Unique)

$baseUrlExisted = Test-Path Env:STOCK_QUANT_PYTHON_BASE_URL
$originalBaseUrl = if ($baseUrlExisted) { $env:STOCK_QUANT_PYTHON_BASE_URL } else { $null }
$savedEnvironment = @{}
$removedEnvironmentNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$quantAi = Join-Path $repoRoot 'quant-ai'
$stdoutLog = Join-Path ([System.IO.Path]::GetTempPath()) ("stock-quant-python-smoke-" + [guid]::NewGuid() + '.out.log')
$stderrLog = Join-Path ([System.IO.Path]::GetTempPath()) ("stock-quant-python-smoke-" + [guid]::NewGuid() + '.err.log')
$pythonProcess = $null
$pythonRootPid = $null
$port = $null
$trackedProcessIds = @()
$executionSucceeded = $false
$environmentRestored = $false
$baseUrlRestored = $false
$processRemaining = $false
$agentPostCount = 0
$post200Count = 0
$finalExitCode = 1
$failureReason = $null

function Restore-SensitiveEnvironment([switch]$ExcludePythonBaseUrl) {
    foreach ($entry in $savedEnvironment.GetEnumerator()) {
        if ($ExcludePythonBaseUrl -and $entry.Key -eq 'STOCK_QUANT_PYTHON_BASE_URL') {
            continue
        }
        Set-Item ("Env:" + $entry.Key) $entry.Value
    }
}

function Get-SmokeProcessTreeIds([int]$RootPid) {
    $ids = [System.Collections.Generic.List[int]]::new()
    $pending = [System.Collections.Generic.Queue[int]]::new()
    $pending.Enqueue($RootPid)
    while ($pending.Count -gt 0) {
        $parentPid = $pending.Dequeue()
        if (-not $ids.Contains($parentPid)) {
            $ids.Add($parentPid)
        }
        $children = Get-CimInstance Win32_Process -Filter ("ParentProcessId=" + $parentPid) -ErrorAction SilentlyContinue
        foreach ($child in $children) {
            if (-not $ids.Contains([int]$child.ProcessId)) {
                $pending.Enqueue([int]$child.ProcessId)
            }
        }
    }
    return @($ids)
}

try {
    New-Item -ItemType File -Path $stdoutLog, $stderrLog -Force | Out-Null

    # All parent environment mutations are protected by this try/finally.
    foreach ($name in $sensitiveNames) {
        if (Test-Path ("Env:" + $name)) {
            $savedEnvironment[$name] = (Get-Item ("Env:" + $name)).Value
            Remove-Item ("Env:" + $name)
            $removedEnvironmentNames.Add($name) | Out-Null
        }
    }

    # Resolve the py launcher only after isolation, because this operation starts Python.
    if (-not $python) {
        $resolved = & $pythonLauncher.Source -3 -c "import sys; print(sys.executable)"
        if ($LASTEXITCODE -ne 0 -or -not $resolved) {
            throw 'No usable Python 3 interpreter was found.'
        }
        $python = $resolved.Trim()
    }

    $isolatedNameArgument = $sensitiveNames -join ';'
    & $python -c "import os,sys; names=[n for n in sys.argv[1].split(';') if n]; ok=not any(n in os.environ for n in names); print('PYTHON_ENV_ISOLATED=' + str(ok)); sys.exit(0 if ok else 1)" $isolatedNameArgument
    if ($LASTEXITCODE -ne 0) {
        throw 'Python environment isolation check failed.'
    }

    Push-Location $quantAi
    try {
        if ($ForceImportFailure) {
            & $python -c "raise ImportError('controlled smoke preflight failure')"
        } else {
            & $python -c "import sys, fastapi, uvicorn, pydantic; import app.main; print(sys.version.split()[0])"
        }
        if ($LASTEXITCODE -ne 0) {
            throw 'Python import preflight failed.'
        }
        & $python -m unittest discover -s tests\agent_team -p 'test_*.py' -v
        if ($LASTEXITCODE -ne 0) {
            throw 'Python agent team tests failed.'
        }
    } finally {
        Pop-Location
    }

    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Parse('127.0.0.1'), 0)
    try {
        $listener.Start()
        $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
    if ($port -lt 1024 -or $port -gt 65535) {
        throw 'Unable to select a safe unprivileged loopback port.'
    }

    $pythonProcess = Start-Process -FilePath $python -ArgumentList @(
        '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', [string]$port,
        '--workers', '1', '--access-log'
    ) -WorkingDirectory $quantAi -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru -WindowStyle Hidden
    $pythonRootPid = $pythonProcess.Id

    # Uvicorn inherited the isolated environment. Restore only non-base-URL values for Maven.
    Restore-SensitiveEnvironment -ExcludePythonBaseUrl
    $environmentRestored = $true

    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline -and -not $pythonProcess.HasExited) {
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $connect = $client.BeginConnect('127.0.0.1', $port, $null, $null)
            if ($connect.AsyncWaitHandle.WaitOne(250) -and $client.Connected) {
                $client.EndConnect($connect)
                $ready = $true
                break
            }
        } catch {
        } finally {
            $client.Dispose()
        }
        Start-Sleep -Milliseconds 100
    }
    if (-not $ready) {
        throw 'Python FastAPI service did not become ready within the bounded timeout.'
    }

    $env:STOCK_QUANT_PYTHON_BASE_URL = "http://127.0.0.1:$port"
    & (Join-Path $repoRoot 'mvnw.cmd') '-pl' 'quant-server' '-am' '-Dtest=AgentPythonServicePostgresSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' 'test'
    $mavenExitCode = $LASTEXITCODE
    if ($mavenExitCode -ne 0) {
        $finalExitCode = $mavenExitCode
        throw 'Java real-Python smoke test failed.'
    }
    $executionSucceeded = $true
    $failureReason = $null
} catch {
    if ($finalExitCode -eq 0) {
        $finalExitCode = 1
    }
    $failureReason = $_.Exception.Message
} finally {
    try {
        Restore-SensitiveEnvironment -ExcludePythonBaseUrl
        $environmentRestored = $true
    } catch {
        $environmentRestored = $false
        $executionSucceeded = $false
        if (-not $failureReason) {
            $failureReason = 'Failed to restore the parent process environment.'
        }
        $finalExitCode = 1
    }

    try {
        if ($baseUrlExisted) {
            $env:STOCK_QUANT_PYTHON_BASE_URL = $originalBaseUrl
        } else {
            Remove-Item Env:STOCK_QUANT_PYTHON_BASE_URL -ErrorAction SilentlyContinue
        }
        $baseUrlRestored = $true
    } catch {
        $baseUrlRestored = $false
        $executionSucceeded = $false
        if (-not $failureReason) {
            $failureReason = 'Failed to restore the Python base URL environment state.'
        }
        $finalExitCode = 1
    }

    if ($pythonRootPid) {
        $trackedProcessIds = @(Get-SmokeProcessTreeIds -RootPid $pythonRootPid)
        for ($index = $trackedProcessIds.Count - 1; $index -ge 0; $index--) {
            Stop-Process -Id $trackedProcessIds[$index] -Force -ErrorAction SilentlyContinue
        }
        $cleanupDeadline = [DateTime]::UtcNow.AddSeconds(5)
        do {
            $remainingIds = @($trackedProcessIds | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
            if ($remainingIds.Count -eq 0) {
                break
            }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $cleanupDeadline)

        $portProcess = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
            $_.CommandLine -and $_.CommandLine -match 'app\.main:app' -and
            $_.CommandLine -match ("--port\s+" + [regex]::Escape([string]$port) + '(\s|$)')
        }
        $processRemaining = [bool](@($trackedProcessIds | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue }).Count -gt 0 -or $portProcess)
        if ($processRemaining) {
            $executionSucceeded = $false
            if (-not $failureReason) {
                $failureReason = 'The Python smoke process tree did not stop within the bounded timeout.'
            }
            $finalExitCode = 1
        }
    }

    $logLines = @()
    if (Test-Path -LiteralPath $stderrLog) {
        $logLines += Get-Content -LiteralPath $stderrLog -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $stdoutLog) {
        $logLines += Get-Content -LiteralPath $stdoutLog -ErrorAction SilentlyContinue
    }
    $agentPostCount = @($logLines | Where-Object { $_ -match '"POST /agents/team/analyze HTTP/1\.[01]"\s+\d{3}' }).Count
    $post200Count = @($logLines | Where-Object { $_ -match '"POST /agents/team/analyze HTTP/1\.[01]"\s+200' }).Count

    Write-Output ("PYTHON_ROOT_PID=" + $(if ($pythonRootPid) { $pythonRootPid } else { 'NOT_STARTED' }))
    Write-Output ("PYTHON_AGENT_POST_COUNT=" + $agentPostCount)
    Write-Output ("PYTHON_POST_200_COUNT=" + $post200Count)
    Write-Output ("PYTHON_PROCESS_REMAINING=" + $processRemaining)

    if ($agentPostCount -ne 1 -or $post200Count -ne 1) {
        $executionSucceeded = $false
        if (-not $failureReason) {
            $failureReason = 'Python agent endpoint request counts did not match the smoke contract.'
        }
        $finalExitCode = 1
    }
    if (-not $environmentRestored -or -not $baseUrlRestored) {
        $executionSucceeded = $false
        $finalExitCode = 1
    }

    if ($executionSucceeded -and -not $processRemaining -and $agentPostCount -eq 1 -and $post200Count -eq 1) {
        Remove-Item -LiteralPath $stdoutLog, $stderrLog -ErrorAction SilentlyContinue
        $finalExitCode = 0
    } else {
        Write-Output ("Python smoke stdout log retained at: " + $stdoutLog)
        Write-Output ("Python smoke stderr log retained at: " + $stderrLog)
    }
}

if ($finalExitCode -ne 0) {
    Write-Error $(if ($failureReason) { $failureReason } else { 'Python smoke workflow failed.' })
}
exit $finalExitCode
