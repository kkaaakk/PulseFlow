import { apiRequest } from './http'
import { demoData } from './demo'
import type { PageResponse, UserDetail, UserListItem, UserProfileView, EventView } from '@/types/api'

export const listUsers = (page = 1, pageSize = 10, keyword?: string) => apiRequest<PageResponse<UserListItem>>({ method: 'GET', url: '/users', params: { page, pageSize, keyword } }, () => demoData.users(page, pageSize, keyword))

export const getUser = (userId: number) => apiRequest<UserDetail>({ method: 'GET', url: `/users/${userId}` }, () => demoData.userDetail(userId))

export const getUserProfile = (userId: number) => apiRequest<UserProfileView>({ method: 'GET', url: `/users/${userId}/profile` }, () => demoData.userDetail(userId).profile)

export const getUserEvents = (userId: number, page = 1, pageSize = 20) => apiRequest<PageResponse<EventView>>({ method: 'GET', url: `/users/${userId}/events`, params: { page, pageSize } }, () => demoData.userDetail(userId).recentEvents)
