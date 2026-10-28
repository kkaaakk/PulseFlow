// 控制台 API 的统一响应、分页及各业务页面视图契约。
/** 统一后端响应封装，业务 code 为成功时才可使用 data。 */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

/** 统一分页结果，包含条目、页码、每页数量、总数和总页数。 */
export interface PageResponse<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
  totalPages: number
}

/** 仪表盘当天业务指标和比率摘要。 */
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

/** 趋势图中的一个时间标签及对应数值。 */
export interface TrendPoint {
  label: string
  value: number
}

/** 事件、触达及转化的趋势序列集合。 */
export interface DashboardTrends {
  events: TrendPoint[]
  deliveries: TrendPoint[]
  conversions: TrendPoint[]
}

/** 活动列表中的配置与绩效摘要。 */
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

/** 控制台活动基础配置视图。 */
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

/** 活动规则的展示视图，包含解析后的配置。 */
export interface RuleView {
  id: number
  name: string
  type: string
  config: Record<string, unknown> | unknown
  priority?: number
  enabled: boolean
}

/** 活动人群预估视图，保留人数与版本口径。 */
export interface AudienceView {
  estimatedCount: number
  dataVersion?: string
  calculationMode?: string
  warnings: string[]
}

/** 活动触达计数及状态摘要。 */
export interface DeliverySummary {
  sent: number
  delivered: number
  clicked: number
  converted: number
  deliveryRate: number
  clickRate: number
  conversionRate: number
}

/** 活动归因计数与相关业务摘要。 */
export interface AttributionSummary {
  attributedConversions: number
  model?: string
  windowHours?: number
}

/** 活动详情组合，包含基础配置、规则、人群及触达归因摘要。 */
export interface CampaignDetail {
  campaign: CampaignView
  rules: RuleView[]
  audience: AudienceView
  deliverySummary: DeliverySummary
  attributionSummary: AttributionSummary
}

/** 活动绩效展示数据，统一发送、点击和转化口径。 */
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

/** 用户列表中的档案、活跃和消费摘要及标签。 */
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

/** 面向页面的用户基础档案。 */
export interface UserProfileView {
  userId: number
  nickname?: string
  avatar?: string
  status: number
  createdAt?: string
  updatedAt?: string
}

/** 事件事实展示视图，区分原始时间和有效时间。 */
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

/** 用户全景组合，包含基础资料、实时和窗口画像、标签及事件。 */
export interface UserDetail {
  profile: UserProfileView
  realtimeMetrics: Record<string, string>
  realtimeSource: string
  realtimeAvailable: boolean
  windowMetrics: Record<string, any>
  tags: string[]
  recentEvents: PageResponse<EventView>
}

/** 触达任务列表视图，包含渠道、用户及发送状态。 */
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

/** 单次渠道发送记录的展示视图。 */
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

/** 触达点击事实及扩展属性视图。 */
export interface ClickView {
  id: number
  taskId?: number
  userId: number
  clickSource?: string
  clickTime?: string
  properties: Record<string, any>
}

/** 目标事件到活动触达的归因展示视图。 */
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

/** 触达任务及发送、点击和归因记录的组合详情。 */
export interface DeliveryDetail {
  task: DeliveryListItem
  record?: DeliveryRecordView | null
  clicks: ClickView[]
  attributions: AttributionView[]
}

/** 后端与各基础设施的健康状态。 */
export interface SystemStatus {
  backend: string
  mysql: string
  redis: string
  kafka: string
}

/** 前端恢复认证所需的操作员展示信息和令牌元数据。 */
export interface AuthSession {
  operatorId: number
  role: string
  displayName: string
  tokenName?: string
  tokenValue?: string | null
  loginId?: string
}
