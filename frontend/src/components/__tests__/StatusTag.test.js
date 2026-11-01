import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StatusTag from '../StatusTag.vue'

describe('StatusTag', () => {
  it('单据状态：DRAFT → 草稿 + tag-gray', () => {
    const wrapper = mount(StatusTag, { props: { status: 'DRAFT' } })
    expect(wrapper.text()).toBe('草稿')
    expect(wrapper.classes()).toContain('tag-gray')
  })

  it('付款单状态：kind=payment 用 PAYMENT_STATUS 映射', () => {
    const wrapper = mount(StatusTag, { props: { status: 'PAID', kind: 'payment' } })
    expect(wrapper.text()).toBe('已付款')
    expect(wrapper.classes()).toContain('tag-green')
  })

  it('发票状态：kind=invoice 用 INVOICE_STATUS 映射', () => {
    const wrapper = mount(StatusTag, { props: { status: 'MANUAL', kind: 'invoice' } })
    expect(wrapper.text()).toBe('待复核')
  })

  it('未知状态原样显示，不抛错', () => {
    const wrapper = mount(StatusTag, { props: { status: 'WHATEVER' } })
    expect(wrapper.text()).toBe('WHATEVER')
  })
})
