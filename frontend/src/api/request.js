import { useAuthStore } from '../stores/auth.js'

/**
 * fetch 封装：统一响应解析 + 错误码处理（docs/03-接口清单.md §1.1 / §5）。
 * - 成功：resolve 出 data 字段
 * - 失败：reject 出 Error，message 为后端 message，附带 code/traceId/httpStatus/data
 * - 40101：清 token 跳登录；40901/40902 等语义错误由调用方按需展示
 */
export class ApiError extends Error {
  constructor(message, { code, traceId, httpStatus, data }) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
    this.httpStatus = httpStatus
    this.data = data
  }
}

async function request(path, { method = 'GET', body, headers = {} } = {}) {
  const auth = useAuthStore()
  if (auth.token) {
    headers['Authorization'] = `Bearer ${auth.token}`
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  let res
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  } catch {
    throw new ApiError('网络异常，请检查后端服务是否启动（8080）', { code: -1, httpStatus: 0 })
  }

  // 后端拦截器可能直接回 401/403 无统一响应体，先兜住
  let payload = null
  try {
    payload = await res.json()
  } catch {
    payload = null
  }

  if (payload && payload.code === 0) {
    return payload.data
  }

  const code = payload?.code ?? res.status * 100 + 1
  const message = payload?.message || `请求失败（HTTP ${res.status}）`

  // 40101 未登录/过期：清场跳登录（docs/03 §5 处理建议）
  if (code === 40101 || res.status === 401) {
    auth.logout()
    if (!location.pathname.startsWith('/login')) {
      location.href = '/login'
    }
  }

  throw new ApiError(message, {
    code,
    traceId: payload?.traceId,
    httpStatus: res.status,
    data: payload?.data
  })
}

export const get = (path) => request(path)
export const post = (path, body, headers) => request(path, { method: 'POST', body, headers })
export const put = (path, body) => request(path, { method: 'PUT', body })
export const del = (path) => request(path, { method: 'DELETE' })
