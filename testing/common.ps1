Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:TestingRoot = $PSScriptRoot
$script:RepoRoot = Split-Path -Parent $script:TestingRoot

# 优先查找 python，再回退到 Windows py 启动器；均不可用时抛出依赖缺失错误。
function Resolve-PythonCommand {
    if (Get-Command python -ErrorAction SilentlyContinue) {
        return 'python'
    }
    if (Get-Command py -ErrorAction SilentlyContinue) {
        return 'py'
    }
    throw 'Python 3.10+ is required but neither python nor py was found on PATH.'
}

# 用解析出的 Python 执行给定脚本和参数；非零退出码转换为异常，防止编排流程误判成功。
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

# 仅允许 HTTP(S) 回环地址，验证失败时在发起测试请求前抛出异常。
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

# 从 URL 解析主机和端口并探测 TCP 连通性；不可达时终止测试准备。
function Assert-TcpEndpoint {
    param([Parameter(Mandatory = $true)][string]$Url)
    $parsed = [Uri]$Url
    $port = if ($parsed.Port -gt 0) { $parsed.Port } elseif ($parsed.Scheme -eq 'https') { 443 } else { 80 }
    $reachable = Test-NetConnection -ComputerName $parsed.Host -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue
    if (-not $reachable) {
        throw "Test application is not reachable at $($parsed.Host):$port"
    }
}

# 组合 UTC 时间戳和随机 GUID 前缀，生成用于隔离报告的运行标识。
function New-TestRunId {
    return ((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + ([Guid]::NewGuid().ToString('N').Substring(0, 8)))
}

# 在 testing/reports 下按 RunId 创建或复用报告目录，并返回目录路径。
function Get-TestReportDirectory {
    param([Parameter(Mandatory = $true)][string]$RunId)
    $directory = Join-Path $TestingRoot (Join-Path 'reports' $RunId)
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    return $directory
}

# 要求显式设置测试环境且数据库名包含 test，避免验证脚本作用于普通业务库。
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

# 回环存储地址直接通过；其他地址要求同时明确启用测试环境和非本地目标许可。
function Assert-TestStoreHost {
    param([Parameter(Mandatory = $true)][string]$HostName)
    if ($HostName -in @('localhost', '127.0.0.1', '::1')) { return }
    $explicitlyAllowed = $env:PULSEFLOW_TEST_ENV -eq 'test' -and
        $env:PULSEFLOW_TEST_ALLOW_NONLOCAL -eq 'true'
    if (-not $explicitlyAllowed) {
        throw "Refusing non-loopback test store target: $HostName"
    }
}
