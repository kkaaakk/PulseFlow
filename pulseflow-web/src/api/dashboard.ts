import { apiRequest } from './http'
import { demoData } from './demo'
import type { DashboardSummary, DashboardTrends } from '@/types/api'

/**
 * 读取今日运营 KPI 汇总，演示模式返回固定概览样例。
 */
export const getDashboardSummary = () => apiRequest<DashboardSummary>({ method: 'GET', url: '/dashboard/summary' }, demoData.dashboardSummary)

/**
 * 读取总览趋势，days 默认七天；演示模式返回预设时间序列。
 */
export const getDashboardTrends = (days = 7) => apiRequest<DashboardTrends>({ method: 'GET', url: '/dashboard/trends', params: { days } }, demoData.dashboardTrends)
