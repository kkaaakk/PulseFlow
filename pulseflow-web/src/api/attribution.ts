import { apiRequest } from './http'
import { demoData } from './demo'
import type { AttributionView, PageResponse } from '@/types/api'

/** 按分页、活动和用户查询归因列表。 */
export const listAttributions = (page = 1, pageSize = 20, campaignId?: number, userId?: string) => apiRequest<PageResponse<AttributionView>>({ method: 'GET', url: '/attributions', params: { page, pageSize, campaignId, userId } }, () => demoData.attributions(page, pageSize, campaignId, userId))

/** 按归因 ID 读取单条记录，供详情展示使用。 */
export const getAttribution = (id: number) => apiRequest<AttributionView>({ method: 'GET', url: `/attributions/${id}` }, () => demoData.attribution(id))
