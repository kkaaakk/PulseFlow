import { apiRequest } from './http'
import { demoData } from './demo'
import type { EventView, PageResponse } from '@/types/api'

/** 按分页、类型和用户查询事件列表，演示模式复用相同筛选条件。 */
export const listEvents = (page = 1, pageSize = 20, eventType?: string, userId?: number) => apiRequest<PageResponse<EventView>>({ method: 'GET', url: '/events', params: { page, pageSize, eventType, userId } }, () => demoData.events(page, pageSize, eventType, userId))

/** 编码业务事件 ID 后读取事件详情，避免路径中的特殊字符影响请求。 */
export const getEvent = (eventId: string) => apiRequest<EventView>({ method: 'GET', url: `/events/${encodeURIComponent(eventId)}` }, () => demoData.event(eventId))
