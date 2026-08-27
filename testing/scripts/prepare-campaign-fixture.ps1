param(
    [string]$MysqlBin = $(if ($env:PULSEFLOW_TEST_MYSQL_BIN) { $env:PULSEFLOW_TEST_MYSQL_BIN } else { 'mysql' }),
    [string]$MysqlHost = $(if ($env:PULSEFLOW_TEST_MYSQL_HOST) { $env:PULSEFLOW_TEST_MYSQL_HOST } else { '127.0.0.1' }),
    [int]$MysqlPort = $(if ($env:PULSEFLOW_TEST_MYSQL_PORT) { [int]$env:PULSEFLOW_TEST_MYSQL_PORT } else { 13306 }),
    [string]$MysqlUser = $(if ($env:PULSEFLOW_TEST_MYSQL_USER) { $env:PULSEFLOW_TEST_MYSQL_USER } else { 'test' }),
    [string]$MysqlPassword = $(if ($env:PULSEFLOW_TEST_MYSQL_PASSWORD) { $env:PULSEFLOW_TEST_MYSQL_PASSWORD } else { 'test' }),
    [string]$MysqlDatabase = $(if ($env:PULSEFLOW_TEST_MYSQL_DATABASE) { $env:PULSEFLOW_TEST_MYSQL_DATABASE } else { 'pulseflow_test' })
)

. (Join-Path $PSScriptRoot 'common.ps1')

try {
    $env:PULSEFLOW_TEST_ENV = 'test'
    Assert-TestDatabase -Database $MysqlDatabase
    if (-not (Get-Command $MysqlBin -ErrorAction SilentlyContinue) -and -not (Test-Path $MysqlBin)) {
        throw "MySQL client was not found: $MysqlBin"
    }
    $sqlPath = Join-Path $TestingRoot 'validators\mysql\prepare-campaign-fixture.sql'
    $sql = Get-Content -Raw -LiteralPath $sqlPath
    $env:MYSQL_PWD = $MysqlPassword
    & $MysqlBin '--batch' '--raw' '--protocol=tcp' '-h' $MysqlHost '-P' $MysqlPort.ToString() '-u' $MysqlUser $MysqlDatabase '--execute' $sql
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} catch {
    Write-Error $_
    exit 1
}
