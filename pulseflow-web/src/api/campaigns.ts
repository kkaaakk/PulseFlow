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

export const listCampaigns = (query: CampaignListQuery = {}) => apiRequest<PageResponse<CampaignListItem>>({ method: 'GET', url: '/campaigns', params: query }, () => demoData.campaigns(query.page ?? 1, query.pageSize ?? 10, query.keyword, query.status))

export const getCampaign = (campaignId: number) => apiRequest<CampaignDetail>({ method: 'GET', url: `/campaigns/${campaignId}` }, () => demoData.campaignDetail(campaignId))

export const getCampaignPerformance = (campaignId: number) => apiRequest<PerformanceView>({ method: 'GET', url: `/campaigns/${campaignId}/performance` }, () => demoData.performance(campaignId))

export const getCampaignDeliveryTrend = (campaignId: number, days = 7) => apiRequest<TrendPoint[]>({ method: 'GET', url: `/campaigns/${campaignId}/performance/trend`, params: { days } }, () => demoData.campaignTrend(campaignId))


export const getCampaignDeliveries = (campaignId: number, page = 1, pageSize = 10) => apiRequest<PageResponse<DeliveryListItem>>({ method: 'GET', url: `/campaigns/${campaignId}/deliveries`, params: { page, pageSize } }, () => demoData.deliveries(page, pageSize, campaignId))

export const getCampaignAttributions = (campaignId: number, page = 1, pageSize = 10) => apiRequest<PageResponse<AttributionView>>({ method: 'GET', url: `/campaigns/${campaignId}/attribution`, params: { page, pageSize } }, () => demoData.attributions(page, pageSize, campaignId))
