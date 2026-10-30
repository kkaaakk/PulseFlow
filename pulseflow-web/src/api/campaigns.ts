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

/**
 * 按 query 中的分页、关键词、状态和创建者查询 Campaign；演示模式支持其本地已实现的筛选条件。
 */
export const listCampaigns = (query: CampaignListQuery = {}) => apiRequest<PageResponse<CampaignListItem>>({ method: 'GET', url: '/campaigns', params: query }, () => demoData.campaigns(query.page ?? 1, query.pageSize ?? 10, query.keyword, query.status))

/**
 * 按 campaignId 获取规则、人群、触达与归因组合详情。
 */
export const getCampaign = (campaignId: number) => apiRequest<CampaignDetail>({ method: 'GET', url: `/campaigns/${campaignId}` }, () => demoData.campaignDetail(campaignId))

/**
 * 读取指定 Campaign 的绩效视图，不在浏览器重算后端统计口径。
 */
export const getCampaignPerformance = (campaignId: number) => apiRequest<PerformanceView>({ method: 'GET', url: `/campaigns/${campaignId}/performance` }, () => demoData.performance(campaignId))

/**
 * 查询指定 Campaign 最近 days 天的触达日趋势，默认七天；演示模式使用固定七点序列。
 */
export const getCampaignDeliveryTrend = (campaignId: number, days = 7) => apiRequest<TrendPoint[]>({ method: 'GET', url: `/campaigns/${campaignId}/performance/trend`, params: { days } }, () => demoData.campaignTrend(campaignId))


/**
 * 分页读取指定 Campaign 的触达任务，默认每页十条。
 */
export const getCampaignDeliveries = (campaignId: number, page = 1, pageSize = 10) => apiRequest<PageResponse<DeliveryListItem>>({ method: 'GET', url: `/campaigns/${campaignId}/deliveries`, params: { page, pageSize } }, () => demoData.deliveries(page, pageSize, campaignId))

/**
 * 分页读取指定 Campaign 的归因记录，默认每页十条。
 */
export const getCampaignAttributions = (campaignId: number, page = 1, pageSize = 10) => apiRequest<PageResponse<AttributionView>>({ method: 'GET', url: `/campaigns/${campaignId}/attribution`, params: { page, pageSize } }, () => demoData.attributions(page, pageSize, campaignId))
