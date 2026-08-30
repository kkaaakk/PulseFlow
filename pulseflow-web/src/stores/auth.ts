import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as authApi from '@/api/auth'
import { setToken, TOKEN_KEY } from '@/api/http'
import type { AuthSession } from '@/types/api'

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(null)
  const initialized = ref(false)
  const isAuthenticated = computed(() => Boolean(session.value && sessionStorage.getItem(TOKEN_KEY)))

  const login = async (operatorId: number, password: string) => {
    session.value = await authApi.login(operatorId, password)
    initialized.value = true
  }

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
