import { apiRequest } from './http'
import { demoData } from './demo'
import type { DashboardSummary, DashboardTrends } from '@/types/api'

/** 读取仪表盘业务指标，演示模式使用固定 KPI 夹具。 */
export const getDashboardSummary = () => apiRequest<DashboardSummary>({ method: 'GET', url: '/dashboard/summary' }, demoData.dashboardSummary)

/** 按天数读取事件、触达和转化趋势，供仪表盘图表使用。 */
export const getDashboardTrends = (days = 7) => apiRequest<DashboardTrends>({ method: 'GET', url: '/dashboard/trends', params: { days } }, demoData.dashboardTrends)
