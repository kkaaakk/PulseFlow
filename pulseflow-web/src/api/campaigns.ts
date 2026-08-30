import { apiRequest } from './http'
import { demoData, demoDsl } from './demo'
import type {
  CampaignDetail,
  CampaignDsl,
  CampaignListItem,
  ContentResponse,
  DraftResponse,
  InsightResponse,
  PageResponse,
  ParseResponse,
  PerformanceView,
  ReviewView,
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

export const getCampaignReview = (campaignId: number) => apiRequest<ReviewView | null>({ method: 'GET', url: `/campaigns/${campaignId}/review` }, () => demoData.review(campaignId))

export const getCampaignDeliveries = (campaignId: number, page = 1, pageSize = 10) => apiRequest<PageResponse<DeliveryListItem>>({ method: 'GET', url: `/campaigns/${campaignId}/deliveries`, params: { page, pageSize } }, () => demoData.deliveries(page, pageSize, campaignId))

export const getCampaignAttributions = (campaignId: number, page = 1, pageSize = 10) => apiRequest<PageResponse<AttributionView>>({ method: 'GET', url: `/campaigns/${campaignId}/attribution`, params: { page, pageSize } }, () => demoData.attributions(page, pageSize, campaignId))

export const parseCampaign = (text: string, timezone = 'Asia/Shanghai') => apiRequest<ParseResponse>({ method: 'POST', url: '/ai/campaigns/parse', data: { text, timezone } }, () => demoData.parse(3001))

export const updateDraft = (draftId: number, dsl: CampaignDsl) => apiRequest<DraftResponse>({ method: 'PUT', url: `/ai/campaigns/drafts/${draftId}`, data: { dsl } }, () => demoData.draft(draftId, dsl))

export const refreshPreview = (draftId: number) => apiRequest<DraftResponse>({ method: 'POST', url: `/ai/campaigns/drafts/${draftId}/refresh-preview` }, () => demoData.draft(draftId, demoDsl))

export const generateInsight = (draftId: number) => apiRequest<InsightResponse>({ method: 'POST', url: `/ai/campaigns/drafts/${draftId}/insight` }, () => demoData.insight(draftId))

export const generateContent = (draftId: number, tone = 'WARM') => apiRequest<ContentResponse>({ method: 'POST', url: `/ai/campaigns/drafts/${draftId}/contents`, data: { tone, variantCount: 3 } }, () => demoData.content(draftId))

export const confirmCampaign = (draftId: number) => apiRequest<{ campaignId: number; draftId: number; idempotent: boolean }>({ method: 'POST', url: `/campaigns/from-ai-draft/${draftId}`, data: {} }, () => demoData.confirm(2001))
