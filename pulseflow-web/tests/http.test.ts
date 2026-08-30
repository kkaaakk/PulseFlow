import { describe, expect, it } from 'vitest'

import { ApiError, unwrapApiResponse } from '@/api/http'

describe('ApiResponse parser', () => {
  it('unwraps the unified backend envelope', () => {
    expect(unwrapApiResponse({ code: 200, message: 'success', data: { ok: true } })).toEqual({ ok: true })
  })

  it('turns backend errors into a typed client error', () => {
    expect(() => unwrapApiResponse({ code: 422, message: 'DSL invalid', data: null })).toThrow(ApiError)
    try {
      unwrapApiResponse({ code: 403, message: 'forbidden', data: null })
    } catch (error) {
      expect(error).toMatchObject({ code: 403, message: 'forbidden' })
    }
  })
})
