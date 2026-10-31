import { useAuthStore } from '../stores/auth.js'
import { ApiError, get, post, put } from './request.js'

export function fetchInvoice(id) {
  return get(`/api/v1/invoices/${id}`)
}

/** 复核队列（FINANCE/ADMIN）：默认 MANUAL，docs/03 §2.5 */
export function listInvoices({ ocrStatus = 'MANUAL', page = 1, size = 10 } = {}) {
  return get(`/api/v1/invoices?ocrStatus=${encodeURIComponent(ocrStatus)}&page=${page}&size=${size}`)
}

/** 人工复核：修正字段并置 SUCCESS */
export function reviewInvoice(id, dto) {
  return put(`/api/v1/invoices/${id}/review`, dto)
}

/** 重试识别：MANUAL/FAILED 可用 */
export function retryInvoice(id) {
  return post(`/api/v1/invoices/${id}/retry`)
}

/**
 * 上传发票（multipart），复用 request.js 的 Result 解析与 401 处理。
 * 文件接口必须走 fetch 拿 blob：<img src> 无法携带 Authorization 头。
 */
export async function uploadInvoice(file) {
  const auth = useAuthStore()
  const form = new FormData()
  form.append('file', file)
  const headers = {}
  if (auth.token) {
    headers.Authorization = `Bearer ${auth.token}`
  }

  let res
  try {
    res = await fetch('/api/v1/invoices/upload', { method: 'POST', headers, body: form })
  } catch {
    throw new ApiError('网络异常，请检查后端服务是否启动（8080）', { code: -1, httpStatus: 0 })
  }
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
  const message = payload?.message || `上传失败（HTTP ${res.status}）`
  if (code === 40101 || res.status === 401) {
    auth.logout()
    if (!location.pathname.startsWith('/login')) {
      location.href = '/login'
    }
  }
  throw new ApiError(message, { code, traceId: payload?.traceId, httpStatus: res.status, data: payload?.data })
}

/** 拉取发票文件转 blob URL，供 img 预览 / 新窗口打开 */
export async function fetchInvoiceBlobUrl(id) {
  const auth = useAuthStore()
  const res = await fetch(`/api/v1/invoices/${id}/file`, {
    headers: auth.token ? { Authorization: `Bearer ${auth.token}` } : {}
  })
  if (!res.ok) {
    throw new ApiError(`发票文件加载失败（HTTP ${res.status}）`, { code: res.status * 100 + 1, httpStatus: res.status })
  }
  const blob = await res.blob()
  return URL.createObjectURL(blob)
}
