import axios, { type AxiosRequestConfig } from 'axios'

import { demoResolve, isDemoMode } from './demo'
import type { ApiEnvelope } from '@/types/api'

export const TOKEN_KEY = 'pulseflow_token'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 20_000,
  headers: { 'Content-Type': 'application/json' },
})

// 每次请求从当前标签页读取登录令牌，统一注入 Java 认证头。
http.interceptors.request.use((config) => {
  const token = sessionStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.set('token', token)
  }
  return config
})

// 未授权时清除本地令牌并跳转登录，其余错误继续交给调用页面处理。
http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && window.location.pathname !== '/login') {
      sessionStorage.removeItem(TOKEN_KEY)
      window.location.assign('/login')
    }
    return Promise.reject(error)
  },
)

export class ApiError extends Error {
  /** 保存统一业务错误码和展示消息，便于调用方识别失败响应。 */
  constructor(public readonly code: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 校验统一响应 code，失败抛出 ApiError，通过后返回业务 data。 */
export const unwrapApiResponse = <T>(envelope: ApiEnvelope<T>): T => {
  if (envelope.code !== 200) {
    throw new ApiError(envelope.code, envelope.message || '请求失败')
  }
  return envelope.data
}

/** 演示模式返回独立数据副本，真实模式发送 Axios 请求并解包业务响应。 */
export const apiRequest = async <T>(config: AxiosRequestConfig, demoValue: T | (() => T)): Promise<T> => {
  if (isDemoMode) {
    return demoResolve(demoValue)
  }
  const response = await http.request<ApiEnvelope<T>>(config)
  return unwrapApiResponse(response.data)
}

/** 设置或移除当前标签页的会话令牌，供请求拦截器自动附带。 */
export const setToken = (token?: string | null) => {
  if (token) sessionStorage.setItem(TOKEN_KEY, token)
  else sessionStorage.removeItem(TOKEN_KEY)
}
