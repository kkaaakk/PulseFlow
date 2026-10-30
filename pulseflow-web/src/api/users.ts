import { apiRequest } from './http'
import { demoData } from './demo'
import type { PageResponse, UserDetail, UserListItem, UserProfileView, EventView } from '@/types/api'

/**
 * 按分页与昵称或 ID 关键词查询用户画像列表。
 */
export const listUsers = (page = 1, pageSize = 10, keyword?: string) => apiRequest<PageResponse<UserListItem>>({ method: 'GET', url: '/users', params: { page, pageSize, keyword } }, () => demoData.users(page, pageSize, keyword))

/**
 * 读取指定用户的基础画像、实时与窗口指标、标签和最近事件。
 */
export const getUser = (userId: number) => apiRequest<UserDetail>({ method: 'GET', url: `/users/${userId}` }, () => demoData.userDetail(userId))

/**
 * 只读取用户基础画像，演示模式从组合详情提取 profile。
 */
export const getUserProfile = (userId: number) => apiRequest<UserProfileView>({ method: 'GET', url: `/users/${userId}/profile` }, () => demoData.userDetail(userId).profile)

/**
 * 分页读取指定用户事件；演示模式直接复用样例详情内的事件分页。
 */
export const getUserEvents = (userId: number, page = 1, pageSize = 20) => apiRequest<PageResponse<EventView>>({ method: 'GET', url: `/users/${userId}/events`, params: { page, pageSize } }, () => demoData.userDetail(userId).recentEvents)
