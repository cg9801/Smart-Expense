import { describe, expect, it } from 'vitest'
import { PAYMENT_STATUS, INVOICE_STATUS, REPORT_STATUS, statusTag } from '../status.js'

describe('statusTag', () => {
  it('已知单据状态返回中文标签与色调', () => {
    expect(statusTag('DRAFT')).toEqual({ label: '草稿', tone: 'gray' })
    expect(statusTag('APPROVING')).toEqual({ label: '审批中', tone: 'blue' })
  })

  it('未知状态回退为原始值 + gray，不抛错', () => {
    expect(statusTag('WHAT')).toEqual({ label: 'WHAT', tone: 'gray' })
  })

  it('付款单与发票状态字典齐全（docs/19 §2）', () => {
    expect(Object.keys(PAYMENT_STATUS)).toEqual(['CREATED', 'PAID', 'FAILED'])
    expect(Object.keys(INVOICE_STATUS)).toEqual(['PENDING', 'PROCESSING', 'SUCCESS', 'MANUAL', 'FAILED'])
    expect(REPORT_STATUS.PAID).toEqual({ label: '已付款', tone: 'gold' })
  })
})
