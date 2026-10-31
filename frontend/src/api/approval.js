import { get, post } from './request.js'

// 契约：docs/03-接口清单.md §2.3
export function listPending({ page = 1, size = 10 } = {}) {
  return get(`/api/v1/approvals/pending?page=${page}&size=${size}`)
}

/** 我的已办（docs/03 §2.3）：我处理过的节点，按处理时间倒序 */
export function listHandled({ page = 1, size = 10 } = {}) {
  return get(`/api/v1/approvals/handled?page=${page}&size=${size}`)
}

/** 转交候选人：服务端已排除本人与本单历史审批人（防环） */
export function listTransferCandidates(reportId) {
  const query = reportId ? `?reportId=${reportId}` : ''
  return get(`/api/v1/approvals/transfer-candidates${query}`)
}

export function approve(approvalId, comment) {
  return post(`/api/v1/approvals/${approvalId}/approve`, comment === undefined ? {} : { comment })
}

export function reject(approvalId, comment) {
  // 驳回意见必填（docs/03 §2.3），后端 40001 兜底
  return post(`/api/v1/approvals/${approvalId}/reject`, { comment })
}

export function transfer(approvalId, targetUserId, comment) {
  return post(`/api/v1/approvals/${approvalId}/transfer`, { targetUserId, comment })
}
