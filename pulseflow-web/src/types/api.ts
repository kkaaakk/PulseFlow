export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

export interface PageResponse<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
  totalPages: number
}

export interface DashboardSummary {
  todayEvents: number
  activeUsers: number
  runningCampaigns: number
  todayDeliveries: number
  deliverySuccessRate: number
  todayClicks: number
  todayConversions: number
  todayAttributions: number
}

export interface TrendPoint {
  label: string
  value: number
}

export interface DashboardTrends {
  events: TrendPoint[]
  deliveries: TrendPoint[]
  conversions: TrendPoint[]
}

export interface CampaignListItem {
  id: number
  name: string
  status: string
  triggerType: string
  channel: string
  audience: number
  sent: number
  clicked: number
  converted: number
  createdAt?: string
  createdBy?: number
}

export interface CampaignView {
  id: number
  name: string
  description?: string
  status: string
  triggerType: string
  channel: string
  eventTypes?: string
  cronExpression?: string
  delaySeconds?: number
  userDailyLimit?: number
  campaignWeeklyLimit?: number
  startTime?: string
  endTime?: string
  nextTriggerAt?: string
  lastTriggerAt?: string
  createdAt?: string
  updatedAt?: string
  createdBy?: number
}

export interface RuleView {
  id: number
  name: string
  type: string
  config: Record<string, unknown> | unknown
  priority?: number
  enabled: boolean
}

export interface AudienceView {
  estimatedCount: number
  dataVersion?: string
  calculationMode?: string
  warnings: string[]
}

export interface DeliverySummary {
  sent: number
  delivered: number
  clicked: number
  converted: number
  deliveryRate: number
  clickRate: number
  conversionRate: number
}

export interface AttributionSummary {
  attributedConversions: number
  model?: string
  windowHours?: number
}

export interface ReviewView {
  campaignId: number
  status: string
  model?: string
  promptVersion?: string
  errorMessage?: string
  failureCode?: string
  retryable?: boolean
  retryCount?: number
  nextRetryAt?: string
  updatedAt?: string
  review: Record<string, any>
}

export interface CampaignDetail {
  campaign: CampaignView
  rules: RuleView[]
  audience: AudienceView
  deliverySummary: DeliverySummary
  attributionSummary: AttributionSummary
  aiReview?: ReviewView | null
}

export interface PerformanceView {
  campaignId: number
  targetAudienceCount: number
  sentCount: number
  deliveredCount: number
  clickedCount: number
  convertedCount: number
  unsubscribeCount: number
  deliveryRate: number
  clickRate: number
  conversionRate: number
  unsubscribeRate: number
  calculatedAt?: string
}

export interface UserListItem {
  userId: number
  nickname?: string
  avatar?: string
  status: number
  lastActiveAt?: string
  activeDays7d: number
  spend30d: number
  tags: string[]
}

export interface UserProfileView {
  userId: number
  nickname?: string
  avatar?: string
  status: number
  createdAt?: string
  updatedAt?: string
}

export interface EventView {
  id: number
  eventId: string
  userId: number
  eventType: string
  targetId?: number
  eventTime?: string
  receivedAt?: string
  effectiveEventTime?: string
  clockSkew: boolean
  properties: Record<string, any>
}

export interface UserDetail {
  profile: UserProfileView
  realtimeMetrics: Record<string, string>
  realtimeSource: string
  realtimeAvailable: boolean
  windowMetrics: Record<string, any>
  tags: string[]
  recentEvents: PageResponse<EventView>
}

export interface DeliveryListItem {
  taskId: number
  campaignId: number
  campaignName?: string
  userId: number
  channel: string
  status: string
  dispatchStatus?: string
  retryCount: number
  triggerEventId?: string
  createdAt?: string
  sentAt?: string
}

export interface DeliveryRecordView {
  id: number
  taskId: number
  campaignId: number
  userId: number
  channel: string
  status: string
  sentAt?: string
  errorMessage?: string
}

export interface ClickView {
  id: number
  taskId?: number
  userId: number
  clickSource?: string
  clickTime?: string
  properties: Record<string, any>
}

export interface AttributionView {
  id: number
  userId: number
  campaignId?: number
  campaignName?: string
  clickEventId?: number
  targetEventId: string
  taskId?: number
  attributionModel: string
  attributionWindowHours: number
  creditedAt?: string
}

export interface DeliveryDetail {
  task: DeliveryListItem
  record?: DeliveryRecordView | null
  clicks: ClickView[]
  attributions: AttributionView[]
}

export interface SystemStatus {
  backend: string
  mysql: string
  redis: string
  kafka: string
  aiMode: string
  piiGuardrail: string
}

export interface AuthSession {
  operatorId: number
  role: string
  displayName: string
  tokenName?: string
  tokenValue?: string | null
  loginId?: string
}

export interface AudienceEstimate {
  count: number
  dataVersion?: string
  calculationMode?: string
  warnings: string[]
}

export interface AudienceCondition {
  field: string
  operator: string
  valueType: string
  value: string | number | boolean
}

export interface CampaignDsl {
  schemaVersion: number
  campaignName: string
  objective: string
  audience: {
    logic: 'AND' | 'OR'
    conditions: AudienceCondition[]
  }
  channel: string
  schedule: {
    type: string
    sendAt: string
    timezone: string
  }
  frequencyCap: {
    maxTimes: number
    windowHours: number
  }
  promotionFacts: Array<Record<string, any>>
}

export interface ParseResponse {
  requestId: string
  draftId: number
  status: string
  dsl: CampaignDsl
  estimatedAudience?: AudienceEstimate
  missingFields: string[]
  warnings: string[]
}

export interface DraftResponse {
  draftId: number
  status: string
  dsl: CampaignDsl
  errors?: string[]
  warnings: string[]
  estimatedAudience?: AudienceEstimate
}

export interface InsightResponse {
  requestId: string
  draftId: number
  metrics: Record<string, any>
  insight: Record<string, any>
  dataQuality: {
    baselineType: string
    proxyMetrics: string[]
    unavailableMetrics: string[]
  }
}

export interface ContentVariant {
  type?: string
  variant?: string
  title: string
  body: string
  strategy?: string
  tone?: string
}

export interface ContentResponse {
  requestId: string
  draftId: number
  content: ContentVariant[] | { variants: ContentVariant[] }
}
