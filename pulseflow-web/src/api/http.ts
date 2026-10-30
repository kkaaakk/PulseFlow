import axios, { type AxiosRequestConfig } from 'axios'

import { demoResolve, isDemoMode } from './demo'
import type { ApiEnvelope } from '@/types/api'

export const TOKEN_KEY = 'pulseflow_token'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 20_000,
  headers: { 'Content-Type': 'application/json' },
})

// 每次请求从当前标签页读取最新令牌，避免登录或退出后继续使用旧的请求头。
http.interceptors.request.use((config) => {
  const token = sessionStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.set('token', token)
  }
  return config
})

// HTTP 401 表示会话失效：清理令牌并返回登录页，其他错误继续交给调用方处理。
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
  /**
   * 创建保留业务响应码的 ApiError，供调用方区分 HTTP 成功但业务失败的响应。
   */
  constructor(public readonly code: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * 仅在业务码为 200 时返回 data；否则使用响应消息构造 ApiError，不把失败信封当作正常数据。
 */
export const unwrapApiResponse = <T>(envelope: ApiEnvelope<T>): T => {
  if (envelope.code !== 200) {
    throw new ApiError(envelope.code, envelope.message || '请求失败')
  }
  return envelope.data
}

/**
 * 演示模式求值并深拷贝 demoValue；真实模式发送 Axios 请求，再统一校验并解包业务响应。
 */
export const apiRequest = async <T>(config: AxiosRequestConfig, demoValue: T | (() => T)): Promise<T> => {
  if (isDemoMode) {
    return demoResolve(demoValue)
  }
  const response = await http.request<ApiEnvelope<T>>(config)
  return unwrapApiResponse(response.data)
}

/**
 * 把非空 token 写入 sessionStorage，空值则删除；令牌仅在当前标签页会话内保存。
 */
export const setToken = (token?: string | null) => {
  if (token) sessionStorage.setItem(TOKEN_KEY, token)
  else sessionStorage.removeItem(TOKEN_KEY)
}
