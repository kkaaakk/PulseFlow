import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as authApi from '@/api/auth'
import { setToken, TOKEN_KEY } from '@/api/http'
import type { AuthSession } from '@/types/api'

// 定义认证状态仓库，集中处理首次会话恢复、登录与退出。
export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(null)
  const initialized = ref(false)
  const isAuthenticated = computed(() => Boolean(session.value && sessionStorage.getItem(TOKEN_KEY)))

  /** 调用登录 API 后保存会话，并标记认证状态已初始化。 */
  const login = async (operatorId: number, password: string) => {
    session.value = await authApi.login(operatorId, password)
    initialized.value = true
  }

  /** 首次访问时根据本地令牌恢复会话，失败清除令牌，避免重复初始化。 */
  const restore = async () => {
    if (initialized.value) return
    if (!sessionStorage.getItem(TOKEN_KEY)) {
      initialized.value = true
      return
    }
    try {
      session.value = await authApi.me()
    } catch {
      session.value = null
      setToken(null)
    } finally {
      initialized.value = true
    }
  }

  /** 请求服务端注销，无论请求结果如何都清空本地会话和令牌。 */
  const logout = async () => {
    try {
      await authApi.logout()
    } finally {
      session.value = null
      setToken(null)
      initialized.value = true
    }
  }

  return { session, initialized, isAuthenticated, login, restore, logout }
})
