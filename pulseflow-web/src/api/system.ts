import { apiRequest } from './http'
import { demoData } from './demo'
import type { SystemStatus } from '@/types/api'

/** 读取后端及存储、消息依赖的健康状态。 */
export const getSystemStatus = () => apiRequest<SystemStatus>({ method: 'GET', url: '/system/status' }, demoData.system)
