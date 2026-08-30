param(
    [ValidateSet('small', 'medium', 'large')][string]$Scale = 'small',
    [int]$Seed = 20260827,
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$Concurrency = 8,
    [int]$WaitSeconds = 120,
    [int]$CampaignWaitSeconds = 600,
    [switch]$PrepareDependencies,
    [switch]$RunMaven,
    [switch]$RebaseEventTime,
    [switch]$JobsTriggered,
    [ValidateSet('none', 'smoke', 'load', 'stress')][string]$Performance = 'none',
    [switch]$AllowStress
)

. (Join-Path $PSScriptRoot 'common.ps1')

function Stage-Status {
    param([int]$ExitCode)
    if ($ExitCode -eq 0) { return 'PASS' }
    if ($ExitCode -eq 2) { return 'NOT_RUN' }
    return 'FAIL'
}

$script:RunAllStatusLabels = @{
    PASS = '✅ PASS（通过）'
    FAIL = '❌ FAIL（失败）'
    NOT_RUN = '⚠️ NOT_RUN（未执行）'
}

$script:RunAllScenarioLabels = @{
    fixture = '测试预置数据'
    normal = '正常业务事件'
    duplicate = '重复事件 / 幂等'
    'out-of-order' = '乱序事件'
    late = '迟到事件'
    invalid = '非法请求'
    'hot-user' = '热点用户并发'
    concurrency = '并发正确性'
    campaign = 'Campaign 营销 / 频控 / 归因'
}

$script:RunAllCheckLabels = @{
    'mysql-unique-events' = '事件落库数量'
    'mysql-duplicate-rows' = 'eventId 唯一性'
    'mysql-hourly-metrics' = '小时指标聚合'
    'mysql-daily-metrics' = '日指标聚合'
    'profile-window-metrics' = '用户窗口指标'
    'profile-user-tags' = '用户标签计算'
    'campaign-delivery-tasks' = '营销投放任务'
    'campaign-delivery-records' = '营销投放记录'
    'campaign-channel-records' = '渠道发送记录'
    'campaign-frequency-reservations' = '营销频控配额预留'
    'campaign-execution' = '营销活动执行记录'
    'campaign-performance-summary' = '营销活动效果汇总'
    'campaign-ai-review' = '营销活动 AI 审核'
    'attribution-click-event' = '归因点击事件'
    'attribution-last-touch' = '末次点击归因'
    'attribution-task-state' = '归因任务状态'
    'redis-processed-flags' = 'Redis 幂等处理标记'
    'redis-processed-ttl' = 'Redis 幂等标记 TTL'
    'redis-realtime-profile' = 'Redis 实时画像键'
    'redis-realtime-values' = 'Redis 实时画像值'
    'redis-daily-values' = 'Redis 日实时指标'
    'redis-cart-values' = 'Redis 购物车状态'
    'replay-request' = '业务重放请求'
}

function Get-RunAllStatusLabel {
    param([string]$Status, [switch]$Partial)
    if ($Partial -and $Status -eq 'NOT_RUN') { return '⚠️ NOT_RUN（部分未执行）' }
    if ($script:RunAllStatusLabels.ContainsKey($Status)) { return $script:RunAllStatusLabels[$Status] }
    return $Status
}

function Get-RunAllScenarioLabel {
    param([string]$Scenario)
    if ($script:RunAllScenarioLabels.ContainsKey($Scenario)) { return $script:RunAllScenarioLabels[$Scenario] }
    if ($Scenario) { return $Scenario }
    return '未知场景'
}

function Get-RunAllCheckLabel {
    param([string]$CheckId, [string]$Description = '')
    if ($script:RunAllCheckLabels.ContainsKey($CheckId)) { return $script:RunAllCheckLabels[$CheckId] }
    if ($Description) { return $Description }
    if ($CheckId) { return $CheckId }
    return '未命名校验'
}

function Get-RunAllJsonProperty {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($property) { return $property.Value }
    return $null
}

