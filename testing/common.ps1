Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:TestingRoot = $PSScriptRoot
$script:RepoRoot = Split-Path -Parent $script:TestingRoot

function Resolve-PythonCommand {
    if (Get-Command python -ErrorAction SilentlyContinue) {
        return 'python'
    }
    if (Get-Command py -ErrorAction SilentlyContinue) {
        return 'py'
    }
    throw 'Python 3.10+ is required but neither python nor py was found on PATH.'
}

function Invoke-PythonScript {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [string[]]$Arguments = @()
    )
    $python = Resolve-PythonCommand
    & $python $ScriptPath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Python script failed with exit code $LASTEXITCODE`: $ScriptPath"
    }
}

function Assert-LoopbackUrl {
    param([Parameter(Mandatory = $true)][string]$Url)
    $parsed = [Uri]$Url
    if ($parsed.Scheme -notin @('http', 'https')) {
        throw "Only http/https test URLs are allowed: $Url"
    }
    if ($parsed.Host -notin @('localhost', '127.0.0.1', '::1')) {
        throw "Refusing non-loopback test URL: $Url"
    }
}

function Assert-TcpEndpoint {
    param([Parameter(Mandatory = $true)][string]$Url)
    $parsed = [Uri]$Url
    $port = if ($parsed.Port -gt 0) { $parsed.Port } elseif ($parsed.Scheme -eq 'https') { 443 } else { 80 }
    $reachable = Test-NetConnection -ComputerName $parsed.Host -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue
    if (-not $reachable) {
        throw "Test application is not reachable at $($parsed.Host):$port"
    }
}

function Set-TestcontainersDockerHost {
    if ($env:OS -ne 'Windows_NT' -or $env:DOCKER_HOST) {
        return
    }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        return
    }
    $dockerContext = (& docker context show 2>$null).Trim()
    if ($dockerContext -eq 'desktop-linux') {
        # Testcontainers/docker-java does not consistently consume the Docker
        # CLI context on Windows. Pass Docker Desktop's Linux-engine named pipe
        # explicitly to the Maven process.
        $env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
    }
}

function New-TestRunId {
    return ((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + ([Guid]::NewGuid().ToString('N').Substring(0, 8)))
}

function Get-TestReportDirectory {
    param([Parameter(Mandatory = $true)][string]$RunId)
    $directory = Join-Path $TestingRoot (Join-Path 'reports' $RunId)
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    return $directory
}

function Assert-TestDatabase {
    param(
        [string]$Database = $(if ($env:PULSEFLOW_TEST_MYSQL_DATABASE) { $env:PULSEFLOW_TEST_MYSQL_DATABASE } else { 'pulseflow_test' })
    )
    if ($env:PULSEFLOW_TEST_ENV -ne 'test') {
        throw 'Set PULSEFLOW_TEST_ENV=test before touching a validation database.'
    }
    if ($Database -notmatch '(?i)test') {
        throw "Refusing non-test database name: $Database"
    }
}

function Assert-TestStoreHost {
    param([Parameter(Mandatory = $true)][string]$HostName)
    if ($HostName -in @('localhost', '127.0.0.1', '::1')) { return }
    $explicitlyAllowed = $env:PULSEFLOW_TEST_ENV -eq 'test' -and
        $env:PULSEFLOW_TEST_ALLOW_NONLOCAL -eq 'true'
    if (-not $explicitlyAllowed) {
        throw "Refusing non-loopback test store target: $HostName"
    }
}
