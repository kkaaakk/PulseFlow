import { apiRequest } from './http'
import { demoData } from './demo'
import type {
  CampaignDetail,
  CampaignListItem,
  PageResponse,
  PerformanceView,
  DeliveryListItem,
 AttributionView,
  TrendPoint,
} from '@/types/api'

export interface CampaignListQuery {
  page?: number
  pageSize?: number
  keyword?: string
  status?: string
  createdBy?: number
}

/** 按分页与筛选对象查询活动列表，缺省参数由接口和演示分页器统一处理。 */
export const listCampaigns = (query: CampaignListQuery = {}) => apiRequest<PageResponse<CampaignListItem>>({ method: 'GET', url: '/campaigns', params: query }, () => demoData.campaigns(query.page ?? 1, query.pageSize ?? 10, query.keyword, query.status))

/** 按活动 ID 读取规则、人群、触达与归因摘要。 */
export const getCampaign = (campaignId: number) => apiRequest<CampaignDetail>({ method: 'GET', url: `/campaigns/${campaignId}` }, () => demoData.campaignDetail(campaignId))

/** 读取指定活动的绩效计数与比率。 */
export const getCampaignPerformance = (campaignId: number) => apiRequest<PerformanceView>({ method: 'GET', url: `/campaigns/${campaignId}/performance` }, () => demoData.performance(campaignId))

/** 读取指定活动最近若干天的触达趋势。 */
export const getCampaignDeliveryTrend = (campaignId: number, days = 7) => apiRequest<TrendPoint[]>({ method: 'GET', url: `/campaigns/${campaignId}/performance/trend`, params: { days } }, () => demoData.campaignTrend(campaignId))


/** 在活动范围内分页读取触达任务。 */
export const getCampaignDeliveries = (campaignId: number, page = 1, pageSize = 10) => apiRequest<PageResponse<DeliveryListItem>>({ method: 'GET', url: `/campaigns/${campaignId}/deliveries`, params: { page, pageSize } }, () => demoData.deliveries(page, pageSize, campaignId))

/** 在活动范围内分页读取归因记录。 */
export const getCampaignAttributions = (campaignId: number, page = 1, pageSize = 10) => apiRequest<PageResponse<AttributionView>>({ method: 'GET', url: `/campaigns/${campaignId}/attribution`, params: { page, pageSize } }, () => demoData.attributions(page, pageSize, campaignId))
