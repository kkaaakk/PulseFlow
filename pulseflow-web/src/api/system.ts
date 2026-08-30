import { apiRequest } from './http'
import { demoData } from './demo'
import type { SystemStatus } from '@/types/api'

export const getSystemStatus = () => apiRequest<SystemStatus>({ method: 'GET', url: '/system/status' }, demoData.system)
