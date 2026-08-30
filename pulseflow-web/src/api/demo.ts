import type {
  AttributionView,
  CampaignDetail,
  CampaignDsl,
  CampaignListItem,
  ContentResponse,
  DashboardSummary,
  DashboardTrends,
  DeliveryDetail,
  DeliveryListItem,
  DraftResponse,
  EventView,
  InsightResponse,
  PageResponse,
  ParseResponse,
  ReviewView,
  SystemStatus,
  TrendPoint,
  UserDetail,
  UserListItem,
} from '@/types/api'

export const isDemoMode = import.meta.env.VITE_DEMO_MODE === 'true'

const now = Date.now()
const at = (minutesAgo: number) => new Date(now - minutesAgo * 60_000).toISOString()

export const demoResolve = <T>(value: T | (() => T)): Promise<T> =>
  Promise.resolve(structuredClone(typeof value === 'function' ? (value as () => T)() : value))

const condition = (field: string, operator: string, valueType: string, value: string | number | boolean) => ({
  field,
  operator,
  valueType,
  value,
})

export const demoDsl: CampaignDsl = {
  schemaVersion: 1,
  campaignName: '高活跃未购买用户召回',
  objective: 'CONVERSION',
  audience: {
    logic: 'AND',
    conditions: [
      condition('activeDays7d', 'GTE', 'INTEGER', 3),
      condition('orderCount30d', 'EQ', 'INTEGER', 0),
    ],
  },
  channel: 'IN_APP',
  schedule: {
    type: 'ONCE',
    sendAt: new Date(now + 24 * 60 * 60_000).toISOString(),
    timezone: 'Asia/Shanghai',
  },
  frequencyCap: { maxTimes: 1, windowHours: 24 },
  promotionFacts: [{ type: 'FULL_REDUCTION', threshold: 300, discount: 30, description: '满300减30' }],
}

const campaignRows: CampaignListItem[] = [
  { id: 2001, name: '高活跃未购买用户召回', status: 'ACTIVE', triggerType: 'SCHEDULED', channel: 'IN_APP', audience: 128430, sent: 42800, clicked: 5393, converted: 1268, createdAt: at(85), createdBy: 1024 },
  { id: 2002, name: '购物车 24 小时挽回', status: 'ACTIVE', triggerType: 'DELAYED', channel: 'PUSH', audience: 89420, sent: 31890, clicked: 2841, converted: 902, createdAt: at(260), createdBy: 1024 },
  { id: 2003, name: '新用户欢迎系列', status: 'PAUSED', triggerType: 'EVENT', channel: 'IN_APP', audience: 52400, sent: 22400, clicked: 2200, converted: 680, createdAt: at(520), createdBy: 1 },
  { id: 2004, name: '价格敏感用户年中礼', status: 'DRAFT', triggerType: 'SCHEDULED', channel: 'EMAIL', audience: 0, sent: 0, clicked: 0, converted: 0, createdAt: at(880), createdBy: 1024 },
]

const detailFor = (campaign: CampaignListItem): CampaignDetail => ({
  campaign: {
    id: campaign.id,
    name: campaign.name,
    description: '面向近期活跃且尚未购买的用户，基于候选用户池进行圈选。',
    status: campaign.status,
    triggerType: campaign.triggerType,
    channel: campaign.channel,
    userDailyLimit: 1,
    campaignWeeklyLimit: 3,
    startTime: at(20),
    nextTriggerAt: at(-120),
    createdAt: campaign.createdAt,
    updatedAt: at(12),
    createdBy: campaign.createdBy,
  },
  rules: [
    { id: 1, name: 'activeDays7d', type: 'PROFILE', config: { field: 'activeDays7d', operator: 'GTE', value: 3 }, priority: 0, enabled: true },
    { id: 2, name: 'orderCount30d', type: 'PROFILE', config: { field: 'orderCount30d', operator: 'EQ', value: 0 }, priority: 0, enabled: true },
  ],
  audience: { estimatedCount: campaign.audience, dataVersion: 'profile-20260830-1030', calculationMode: 'SNAPSHOT', warnings: [] },
  deliverySummary: { sent: campaign.sent, delivered: Math.round(campaign.sent * 0.992), clicked: campaign.clicked, converted: campaign.converted, deliveryRate: 0.992, clickRate: campaign.sent ? campaign.clicked / campaign.sent : 0, conversionRate: campaign.sent ? campaign.converted / campaign.sent : 0 },
  attributionSummary: { attributedConversions: campaign.converted, model: 'CLICK_LAST_TOUCH', windowHours: 24 },
  aiReview: campaign.id === 2001 ? {
    campaignId: campaign.id,
    status: 'SUCCESS',
    model: 'fake-mock-v1',
    promptVersion: 'campaign-review-v1',
    updatedAt: at(8),
    review: {
      summary: '本次活动点击表现优于历史平均，转化率小幅提升。',
      highlights: [{ title: '点击率明显提升', description: '本次点击率为12.6%，高于历史平均9.1%。', evidenceKeys: ['metrics.clickRate', 'historicalBaseline.clickRate'] }],
      nextActions: [{ action: '下一次优先使用直接利益型文案', reason: '该版本点击率和转化率均为最高。' }],
    },
  } : null,
})

