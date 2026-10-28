Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:TestingRoot = $PSScriptRoot
$script:RepoRoot = Split-Path -Parent $script:TestingRoot

# 查找可用的 Python 启动命令，均不存在时明确报告依赖缺失。
function Resolve-PythonCommand {
    if (Get-Command python -ErrorAction SilentlyContinue) {
        return 'python'
    }
    if (Get-Command py -ErrorAction SilentlyContinue) {
        return 'py'
    }
    throw 'Python 3.10+ is required but neither python nor py was found on PATH.'
}

# 执行指定 Python 脚本并转发参数，非零退出码转为 PowerShell 失败。
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

# 要求测试 URL 使用本机 HTTP/HTTPS，阻止误向外部目标发送测试流量。
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

# 检查目标主机端口连通性，无法连接时在测试开始前失败。
function Assert-TcpEndpoint {
    param([Parameter(Mandatory = $true)][string]$Url)
    $parsed = [Uri]$Url
    $port = if ($parsed.Port -gt 0) { $parsed.Port } elseif ($parsed.Scheme -eq 'https') { 443 } else { 80 }
    $reachable = Test-NetConnection -ComputerName $parsed.Host -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue
    if (-not $reachable) {
        throw "Test application is not reachable at $($parsed.Host):$port"
    }
}

# 组合 UTC 时间和随机后缀生成独立运行 ID，避免报告目录冲突。
function New-TestRunId {
    return ((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + ([Guid]::NewGuid().ToString('N').Substring(0, 8)))
}

# 创建当前运行的报告目录并返回绝对路径。
function Get-TestReportDirectory {
    param([Parameter(Mandatory = $true)][string]$RunId)
    $directory = Join-Path $TestingRoot (Join-Path 'reports' $RunId)
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    return $directory
}

# 要求显式测试环境和含 test 的数据库名，避免清理或验证误触正式库。
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

# 默认只允许本机存储；非本机目标须通过明确的测试环境开关放行。
function Assert-TestStoreHost {
    param([Parameter(Mandatory = $true)][string]$HostName)
    if ($HostName -in @('localhost', '127.0.0.1', '::1')) { return }
    $explicitlyAllowed = $env:PULSEFLOW_TEST_ENV -eq 'test' -and
        $env:PULSEFLOW_TEST_ALLOW_NONLOCAL -eq 'true'
    if (-not $explicitlyAllowed) {
        throw "Refusing non-loopback test store target: $HostName"
    }
}