function Get-RunAllK6Metrics {
    param([object]$PerformanceReport)
    if ($null -eq $PerformanceReport) { return $null }
    $embeddedMetrics = Get-RunAllJsonProperty -Object $PerformanceReport -Name 'metrics'
    if ($null -ne $embeddedMetrics) { return $embeddedMetrics }
    $summaryPath = [string](Get-RunAllJsonProperty -Object $PerformanceReport -Name 'summary')
    if (-not $summaryPath -or -not (Test-Path -LiteralPath $summaryPath)) { return $null }
    try {
        $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
        $metrics = $summary.metrics
        if ($null -eq $metrics) { return $null }
        return [pscustomobject][ordered]@{
            requests = Get-RunAllJsonProperty -Object (Get-RunAllJsonProperty -Object $metrics -Name 'http_reqs') -Name 'count'
            rps = Get-RunAllJsonProperty -Object (Get-RunAllJsonProperty -Object $metrics -Name 'http_reqs') -Name 'rate'
            avgMs = Get-RunAllJsonProperty -Object (Get-RunAllJsonProperty -Object $metrics -Name 'http_req_duration') -Name 'avg'
            p95Ms = Get-RunAllJsonProperty -Object (Get-RunAllJsonProperty -Object $metrics -Name 'http_req_duration') -Name 'p(95)'
            p99Ms = Get-RunAllJsonProperty -Object (Get-RunAllJsonProperty -Object $metrics -Name 'http_req_duration') -Name 'p(99)'
            errorRate = Get-RunAllJsonProperty -Object (Get-RunAllJsonProperty -Object $metrics -Name 'http_req_failed') -Name 'value'
            apiErrorRate = Get-RunAllJsonProperty -Object (Get-RunAllJsonProperty -Object $metrics -Name 'pulseflow_api_failures') -Name 'value'
            vus = Get-RunAllJsonProperty -Object (Get-RunAllJsonProperty -Object $metrics -Name 'vus_max') -Name 'value'
        }
    } catch {
        return $null
    }
}

function Format-RunAllMetric {
    param([object]$Value, [string]$Unit = '', [switch]$Integer)
    if ($null -eq $Value) { return '—' }
    try {
        if ($Integer) { return ('{0:N0}{1}' -f [double]$Value, $Unit) }
        return ('{0:N2}{1}' -f [double]$Value, $Unit)
    } catch {
        return ([string]$Value) + $Unit
    }
}

function Format-RunAllPercent {
    param([object]$Value)
    if ($null -eq $Value) { return '—' }
    try { return '{0:P2}' -f [double]$Value } catch { return ([string]$Value) }
}

function Get-RunAllShortValue {
    param([object]$Value, [string]$Fallback = '—')
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) { return $Fallback }
    $text = ([string]$Value).Replace("`r", ' ').Replace("`n", ' ').Replace('|', '\|')
    if ($text.Length -gt 240) { return $text.Substring(0, 239) + '…' }
    return $text
}

function Get-RunAllCheckCountText {
    param([object]$Summary)
    if ($null -eq $Summary -or $null -eq $Summary.checkCounts) { return '—' }
    $total = [int]$Summary.checkCounts.PASS + [int]$Summary.checkCounts.FAIL + [int]$Summary.checkCounts.NOT_RUN
    if ($total -eq 0) { return '适用校验 0' }
    return "通过 $($Summary.checkCounts.PASS) / 失败 $($Summary.checkCounts.FAIL) / 未执行 $($Summary.checkCounts.NOT_RUN)"
}

