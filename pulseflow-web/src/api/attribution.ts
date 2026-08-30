import { apiRequest } from './http'
import { demoData } from './demo'
import type { AttributionView, PageResponse } from '@/types/api'

export const listAttributions = (page = 1, pageSize = 20, campaignId?: number, userId?: string) => apiRequest<PageResponse<AttributionView>>({ method: 'GET', url: '/attributions', params: { page, pageSize, campaignId, userId } }, () => demoData.attributions(page, pageSize, campaignId, userId))

export const getAttribution = (id: number) => apiRequest<AttributionView>({ method: 'GET', url: `/attributions/${id}` }, () => demoData.attribution(id))
