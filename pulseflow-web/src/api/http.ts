import axios, { type AxiosRequestConfig } from 'axios'

import { demoResolve, isDemoMode } from './demo'
import type { ApiEnvelope } from '@/types/api'

export const TOKEN_KEY = 'pulseflow_token'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 20_000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = sessionStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.set('token', token)
  }
  return config
})

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
  constructor(public readonly code: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

export const unwrapApiResponse = <T>(envelope: ApiEnvelope<T>): T => {
  if (envelope.code !== 200) {
    throw new ApiError(envelope.code, envelope.message || '请求失败')
  }
  return envelope.data
}

export const apiRequest = async <T>(config: AxiosRequestConfig, demoValue: T | (() => T)): Promise<T> => {
  if (isDemoMode) {
    return demoResolve(demoValue)
  }
  const response = await http.request<ApiEnvelope<T>>(config)
  return unwrapApiResponse(response.data)
}

export const setToken = (token?: string | null) => {
  if (token) sessionStorage.setItem(TOKEN_KEY, token)
  else sessionStorage.removeItem(TOKEN_KEY)
}
