param(
    [Parameter(Mandatory = $true)][string]$Manifest,
    [string]$RunDir = '',
    [string]$ReportDir = '',
    [int]$WaitSeconds = 120,
    [switch]$SkipMySql,
    [switch]$SkipRedis
)

. (Join-Path $PSScriptRoot 'common.ps1')

try {
    $env:PULSEFLOW_TEST_ENV = 'test'
    Assert-TestDatabase
    $validator = Join-Path $TestingRoot 'validators\validate_run.py'
    $arguments = @('--manifest', $Manifest, '--wait-seconds', $WaitSeconds.ToString())
    if ($RunDir) { $arguments += @('--run-dir', $RunDir) }
    if ($ReportDir) { $arguments += @('--report-dir', $ReportDir) }
    if ($SkipMySql) { $arguments += '--skip-mysql' }
    if ($SkipRedis) { $arguments += '--skip-redis' }
    Invoke-PythonScript -ScriptPath $validator -Arguments $arguments
} catch {
    Write-Error $_
    exit 1
}
