import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, get, post } from '../request.js'
import { useAuthStore } from '../../stores/auth.js'

beforeEach(() => {
  vi.restoreAllMocks()
  useAuthStore().logout()
})

describe('request.js', () => {
  it('code=0 时直接返回 data', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      status: 200,
      json: async () => ({ code: 0, message: 'ok', data: { id: 1 } })
    })

    await expect(get('/api/v1/x')).resolves.toEqual({ id: 1 })
  })

  it('业务错误抛 ApiError，保留 code/message/traceId', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      status: 409,
      json: async () => ({ code: 40902, message: '部门预算不足', traceId: 't-1' })
    })

    await expect(get('/api/v1/x')).rejects.toMatchObject({
      name: 'ApiError',
      code: 40902,
      message: '部门预算不足',
      traceId: 't-1'
    })
  })

  it('网络异常统一成 code=-1，前端可提示后端未启动', async () => {
    global.fetch = vi.fn().mockRejectedValue(new Error('ECONNREFUSED'))

    await expect(get('/api/v1/x')).rejects.toMatchObject({ code: -1 })
  })

  it('POST 会带 Content-Type 并序列化 body', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 200,
      json: async () => ({ code: 0, data: null })
    })
    global.fetch = fetchMock

    await post('/api/v1/x', { a: 1 })

    const [, options] = fetchMock.mock.calls[0]
    expect(options.method).toBe('POST')
    expect(options.headers['Content-Type']).toBe('application/json')
    expect(JSON.parse(options.body)).toEqual({ a: 1 })
  })
})
