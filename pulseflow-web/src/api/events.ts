import { apiRequest } from './http'
import { demoData } from './demo'
import type { EventView, PageResponse } from '@/types/api'

export const listEvents = (page = 1, pageSize = 20, eventType?: string, userId?: number) => apiRequest<PageResponse<EventView>>({ method: 'GET', url: '/events', params: { page, pageSize, eventType, userId } }, () => demoData.events(page, pageSize, eventType, userId))

export const getEvent = (eventId: string) => apiRequest<EventView>({ method: 'GET', url: `/events/${encodeURIComponent(eventId)}` }, () => demoData.event(eventId))
