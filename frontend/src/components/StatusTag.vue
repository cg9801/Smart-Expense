<script setup>
import { computed } from 'vue'
import { statusTag, REPORT_STATUS, NODE_STATUS, PAYMENT_STATUS, INVOICE_STATUS } from '../constants/status.js'

const props = defineProps({
  status: { type: String, required: true },
  // report = 单据状态；node = 审批节点；payment = 付款单；invoice = 发票识别状态
  kind: { type: String, default: 'report' }
})

const MAPS = {
  report: REPORT_STATUS,
  node: NODE_STATUS,
  payment: PAYMENT_STATUS,
  invoice: INVOICE_STATUS
}

const tag = computed(() => statusTag(props.status, MAPS[props.kind] || REPORT_STATUS))
</script>

<template>
  <span class="tag" :class="`tag-${tag.tone}`">{{ tag.label }}</span>
</template>
