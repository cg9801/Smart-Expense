import { del, get, post, put } from './request.js'

// 契约：docs/03-接口清单.md §2.2
export function listReports({ scope = 'mine', status, page = 1, size = 10 } = {}) {
  const params = new URLSearchParams({ scope, page, size })
  if (status) params.set('status', status)
  return get(`/api/v1/reports?${params}`)
}

export function createDraft(dto) {
  return post('/api/v1/reports', dto)
}

export function updateDraft(id, dto) {
  return put(`/api/v1/reports/${id}`, dto)
}

export function getDetail(id) {
  return get(`/api/v1/reports/${id}`)
}

export function submitReport(id) {
  // Idempotency-Key：每次点提交生成新 uuid，重试复用同一次会话内的 key（docs/03 §1.2）
  const key = crypto.randomUUID()
  return post(`/api/v1/reports/${id}/submit`, undefined, { 'Idempotency-Key': key })
}

export function withdrawReport(id) {
  return post(`/api/v1/reports/${id}/withdraw`)
}

export function getTimeline(id) {
  return get(`/api/v1/reports/${id}/timeline`)
}

/** 删除草稿（docs/03 §2.2）：仅本人 + DRAFT，逻辑删除；不是状态流转 */
export function deleteReport(id) {
  return del(`/api/v1/reports/${id}`)
}
