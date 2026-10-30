import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as authApi from '@/api/auth'
import { setToken, TOKEN_KEY } from '@/api/http'
import type { AuthSession } from '@/types/api'

/** 管理当前标签页的操作员身份、一次性会话恢复与退出后的本地清理。 */
export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(null)
  const initialized = ref(false)
  const isAuthenticated = computed(() => Boolean(session.value && sessionStorage.getItem(TOKEN_KEY)))

  /**
   * 调用鉴权 API 并保存返回身份，成功后标记会话初始化完成。
   */
  const login = async (operatorId: number, password: string) => {
    session.value = await authApi.login(operatorId, password)
    initialized.value = true
  }

  /**
   * 首次需要时根据本地令牌恢复身份；无令牌直接结束，恢复失败同时清空身份和令牌。
   */
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

  /**
   * 请求注销，并在 finally 中清空身份和令牌，保证请求失败后本地也退出登录。
   */
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
