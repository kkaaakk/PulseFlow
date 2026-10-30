import { apiRequest } from './http'
import { demoData } from './demo'
import type { AttributionView, PageResponse } from '@/types/api'

/**
 * 按页码、每页条数及可选 Campaign 和用户筛选归因记录；演示模式使用同形本地分页数据。
 */
export const listAttributions = (page = 1, pageSize = 20, campaignId?: number, userId?: string) => apiRequest<PageResponse<AttributionView>>({ method: 'GET', url: '/attributions', params: { page, pageSize, campaignId, userId } }, () => demoData.attributions(page, pageSize, campaignId, userId))

/**
 * 按归因主键读取详情，演示模式从固定样例中查找。
 */
export const getAttribution = (id: number) => apiRequest<AttributionView>({ method: 'GET', url: `/attributions/${id}` }, () => demoData.attribution(id))
