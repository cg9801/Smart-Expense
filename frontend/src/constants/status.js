/**
 * 状态 → 展示映射（状态语义见 docs/02-状态机.md §1 / §4）。
 * 前端只负责"翻译"，不负责推断：按钮显隐一律看后端 availableActions（docs/03 §3.1）。
 */
export const REPORT_STATUS = {
  DRAFT:     { label: '草稿',   tone: 'gray' },
  PENDING:   { label: '待审批', tone: 'yellow' },
  APPROVING: { label: '审批中', tone: 'blue' },
  APPROVED:  { label: '已通过', tone: 'green' },
  REJECTED:  { label: '已驳回', tone: 'red' },
  PAID:      { label: '已付款', tone: 'gold' }
}

export const NODE_STATUS = {
  PENDING:     { label: '待处理', tone: 'yellow' },
  APPROVED:    { label: '已通过', tone: 'green' },
  REJECTED:    { label: '已驳回', tone: 'red' },
  TRANSFERRED: { label: '已转交', tone: 'blue' },
  SKIPPED:     { label: '被跳过', tone: 'gray' },
  CANCELLED:   { label: '已作废', tone: 'gray' }
}

/** 付款单状态（docs/03 §2.4 GET /payments）；CREATED 才能在页面确认付款 */
export const PAYMENT_STATUS = {
  CREATED: { label: '待付款', tone: 'yellow' },
  PAID:    { label: '已付款', tone: 'green' },
  FAILED:  { label: '失败',   tone: 'red' }
}

/** 发票识别状态（docs/11 §3.1）：MANUAL/FAILED 进复核队列 */
export const INVOICE_STATUS = {
  PENDING:    { label: '识别中',  tone: 'yellow' },
  PROCESSING: { label: '识别中',  tone: 'yellow' },
  SUCCESS:    { label: '已识别',  tone: 'green' },
  MANUAL:     { label: '待复核',  tone: 'gold' },
  FAILED:     { label: '识别失败', tone: 'red' }
}

export function statusTag(status, map = REPORT_STATUS) {
  return map[status] || { label: status, tone: 'gray' }
}

export const EXPENSE_TYPES = ['差旅', '交通', '餐饮', '办公', '通信', '培训', '其他']
export const ITEM_CATEGORIES = ['交通', '住宿', '餐饮', '办公', '通信', '其他']
