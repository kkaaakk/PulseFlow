import { apiRequest } from './http'
import { demoData } from './demo'
import type { DeliveryDetail, DeliveryListItem, PageResponse } from '@/types/api'

/**
 * 按分页、Campaign、用户及任务状态筛选触达任务。
 */
export const listDeliveries = (page = 1, pageSize = 20, campaignId?: number, userId?: string, status?: string) => apiRequest<PageResponse<DeliveryListItem>>({ method: 'GET', url: '/deliveries', params: { page, pageSize, campaignId, userId, status } }, () => demoData.deliveries(page, pageSize, campaignId, userId, status))

/**
 * 按 taskId 获取发送记录、点击和归因组合详情。
 */
export const getDelivery = (taskId: number) => apiRequest<DeliveryDetail>({ method: 'GET', url: `/deliveries/${taskId}` }, () => demoData.delivery(taskId))
