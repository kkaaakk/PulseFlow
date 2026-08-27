param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Token = '',
    [string]$ReportDir = '',
    [switch]$PiiEnabled,
    [switch]$RunApi
)

. (Join-Path $PSScriptRoot 'common.ps1')

try {
    Assert-LoopbackUrl -Url $BaseUrl
    if (-not $ReportDir) { $ReportDir = Get-TestReportDirectory -RunId (New-TestRunId) }
    $checker = Join-Path $TestingRoot 'scripts\validate_ai_dataset.py'
    Invoke-PythonScript -ScriptPath $checker
    $evaluator = Join-Path $TestingRoot 'scripts\evaluate_ai_dataset.py'
    $arguments = @('--base-url', $BaseUrl, '--report-dir', $ReportDir)
    if ($PiiEnabled) { $arguments += '--pii-enabled' }
    if (-not $RunApi) { $arguments += '--offline' }
    if ($Token) { $env:PULSEFLOW_TOKEN = $Token }
    Invoke-PythonScript -ScriptPath $evaluator -Arguments $arguments
} catch {
    Write-Error $_
    exit 1
}
