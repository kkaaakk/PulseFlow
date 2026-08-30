import { apiRequest, setToken } from './http'
import type { AuthSession } from '@/types/api'

export const login = async (operatorId: number, password: string) => {
  const session = await apiRequest<AuthSession>({ method: 'POST', url: '/auth/login', data: { operatorId, password } }, {
    operatorId: 1024,
    role: 'OPERATOR',
    displayName: 'Operator',
    tokenName: 'token',
    tokenValue: 'demo-token',
    loginId: '1024',
  })
  setToken(session.tokenValue)
  return session
}

export const me = () => apiRequest<AuthSession>({ method: 'GET', url: '/auth/me' }, {
  operatorId: 1024,
  role: 'OPERATOR',
  displayName: 'Operator',
  tokenName: 'token',
  loginId: '1024',
})

export const logout = async () => {
  await apiRequest<void>({ method: 'POST', url: '/auth/logout' }, undefined as void)
  setToken(null)
}