const userRows: UserListItem[] = [
  { userId: 1024, nickname: '演示用户 A', status: 1, lastActiveAt: at(18), activeDays7d: 6, spend30d: 880.5, tags: ['HIGH_VALUE', 'PRICE_SENSITIVE'] },
  { userId: 1025, nickname: '演示用户 B', status: 1, lastActiveAt: at(46), activeDays7d: 7, spend30d: 1520, tags: ['HIGH_VALUE'] },
  { userId: 1026, nickname: '演示用户 C', status: 1, lastActiveAt: at(72), activeDays7d: 5, spend30d: 699, tags: ['CHURN_RISK'] },
  { userId: 1027, nickname: '对照用户 D', status: 1, lastActiveAt: at(190), activeDays7d: 2, spend30d: 1200, tags: [] },
  { userId: 1028, nickname: '对照用户 E', status: 1, lastActiveAt: at(340), activeDays7d: 6, spend30d: 200, tags: ['PRICE_SENSITIVE'] },
]

const eventRows: EventView[] = [
  { id: 1, eventId: 'evt_1024_001', userId: 1024, eventType: 'CONTENT_VIEW', targetId: 8866, eventTime: at(18), receivedAt: at(17), effectiveEventTime: at(18), clockSkew: false, properties: { category: 'AI', duration: 5000 } },
  { id: 2, eventId: 'evt_1024_002', userId: 1024, eventType: 'SEARCH', eventTime: at(31), receivedAt: at(31), effectiveEventTime: at(31), clockSkew: false, properties: { keyword: '无线耳机' } },
  { id: 3, eventId: 'evt_1025_001', userId: 1025, eventType: 'ADD_CART', targetId: 1002, eventTime: at(46), receivedAt: at(46), effectiveEventTime: at(46), clockSkew: false, properties: { cartItemId: 'cart-1002' } },
  { id: 4, eventId: 'evt_1026_001', userId: 1026, eventType: 'LOGIN', eventTime: at(72), receivedAt: at(72), effectiveEventTime: at(72), clockSkew: false, properties: {} },
  { id: 5, eventId: 'evt_1024_003', userId: 1024, eventType: 'ORDER_PAID', targetId: 9999, eventTime: at(120), receivedAt: at(119), effectiveEventTime: at(120), clockSkew: false, properties: { price: 328, orderId: 'ord_demo_01' } },
]

const deliveryRows: DeliveryListItem[] = [
  { taskId: 7001, campaignId: 2001, campaignName: '高活跃未购买用户召回', userId: 1024, channel: 'IN_APP', status: 'SENT', dispatchStatus: 'PUBLISHED', retryCount: 0, createdAt: at(40), sentAt: at(39) },
  { taskId: 7002, campaignId: 2001, campaignName: '高活跃未购买用户召回', userId: 1025, channel: 'IN_APP', status: 'SENT', dispatchStatus: 'PUBLISHED', retryCount: 0, createdAt: at(42), sentAt: at(41) },
  { taskId: 7003, campaignId: 2002, campaignName: '购物车 24 小时挽回', userId: 1026, channel: 'PUSH', status: 'WAIT_RETRY', dispatchStatus: 'PENDING', retryCount: 1, createdAt: at(62) },
  { taskId: 7004, campaignId: 2001, campaignName: '高活跃未购买用户召回', userId: 1026, channel: 'IN_APP', status: 'SENT', dispatchStatus: 'PUBLISHED', retryCount: 0, createdAt: at(75), sentAt: at(74) },
]

