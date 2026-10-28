import { apiRequest } from './http'
import { demoData } from './demo'
import type { PageResponse, UserDetail, UserListItem, UserProfileView, EventView } from '@/types/api'

/** 按页码、大小和关键字查询用户，演示模式返回同条件夹具。 */
export const listUsers = (page = 1, pageSize = 10, keyword?: string) => apiRequest<PageResponse<UserListItem>>({ method: 'GET', url: '/users', params: { page, pageSize, keyword } }, () => demoData.users(page, pageSize, keyword))

/** 按用户 ID 读取基础资料、实时及窗口画像和近期行为。 */
export const getUser = (userId: number) => apiRequest<UserDetail>({ method: 'GET', url: `/users/${userId}` }, () => demoData.userDetail(userId))

/** 读取指定用户的基础档案，供独立资料展示使用。 */
export const getUserProfile = (userId: number) => apiRequest<UserProfileView>({ method: 'GET', url: `/users/${userId}/profile` }, () => demoData.userDetail(userId).profile)

/** 分页查询指定用户的行为事件，供详情页继续浏览。 */
export const getUserEvents = (userId: number, page = 1, pageSize = 20) => apiRequest<PageResponse<EventView>>({ method: 'GET', url: `/users/${userId}/events`, params: { page, pageSize } }, () => demoData.userDetail(userId).recentEvents)
