import { apiRequest, setToken } from './http'
import type { AuthSession } from '@/types/api'

/**
 * 提交操作员 ID 和密码，成功后把返回令牌保存到当前标签页会话，再返回登录身份。
 */
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

/**
 * 读取当前令牌对应的操作员身份，用于页面刷新后的会话恢复。
 */
export const me = () => apiRequest<AuthSession>({ method: 'GET', url: '/auth/me' }, {
  operatorId: 1024,
  role: 'OPERATOR',
  displayName: 'Operator',
  tokenName: 'token',
  loginId: '1024',
})

/**
 * 请求服务端注销，成功后清除本地令牌；调用方 store 在失败时也负责清理本地状态。
 */
export const logout = async () => {
  await apiRequest<void>({ method: 'POST', url: '/auth/logout' }, undefined as void)
  setToken(null)
}
