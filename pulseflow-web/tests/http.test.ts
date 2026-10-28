// 验证统一 API 响应解包，业务错误码应转为可识别异常。
import { describe, expect, it } from 'vitest'

import { ApiError, unwrapApiResponse } from '@/api/http'

describe('ApiResponse parser', () => {
  // 验证成功响应返回业务 data，保持泛型对象内容完整。
  it('unwraps the unified backend envelope', () => {
    expect(unwrapApiResponse({ code: 200, message: 'success', data: { ok: true } })).toEqual({ ok: true })
  })

  // 验证失败响应转为 ApiError，并保留业务状态码与提示。
  it('turns backend errors into a typed client error', () => {
    expect(() => unwrapApiResponse({ code: 422, message: 'DSL invalid', data: null })).toThrow(ApiError)
    try {
      unwrapApiResponse({ code: 403, message: 'forbidden', data: null })
    } catch (error) {
      expect(error).toMatchObject({ code: 403, message: 'forbidden' })
    }
  })
})
