import { apiRequest } from './http'
import { demoData } from './demo'
import type { EventView, PageResponse } from '@/types/api'

/**
 * 分页查询事件流，支持可选事件类型和用户 ID，演示模式使用本地样例。
 */
export const listEvents = (page = 1, pageSize = 20, eventType?: string, userId?: number) => apiRequest<PageResponse<EventView>>({ method: 'GET', url: '/events', params: { page, pageSize, eventType, userId } }, () => demoData.events(page, pageSize, eventType, userId))

/**
 * 对业务事件 ID 做路径编码后读取事件详情，避免特殊字符破坏 URL。
 */
export const getEvent = (eventId: string) => apiRequest<EventView>({ method: 'GET', url: `/events/${encodeURIComponent(eventId)}` }, () => demoData.event(eventId))
