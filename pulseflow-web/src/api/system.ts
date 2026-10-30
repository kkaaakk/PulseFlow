import { apiRequest } from './http'
import { demoData } from './demo'
import type { SystemStatus } from '@/types/api'

/**
 * 读取控制台各服务状态，演示模式返回固定状态样例。
 */
export const getSystemStatus = () => apiRequest<SystemStatus>({ method: 'GET', url: '/system/status' }, demoData.system)
