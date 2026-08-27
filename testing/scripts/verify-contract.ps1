param([string]$Output = '')

. (Join-Path $PSScriptRoot 'common.ps1')

try {
    $checker = Join-Path $TestingRoot 'scripts\verify_contract.py'
    $arguments = @()
    if ($Output) { $arguments += @('--output', $Output) }
    Invoke-PythonScript -ScriptPath $checker -Arguments $arguments
} catch {
    Write-Error $_
    exit 1
}