function Write-RunAllMarkdown {
    param(
        [Parameter(Mandatory = $true)][string]$RunRoot,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][string]$OverallStatus,
        [Parameter(Mandatory = $true)][string]$FunctionalStatus,
        [Parameter(Mandatory = $true)][string]$PerformanceStatus,
        [Parameter(Mandatory = $true)][string]$PerformanceScenario
    )
    try {
        $functionalPath = Join-Path $RunRoot 'functional\functional-report.json'
        $performancePath = Join-Path $RunRoot 'performance\performance-report.json'
        $functional = if (Test-Path -LiteralPath $functionalPath) {
            Get-Content -Raw -LiteralPath $functionalPath | ConvertFrom-Json
        } else { $null }
        $performance = if (Test-Path -LiteralPath $performancePath) {
            Get-Content -Raw -LiteralPath $performancePath | ConvertFrom-Json
        } else { $null }
        $functionalDetails = Get-RunAllJsonProperty -Object $functional -Name 'details'
        $details = if ($null -ne $functionalDetails) { @($functionalDetails) } else { @() }
        $passScenarios = @($details | Where-Object { $_.status -eq 'PASS' }).Count
        $failScenarios = @($details | Where-Object { $_.status -eq 'FAIL' }).Count
        $notRunScenarios = @($details | Where-Object { $_.status -eq 'NOT_RUN' }).Count
        $failureRows = @()
        $notRunRows = @()
        $scenarioRows = @()

        foreach ($detail in $details) {
            $summary = $null
            $summaryPath = Join-Path ([string]$detail.reportDir) 'summary.json'
            if (Test-Path -LiteralPath $summaryPath) {
                try { $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json } catch { $summary = $null }
            }
            if ($summary) {
                foreach ($check in @($summary.checks)) {
                    if ($check.status -eq 'FAIL') {
                        $failureRows += [pscustomobject]@{
                            Scenario = $detail.scenario
                            Name = Get-RunAllCheckLabel -CheckId $check.checkId -Description $check.description
                            Module = $check.module
                            Reason = Get-RunAllShortValue -Value ($check.reason) -Fallback '断言不匹配'
                            CheckId = $check.checkId
                        }
                    } elseif ($check.status -eq 'NOT_RUN') {
                        $notRunRows += [pscustomobject]@{
                            Scenario = $detail.scenario
                            Name = Get-RunAllCheckLabel -CheckId $check.checkId -Description $check.description
                            Module = $check.module
                            Reason = Get-RunAllShortValue -Value ($check.reason) -Fallback '未满足执行条件'
                            CheckId = $check.checkId
                        }
                    }
                }
            }
            $replayFailuresPath = Join-Path ([string]$detail.reportDir) 'replay-failures.json'
            if (Test-Path -LiteralPath $replayFailuresPath) {
                try {
                    $replayFailures = Get-Content -Raw -LiteralPath $replayFailuresPath | ConvertFrom-Json
                    foreach ($failure in @($replayFailures)) {
                        $failureRows += [pscustomobject]@{
                            Scenario = $detail.scenario
                            Name = '业务重放请求'
                            Module = 'Replay（业务重放）'
                            Reason = Get-RunAllShortValue -Value ($failure.exceptionOrLog) -Fallback '请求失败'
                            CheckId = if ($failure.checkId) { $failure.checkId } else { 'replay-request' }
                        }
                    }
                } catch { }
            }
            $scenarioRows += [pscustomobject]@{
                Scenario = $detail.scenario
                Label = Get-RunAllScenarioLabel -Scenario $detail.scenario
                Status = Get-RunAllStatusLabel -Status $detail.status -Partial
                Replay = Get-RunAllStatusLabel -Status $detail.replayStatus
                Validator = Get-RunAllStatusLabel -Status $detail.validationStatus
                Checks = Get-RunAllCheckCountText -Summary $summary
                Concurrency = $detail.concurrency
            }
        }

        $lines = @(
            '# PulseFlow 测试报告',
            '',
            '## 总体结论',
            '',
            "功能验收：$(Get-RunAllStatusLabel -Status $FunctionalStatus)",
            "性能验收：$(Get-RunAllStatusLabel -Status $PerformanceStatus)",
            "总体结果：$(Get-RunAllStatusLabel -Status $OverallStatus)",
            '',
            "运行 ID：$([char]96)$RunId$([char]96)",
            '',
            '## 当前失败项',
            ''
        )
        if ($failureRows.Count -gt 0) {
            $lines += '| 场景 | 校验内容 | 模块 | 原因 | 技术标识 |'
            $lines += '|---|---|---|---|---|'
            foreach ($row in $failureRows) {
                $lines += "| $(Get-RunAllScenarioLabel -Scenario $row.Scenario) | $($row.Name) | $(Get-RunAllModuleLabel -Module $row.Module) | $($row.Reason) | ``$($row.CheckId)`` |"
            }
        } else {
            $lines += '无。'
        }

        $lines += @('', '## 当前未执行项', '')
        if ($notRunRows.Count -gt 0) {
            $lines += '| 场景 | 校验内容 | 模块 | 原因 | 技术标识 |'
            $lines += '|---|---|---|---|---|'
            foreach ($row in $notRunRows) {
                $lines += "| $(Get-RunAllScenarioLabel -Scenario $row.Scenario) | $($row.Name) | $(Get-RunAllModuleLabel -Module $row.Module) | $($row.Reason) | ``$($row.CheckId)`` |"
            }
        } else {
            $lines += '无。'
        }

        $lines += @('', '## 功能验收', '', '### 场景结果', '', '| 场景 | 中文说明 | 结果 | Replay | Validator | 校验统计 | 并发 |', '|---|---|---|---|---|---|---:|')
        foreach ($row in $scenarioRows) {
            $lines += "| [$($row.Scenario)](functional/$($row.Scenario)/summary.md) | $($row.Label) | $($row.Status) | $($row.Replay) | $($row.Validator) | $($row.Checks) | $($row.Concurrency) |"
        }
        $lines += @('', '功能详情：`functional/functional-report.md`')

        $lines += @('', '## 性能验收', '')
        if ($PerformanceScenario -eq 'none' -or $null -eq $performance) {
            $lines += '本次未选择 Performance 场景。'
        } elseif ($PerformanceStatus -eq 'NOT_RUN') {
            $reason = if ($performance.reason) { $performance.reason } else { '未满足 Performance 执行条件。' }
            $lines += "$(Get-RunAllStatusLabel -Status 'NOT_RUN')：$reason"
        } else {
            $metrics = Get-RunAllK6Metrics -PerformanceReport $performance
            $apiErrorRate = if ($metrics) { $metrics.apiErrorRate } else { $null }
            $errorRate = if ($null -ne $apiErrorRate) { $apiErrorRate } elseif ($metrics) { $metrics.errorRate } else { $null }
            $lines += "测试类型：$($PerformanceScenario.ToUpperInvariant())"
            $lines += ''
            $lines += '| 指标 | 结果 |'
            $lines += '|---|---:|'
            $lines += "| 请求总数 | $(Format-RunAllMetric -Value $(if ($metrics) { $metrics.requests }) -Integer) |"
            $lines += "| QPS / RPS | $(Format-RunAllMetric -Value $(if ($metrics) { $metrics.rps })) |"
            $lines += "| 平均响应时间 | $(Format-RunAllMetric -Value $(if ($metrics) { $metrics.avgMs }) -Unit ' ms') |"
            $lines += "| P95 | $(Format-RunAllMetric -Value $(if ($metrics) { $metrics.p95Ms }) -Unit ' ms') |"
            $lines += "| P99 | $(Format-RunAllMetric -Value $(if ($metrics) { $metrics.p99Ms }) -Unit ' ms') |"
            $lines += "| 错误率 | $(Format-RunAllPercent -Value $errorRate) |"
            if ($null -eq $metrics -or $null -eq $metrics.p99Ms) {
                $lines += ''
                $lines += '注：k6 Summary 未提供 P99，未进行推算。'
            }
            $lines += ''
            $lines += '性能详情：`performance/performance-report.md`'
        }
        $lines += @('', '## 报告说明', '', 'JSON 文件用于机器读取；Markdown 文件用于人工阅读。需要深入 Debug 时，再查看场景目录中的 `summary.json`、`failures.json` 和 `k6-summary.json`。')
        $lines | Set-Content -LiteralPath (Join-Path $RunRoot 'run-all-report.md') -Encoding UTF8
    } catch {
        Write-Warning "run-all Markdown report generation failed; JSON reports are preserved: $($_.Exception.Message)"
    }
}

