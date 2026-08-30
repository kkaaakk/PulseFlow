import { apiRequest } from './http'
import { demoData } from './demo'
import type { DashboardSummary, DashboardTrends } from '@/types/api'

export const getDashboardSummary = () => apiRequest<DashboardSummary>({ method: 'GET', url: '/dashboard/summary' }, demoData.dashboardSummary)

export const getDashboardTrends = (days = 7) => apiRequest<DashboardTrends>({ method: 'GET', url: '/dashboard/trends', params: { days } }, demoData.dashboardTrends)
