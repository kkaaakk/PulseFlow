import { apiRequest } from './http'
import { demoData } from './demo'
import type { DeliveryDetail, DeliveryListItem, PageResponse } from '@/types/api'

export const listDeliveries = (page = 1, pageSize = 20, campaignId?: number, userId?: string, status?: string) => apiRequest<PageResponse<DeliveryListItem>>({ method: 'GET', url: '/deliveries', params: { page, pageSize, campaignId, userId, status } }, () => demoData.deliveries(page, pageSize, campaignId, userId, status))

export const getDelivery = (taskId: number) => apiRequest<DeliveryDetail>({ method: 'GET', url: `/deliveries/${taskId}` }, () => demoData.delivery(taskId))
