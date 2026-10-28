import { apiRequest, setToken } from './http'
import type { AuthSession } from '@/types/api'

/** 校验登录凭据，成功后保存返回令牌并返回会话信息。 */
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

/** 读取当前登录身份，用于刷新页面后恢复前端会话。 */
export const me = () => apiRequest<AuthSession>({ method: 'GET', url: '/auth/me' }, {
  operatorId: 1024,
  role: 'OPERATOR',
  displayName: 'Operator',
  tokenName: 'token',
  loginId: '1024',
})

/** 请求注销服务端会话，成功后移除当前标签页令牌。 */
export const logout = async () => {
  await apiRequest<void>({ method: 'POST', url: '/auth/logout' }, undefined as void)
  setToken(null)
}
