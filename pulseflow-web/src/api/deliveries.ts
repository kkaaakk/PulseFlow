import { apiRequest } from './http'
import { demoData } from './demo'
import type { DeliveryDetail, DeliveryListItem, PageResponse } from '@/types/api'

/** 按分页、活动、用户和状态查询触达任务。 */
export const listDeliveries = (page = 1, pageSize = 20, campaignId?: number, userId?: string, status?: string) => apiRequest<PageResponse<DeliveryListItem>>({ method: 'GET', url: '/deliveries', params: { page, pageSize, campaignId, userId, status } }, () => demoData.deliveries(page, pageSize, campaignId, userId, status))

/** 按任务 ID 读取触达及关联业务事实详情。 */
export const getDelivery = (taskId: number) => apiRequest<DeliveryDetail>({ method: 'GET', url: `/deliveries/${taskId}` }, () => demoData.delivery(taskId))
