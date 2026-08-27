param(
    [Parameter(Mandatory = $true)][string]$Dataset,
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ReportDir = '',
    [string]$RunId = '',
    [int]$PacingMs = 1,
    [int]$MaxEvents = 0,
    [switch]$DryRun,
    [switch]$StopOnError,
    [switch]$RebaseEventTime
)

. (Join-Path $PSScriptRoot 'common.ps1')

try {
    Assert-LoopbackUrl -Url $BaseUrl
    $replay = Join-Path $TestingRoot 'scripts\replay_dataset.py'
    $arguments = @('--dataset', $Dataset, '--base-url', $BaseUrl, '--pacing-ms', $PacingMs.ToString())
    if ($ReportDir) { $arguments += @('--report-dir', $ReportDir) }
    if ($RunId) { $arguments += @('--run-id', $RunId) }
    if ($MaxEvents -gt 0) { $arguments += @('--max-events', $MaxEvents.ToString()) }
    if ($DryRun) { $arguments += '--dry-run' }
    if ($StopOnError) { $arguments += '--stop-on-error' }
    if ($RebaseEventTime) { $arguments += '--rebase-event-time' }
    Invoke-PythonScript -ScriptPath $replay -Arguments $arguments
} catch {
    Write-Error $_
    exit 1
}