const attributionRows: AttributionView[] = [
  { id: 9001, userId: 1024, campaignId: 2001, campaignName: '高活跃未购买用户召回', clickEventId: 8101, targetEventId: 'evt_order_demo_01', taskId: 7001, attributionModel: 'CLICK_LAST_TOUCH', attributionWindowHours: 24, creditedAt: at(110) },
  { id: 9002, userId: 1025, campaignId: 2002, campaignName: '购物车 24 小时挽回', clickEventId: 8102, targetEventId: 'evt_order_demo_02', taskId: 7002, attributionModel: 'CLICK_LAST_TOUCH', attributionWindowHours: 24, creditedAt: at(250) },
]

const page = <T>(items: T[], pageNumber = 1, pageSize = 10): PageResponse<T> => ({
  items: items.slice((pageNumber - 1) * pageSize, pageNumber * pageSize),
  page: pageNumber,
  pageSize,
  total: items.length,
  totalPages: Math.ceil(items.length / pageSize),
})

export const demoData = {
  dashboardSummary: (): DashboardSummary => ({ todayEvents: 12842168, activeUsers: 356789, runningCampaigns: 23, todayDeliveries: 2156789, deliverySuccessRate: 0.986, todayClicks: 215678, todayConversions: 32456, todayAttributions: 32456 }),
  dashboardTrends: (): DashboardTrends => ({
    events: Array.from({ length: 24 }, (_, index) => ({ label: `${String(index).padStart(2, '0')}:00`, value: Math.round(180000 + Math.sin(index / 3) * 120000 + index * 27000) })),
    deliveries: [1.64, 1.97, 1.58, 2.12, 2.22, 2.34, 2.12].map((value, index) => ({ label: `08-${String(index + 1).padStart(2, '0')}`, value: Math.round(value * 1_000_000) })),
    conversions: [0.032, 0.038, 0.031, 0.041, 0.044, 0.048, 0.043].map((value, index) => ({ label: `08-${String(index + 1).padStart(2, '0')}`, value })),
  }),
  campaigns: (pageNumber: number, pageSize: number, keyword?: string, status?: string) => {
    const filtered = campaignRows.filter((row) => (!keyword || row.name.includes(keyword)) && (!status || row.status === status))
    return page(filtered, pageNumber, pageSize)
  },
  campaignDetail: (campaignId: number) => detailFor(campaignRows.find((row) => row.id === campaignId) ?? campaignRows[0]),
  performance: (campaignId: number) => {
    const detail = detailFor(campaignRows.find((row) => row.id === campaignId) ?? campaignRows[0])
    return { campaignId, targetAudienceCount: detail.audience.estimatedCount, sentCount: detail.deliverySummary.sent, deliveredCount: detail.deliverySummary.delivered, clickedCount: detail.deliverySummary.clicked, convertedCount: detail.deliverySummary.converted, unsubscribeCount: 0, deliveryRate: detail.deliverySummary.deliveryRate, clickRate: detail.deliverySummary.clickRate, conversionRate: detail.deliverySummary.conversionRate, unsubscribeRate: 0, calculatedAt: at(8) }
  },
  campaignTrend: (campaignId: number): TrendPoint[] => {
    const sent = campaignRows.find((row) => row.id === campaignId)?.sent ?? 0
    return [0.48, 0.64, 0.58, 0.77, 0.83, 0.91, 1].map((scale, index) => ({ label: `08-${String(index + 1).padStart(2, '0')}`, value: Math.round(sent * scale) }))
  },
  review: (campaignId: number): ReviewView | null => detailFor(campaignRows.find((row) => row.id === campaignId) ?? campaignRows[0]).aiReview ?? null,
  users: (pageNumber: number, pageSize: number, keyword?: string) => page(userRows.filter((row) => !keyword || String(row.userId).includes(keyword) || row.nickname?.includes(keyword)), pageNumber, pageSize),
  userDetail: (userId: number): UserDetail => {
    const user = userRows.find((row) => row.userId === userId) ?? userRows[0]
    return { profile: { userId: user.userId, nickname: user.nickname, status: user.status, createdAt: at(60 * 24 * 90), updatedAt: at(18) }, realtimeMetrics: { todayViews: '42', todaySearches: '8', cartCount: '2', lastActiveAt: user.lastActiveAt ?? '' }, realtimeSource: 'REDIS', realtimeAvailable: true, windowMetrics: { activeDays7d: user.activeDays7d, spend30d: user.spend30d, search1h: 8, orderCount30d: user.spend30d > 0 ? 2 : 0, daysSinceLastPurchase: 5 }, tags: user.tags, recentEvents: page(eventRows.filter((event) => event.userId === user.userId), 1, 20) }
  },
  events: (pageNumber: number, pageSize: number, eventType?: string, userId?: number) => page(eventRows.filter((row) => (!eventType || row.eventType === eventType) && (!userId || row.userId === userId)), pageNumber, pageSize),
  event: (eventId: string) => eventRows.find((row) => row.eventId === eventId) ?? eventRows[0],
  deliveries: (pageNumber: number, pageSize: number, campaignId?: number, userId?: string, status?: string) => page(deliveryRows.filter((row) => (!campaignId || row.campaignId === campaignId) && (!userId || String(row.userId).includes(userId)) && (!status || row.status === status)), pageNumber, pageSize),
  delivery: (taskId: number): DeliveryDetail => { const task = deliveryRows.find((row) => row.taskId === taskId) ?? deliveryRows[0]; return { task, record: task.sentAt ? { id: task.taskId + 1000, taskId: task.taskId, campaignId: task.campaignId, userId: task.userId, channel: task.channel, status: 'SENT', sentAt: task.sentAt } : null, clicks: task.taskId === 7001 ? [{ id: 8101, taskId, userId: task.userId, clickSource: 'IN_APP', clickTime: at(30), properties: {} }] : [], attributions: attributionRows.filter((row) => row.taskId === task.taskId) } },
  attributions: (pageNumber: number, pageSize: number, campaignId?: number, userId?: string) => page(attributionRows.filter((row) => (!campaignId || row.campaignId === campaignId) && (!userId || String(row.userId).includes(userId))), pageNumber, pageSize),
  attribution: (id: number) => attributionRows.find((row) => row.id === id) ?? attributionRows[0],
  system: (): SystemStatus => ({ backend: 'UP', mysql: 'UP', redis: 'UP', kafka: 'UP', aiMode: 'MOCK', piiGuardrail: 'DISABLED' }),
  parse: (draftId: number): ParseResponse => ({ requestId: 'ai_demo_request_01', draftId, status: 'VALIDATED', dsl: demoDsl, estimatedAudience: { count: 128430, dataVersion: 'profile-20260830-1030', calculationMode: 'SNAPSHOT', warnings: [] }, missingFields: [], warnings: [] }),
  draft: (draftId: number, dsl = demoDsl): DraftResponse => ({ draftId, status: 'VALIDATED', dsl, warnings: [], estimatedAudience: { count: 128430, dataVersion: 'profile-20260830-1030', calculationMode: 'SNAPSHOT', warnings: [] } }),
  insight: (draftId: number): InsightResponse => ({ requestId: 'ai_demo_insight_01', draftId, metrics: { estimatedAudienceCount: 128430, activeRate7d: 0.78, priceSensitiveRate: 0.35, churnRiskRate: 0.18, cartWithoutPurchaseRate: 0.42 }, insight: { summary: '目标人群活跃度高，但加购后的购买转化偏弱，价格敏感特征明显。', findings: [{ title: '活跃度高于全站平均', description: '目标人群 7 日活跃率为 78%，全站基线为 59%。', importance: 'HIGH' }], strategySuggestions: [{ type: 'OFFER', suggestion: '优先采用明确的满减优惠', reason: '价格敏感用户占比为 35%' }], risks: ['该人群包含一定比例的流失风险用户，不适合高频轰炸'] }, dataQuality: { baselineType: 'CANDIDATE_POOL', proxyMetrics: ['cartWithoutPurchaseRate'], unavailableMetrics: ['topCategories'] } }),
  content: (draftId: number): ContentResponse => ({ requestId: 'ai_demo_content_01', draftId, content: { variants: [{ type: 'DIRECT_BENEFIT', variant: 'A', title: '购物车好物，满300减30', body: '你关注的商品还在购物车中，满300减30优惠已开放。', strategy: '直接表达优惠利益' }, { type: 'URGENCY', variant: 'B', title: '满减优惠即将结束', body: '购物车商品仍可购买，满300减30优惠有效至明天。', strategy: '基于真实截止时间制造适度紧迫感' }, { type: 'PERSONALIZED', variant: 'C', title: '你关注的好物有新优惠', body: '近期关注的商品可享满300减30，点击查看当前优惠。', strategy: '强调用户近期兴趣' }] } }),
  confirm: (campaignId: number) => ({ campaignId, draftId: 3001, idempotent: false }),
}