function Get-RunAllModuleLabel {
    param([string]$Module)
    switch ($Module) {
        'MySQL' { return 'MySQL（数据库）' }
        'Redis' { return 'Redis（缓存）' }
        'Profile' { return 'Profile（用户画像）' }
        'Campaign' { return 'Campaign（营销活动）' }
        'Attribution' { return 'Attribution（归因）' }
        'Compensation' { return 'Compensation（补偿）' }
        'Replay' { return 'Replay（业务重放）' }
        'AI' { return 'AI（智能审核）' }
        default {
            if ($Module) { return $Module }
            return '未知模块'
        }
    }
}

try {
    $runId = New-TestRunId
    $runRoot = Get-TestReportDirectory -RunId $runId
    $functionalDir = Join-Path $runRoot 'functional'
    & (Join-Path $PSScriptRoot 'functional\run.ps1') `
        -Scale $Scale -Seed $Seed -BaseUrl $BaseUrl -Concurrency $Concurrency `
        -WaitSeconds $WaitSeconds -CampaignWaitSeconds $CampaignWaitSeconds `
        -RunId $runId -ReportDir $functionalDir `
        -PrepareDependencies:$PrepareDependencies -RunMaven:$RunMaven `
        -RebaseEventTime:$RebaseEventTime -JobsTriggered:$JobsTriggered
    $functionalExit = $LASTEXITCODE

    $performanceStatus = 'NOT_RUN'
    $performanceExit = 2
    if ($Performance -ne 'none') {
        $performanceDir = Join-Path $runRoot 'performance'
        & (Join-Path $PSScriptRoot 'performance\run.ps1') `
            -Scenario $Performance -BaseUrl $BaseUrl -ReportDir $performanceDir `
            -AllowStress:$AllowStress
        $performanceExit = $LASTEXITCODE
        $performanceStatus = Stage-Status -ExitCode $performanceExit
    }

    $functionalStatus = Stage-Status -ExitCode $functionalExit
    $overallStatus = if ($functionalStatus -eq 'FAIL' -or $performanceStatus -eq 'FAIL') { 'FAIL' }
    elseif ($functionalStatus -eq 'NOT_RUN' -or $performanceStatus -eq 'NOT_RUN') {
        if ($Performance -eq 'none' -and $functionalStatus -eq 'PASS') { 'PASS' } else { 'NOT_RUN' }
    } else { 'PASS' }
    $report = [ordered]@{
        status = $overallStatus
        runId = $runId
        functionalStatus = $functionalStatus
        performanceStatus = $performanceStatus
        performanceScenario = $Performance
        functionalReport = Join-Path $functionalDir 'functional-report.json'
        performanceReport = if ($Performance -eq 'none') { $null } else { Join-Path (Join-Path $runRoot 'performance') 'performance-report.json' }
        exitCodes = [ordered]@{ functional = $functionalExit; performance = $performanceExit }
    }
    $report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $runRoot 'run-all-report.json') -Encoding UTF8
    Write-RunAllMarkdown -RunRoot $runRoot -RunId $runId -OverallStatus $overallStatus `
        -FunctionalStatus $functionalStatus -PerformanceStatus $performanceStatus -PerformanceScenario $Performance
    Write-Host "run-all ${overallStatus}: functional=${functionalStatus} performance=${performanceStatus} report=${runRoot}"
    if ($overallStatus -eq 'PASS') { exit 0 }
    if ($overallStatus -eq 'NOT_RUN') { exit 2 }
    exit 1
} catch {
    Write-Error $_
    exit 1
}
