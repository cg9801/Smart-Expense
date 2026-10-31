import { get, post } from './request.js'

// 契约：docs/03-接口清单.md §2.4（付款单）
export function listPayments({ status, reportNo, page = 1, size = 10 } = {}) {
  const params = new URLSearchParams({ page, size })
  if (status) params.set('status', status)
  if (reportNo) params.set('reportNo', reportNo)
  return get(`/api/v1/payments?${params}`)
}

export function getPayment(id) {
  return get(`/api/v1/payments/${id}`)
}

/** 模拟银行回执，触发 T6（docs/02 §3）；重复确认后端返回 40901 */
export function confirmPayment(id) {
  return post(`/api/v1/payments/${id}/confirm`)
}
