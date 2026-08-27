param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$Vus = 10,
    [string]$Duration = '30s',
    [string]$ReportDir = '',
    [switch]$SkipK6
)

. (Join-Path $PSScriptRoot 'common.ps1')

try {
    Assert-LoopbackUrl -Url $BaseUrl
    if ($SkipK6) {
        Write-Warning 'k6 smoke test explicitly skipped; this is not a PASS.'
        exit 2
    }
    if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
        throw 'k6 was not found on PATH. Install k6 or use -SkipK6 and record NOT_RUN.'
    }
    if (-not $ReportDir) {
        $ReportDir = Get-TestReportDirectory -RunId (New-TestRunId)
    }
    New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null
    $script = Join-Path $TestingRoot 'k6\smoke.js'
    & k6 run '-e' "BASE_URL=$BaseUrl" '-e' "VUS=$Vus" '-e' "DURATION=$Duration" '--summary-export' (Join-Path $ReportDir 'k6-summary.json') $script
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} catch {
    Write-Error $_
    exit 1
}
