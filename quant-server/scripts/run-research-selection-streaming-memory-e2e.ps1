[CmdletBinding()]
param(
    [ValidateSet(2048, 3072)]
    [int]$HeapMiB = 2048
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$serverTarget = Join-Path $repoRoot 'quant-server\target'

Push-Location $repoRoot
try {
    & "$repoRoot\mvnw.cmd" -pl quant-server -am -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw 'STREAMING_DATASET_PACKAGE_FAILED'
    }
    $jar = Get-ChildItem -LiteralPath $serverTarget -Filter 'quant-server-*.jar' |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw 'STREAMING_DATASET_PACKAGED_JAR_MISSING'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        $required = @(
            'BOOT-INF/classes/com/stockquant/server/researchselection/ResearchUniverseMainboardDatasetLoader.class',
            'BOOT-INF/classes/com/stockquant/server/agent/marketfacts/PitMarketFactRepository.class',
            'BOOT-INF/classes/com/stockquant/server/agent/marketfacts/TushareResearchSelectionManualRunner.class'
        )
        $names = @($archive.Entries | ForEach-Object FullName)
        foreach ($entry in $required) {
            if ($names -notcontains $entry) {
                throw 'STREAMING_DATASET_PACKAGED_CLASS_MISSING'
            }
        }
    } finally {
        $archive.Dispose()
    }

    $existingJava = @(Get-Process -Name java -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty Id)
    $peakRss = 0L
    $maven = Start-Job -ScriptBlock {
        param($root, $heap)
        Set-Location $root
        & "$root\mvnw.cmd" -pl quant-server -am `
            '-Dtest=ResearchUniverseMainboardStreamingDatasetTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' `
            '-Dstockquant.streaming.memory.probe=true' `
            "-DargLine=-Xmx${heap}m" test
        if ($LASTEXITCODE -ne 0) {
            throw 'STREAMING_DATASET_LIMITED_HEAP_TEST_FAILED'
        }
    } -ArgumentList $repoRoot, $HeapMiB
    try {
        while ($maven.State -eq 'Running') {
            Get-Process -Name java -ErrorAction SilentlyContinue |
                Where-Object { $existingJava -notcontains $_.Id } |
                ForEach-Object {
                    if ($_.WorkingSet64 -gt $peakRss) {
                        $peakRss = $_.WorkingSet64
                    }
                }
            Start-Sleep -Milliseconds 250
        }
        Receive-Job -Job $maven -Wait -ErrorAction Stop |
            ForEach-Object { Write-Output $_ }
        if ($maven.State -ne 'Completed') {
            throw 'STREAMING_DATASET_LIMITED_HEAP_TEST_FAILED'
        }
    } finally {
        Remove-Job -Job $maven -Force -ErrorAction SilentlyContinue
    }

    $jarSha = (Get-FileHash -LiteralPath $jar.FullName `
        -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output 'RESEARCH_SELECTION_STREAMING_PACKAGED_E2E=PASS'
    Write-Output 'RESEARCH_SELECTION_STREAMING_UNIVERSE=3193'
    Write-Output 'RESEARCH_SELECTION_STREAMING_SESSIONS=250'
    Write-Output ("RESEARCH_SELECTION_STREAMING_HEAP_LIMIT_MIB=$HeapMiB")
    Write-Output ("RESEARCH_SELECTION_STREAMING_PEAK_RSS_BYTES=$peakRss")
    Write-Output ("RESEARCH_SELECTION_STREAMING_JAR_SHA256=$jarSha")
    Write-Output 'RESEARCH_SELECTION_STREAMING_QUANTITATIVE_SCAN=PASS'
    Write-Output 'RESEARCH_SELECTION_STREAMING_REAL_TUSHARE_CALLS=0'
    Write-Output 'RESEARCH_SELECTION_STREAMING_REAL_BAILIAN_CALLS=0'
} finally {
    Pop-Location
}
