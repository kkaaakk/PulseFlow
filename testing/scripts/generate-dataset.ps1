param(
    [ValidateSet('small', 'medium', 'large')][string]$Scale = 'small',
    [ValidateSet('all', 'normal', 'duplicate', 'out-of-order', 'late', 'invalid', 'hot-user', 'campaign')][string]$Scenario = 'all',
    [int]$Seed = 20260827,
    [string]$OutputDir = ''
)

. (Join-Path $PSScriptRoot 'common.ps1')

try {
    $generator = Join-Path $TestingRoot 'generator\generate_dataset.py'
    $arguments = @('--scale', $Scale.ToUpperInvariant(), '--scenario', $Scenario, '--seed', $Seed.ToString())
    if ($OutputDir) {
        $arguments += @('--output-dir', $OutputDir)
    }
    Invoke-PythonScript -ScriptPath $generator -Arguments $arguments
} catch {
    Write-Error $_
    exit 1
}
