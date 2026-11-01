import { get, put } from './request.js'

// 管理端接口：预算（docs/03 §2.4）+ 用户赋权（docs/03 §2.6）
export function listBudgets(period) {
  const query = period ? `?period=${encodeURIComponent(period)}` : ''
  return get(`/api/v1/budgets${query}`)
}

export function adjustBudget(deptId, payload) {
  return put(`/api/v1/budgets/${deptId}`, payload)
}

// 总预算池（管理端"设置总预算"）
export function getBudgetPool(period) {
  const query = period ? `?period=${encodeURIComponent(period)}` : ''
  return get(`/api/v1/budgets/pool${query}`)
}

export function setBudgetPool(payload) {
  return put('/api/v1/budgets/pool', payload)
}

export function listUsers() {
  return get('/api/v1/admin/users')
}

export function updateUserRoles(userId, roles) {
  return put(`/api/v1/admin/users/${userId}/roles`, { roles })
}

/** 运营指标（含发票识别：成功率/复核率/降级率/P95），docs/10 + docs/19 §2 */
export function getMetrics() {
  return get('/api/v1/admin/metrics')
}
