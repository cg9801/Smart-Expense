<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getDetail, getTimeline, submitReport, withdrawReport } from '../api/report.js'
import { fetchInvoiceBlobUrl } from '../api/invoice.js'
import { REPORT_STATUS } from '../constants/status.js'
import StatusTag from '../components/StatusTag.vue'

const route = useRoute()
const reportId = computed(() => Number(route.params.id))

const detail = ref(null)
const timeline = ref([])
const timelineError = ref('')
const loading = ref(true)
const error = ref(null)
const acting = ref(false)

// 发票预览：<img> 带不了 Authorization 头，统一走 fetch blob
const invoiceUrls = ref({})
const invoiceLoading = ref(false)

// 动作可用性只信后端 availableActions（docs/03 §3.1，前端不推断状态机）
const can = computed(() => {
  const actions = detail.value?.availableActions || []
  return {
    submit: actions.includes('SUBMIT'),
    withdraw: actions.includes('WITHDRAW'),
    edit: actions.includes('EDIT')
  }
})

// aiAdvice.reasons 是 JSON 数组字符串（AiAdviceVO 注释：前端直接渲染），
// 直接 v-for 字符串会逐字符遍历，必须在渲染前解析成数组
const adviceReasons = computed(() => parseList(detail.value?.aiAdvice?.reasons))

function parseList(raw) {
  if (Array.isArray(raw)) return raw
  if (typeof raw !== 'string' || !raw.trim()) return []
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : [String(arr)]
  } catch {
    return [raw]
  }
}

// 审计日志里的动作与状态码统一翻成中文（docs/02 §8）
const ACTION_LABELS = {
  SUBMIT: '提交', WITHDRAW: '撤回', APPROVE: '通过', REJECT: '驳回',
  TRANSFER: '转交', PAY: '付款', RECONCILE: '对账'
}
function actionLabel(action) {
  return ACTION_LABELS[action] || action
}
function statusLabel(status) {
  return REPORT_STATUS[status]?.label || status
}
// detail 是 JSON 字符串（后端原样返回），审批意见在 detail.comment
function commentOf(t) {
  if (!t.detail) return ''
  try {
    const d = typeof t.detail === 'string' ? JSON.parse(t.detail) : t.detail
    return d?.comment || ''
  } catch {
    return ''
  }
}

async function load() {
  loading.value = true
  try {
    detail.value = await getDetail(reportId.value)
    error.value = null
    loadTimeline()
    loadInvoiceFiles()
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}

async function loadInvoiceFiles() {
  const list = detail.value?.invoices || []
  if (!list.length) return
  invoiceLoading.value = true
  for (const inv of list) {
    try {
      invoiceUrls.value[inv.id] = await fetchInvoiceBlobUrl(inv.id)
    } catch {
      invoiceUrls.value[inv.id] = ''
    }
  }
  invoiceLoading.value = false
}

function isPdf(name) {
  return /\.pdf$/i.test(name || '')
}

function openInvoice(id) {
  const url = invoiceUrls.value[id]
  if (url) window.open(url, '_blank')
}

function revokeInvoiceUrls() {
  Object.values(invoiceUrls.value).forEach(url => { if (url) URL.revokeObjectURL(url) })
  invoiceUrls.value = {}
}

onBeforeUnmount(revokeInvoiceUrls)

async function loadTimeline() {
  timelineError.value = ''
  try {
    timeline.value = (await getTimeline(reportId.value)) || []
  } catch (e) {
    timeline.value = []
    timelineError.value = e.message
  }
}

async function doSubmit() {
  if (!confirm('提交后进入审批流并冻结部门预算，确定？')) return
  acting.value = true
  try {
    detail.value = await submitReport(reportId.value)
    alert('提交成功，等待审批')
    loadTimeline()
  } catch (e) {
    // 40902 预算不足时展示可用额度（docs/03 §5）
    if (e.code === 40902) {
      alert(`提交失败：${e.message}${e.data?.available ? `（可用 ¥ ${e.data.available}）` : ''}`)
    } else if (e.code === 40901) {
      alert(`状态冲突：${e.message}，即将刷新最新状态`)
      load()
    } else {
      alert(`提交失败：${e.message}${e.traceId ? `（traceId: ${e.traceId}）` : ''}`)
    }
  } finally {
    acting.value = false
  }
}

async function doWithdraw() {
  if (!confirm('撤回后单据回到草稿，可继续编辑，确定？')) return
  acting.value = true
  try {
    detail.value = await withdrawReport(reportId.value)
    loadTimeline()
  } catch (e) {
    if (e.code === 40901) { alert(`撤回失败：${e.message}`); load() }
    else alert(`撤回失败：${e.message}`)
  } finally {
    acting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">{{ detail?.title || '报销单详情' }}</h2>
        <p class="page-sub" v-if="detail">
          {{ detail.reportNo }} · 第 {{ detail.currentStep || 0 }} / {{ detail.totalSteps || 0 }} 级审批
        </p>
      </div>
      <div class="action-box">
        <router-link v-if="can.edit" class="btn btn-outline"
                     :to="{ name: 'report-edit', params: { id: reportId } }">编辑</router-link>
        <button v-if="can.withdraw" class="btn btn-outline" :disabled="acting" @click="doWithdraw">撤回</button>
        <button v-if="can.submit" class="btn btn-primary" :disabled="acting" @click="doSubmit">提交审批</button>
        <router-link class="btn btn-outline" :to="{ name: 'report-list' }">← 返回列表</router-link>
      </div>
    </div>

    <div v-if="error" class="alert alert-error">
      {{ error.message }}
      <span v-if="error.traceId" class="trace">traceId: {{ error.traceId }}</span>
    </div>

    <div v-if="loading" class="card card-pad" style="color: var(--c-text-3)">加载中…</div>

    <div v-if="detail" class="detail-grid">
      <div class="card card-pad">
        <div class="kv-grid">
          <div><div class="kv-label">状态</div><div class="kv-value"><StatusTag :status="detail.status" /></div></div>
          <div><div class="kv-label">费用类型</div><div class="kv-value">{{ detail.expenseType }}</div></div>
          <div><div class="kv-label">总金额</div><div class="kv-value" style="color: var(--c-primary); font-variant-numeric: tabular-nums">¥ {{ Number(detail.amountTotal ?? 0).toFixed(2) }}</div></div>
          <div><div class="kv-label">创建时间</div><div class="kv-value">{{ detail.createdAt }}</div></div>
          <div v-if="detail.remark"><div class="kv-label">备注</div><div class="kv-value">{{ detail.remark }}</div></div>
        </div>
      </div>

      <div class="card card-pad" v-if="detail.aiAdvice">
        <h3 style="margin: 0 0 10px; font-size: 15px">🤖 AI 审批建议（仅供参考，不阻断）</h3>
        <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 8px">
          <StatusTag :status="detail.aiAdvice.riskLevel" />
          <span style="color: var(--c-text-2); font-size: 13px" v-if="detail.aiAdvice.confidence != null">
            置信度 {{ detail.aiAdvice.confidence }}
          </span>
        </div>
        <ul style="margin: 0; padding-left: 18px; color: var(--c-text-2)">
          <li v-for="(r, i) in adviceReasons" :key="i">{{ r }}</li>
        </ul>
      </div>
      <div class="card card-pad" v-else-if="detail.status !== 'DRAFT'">
        <span style="color: var(--c-text-3)">暂无 AI 建议</span>
      </div>

      <div class="card card-pad">
        <h3 style="margin: 0 0 12px; font-size: 15px">费用明细</h3>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr><th>日期</th><th>类别</th><th>说明</th><th class="num">金额</th></tr>
            </thead>
            <tbody>
              <tr v-for="(it, i) in detail.items || []" :key="i">
                <td>{{ it.expenseDate }}</td>
                <td>{{ it.category }}</td>
                <td>{{ it.description || '—' }}</td>
                <td class="num">¥ {{ Number(it.amount ?? 0).toFixed(2) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="card card-pad" v-if="detail.invoices?.length">
        <h3 style="margin: 0 0 12px; font-size: 15px">发票（{{ detail.invoices.length }}）</h3>
        <div v-if="invoiceLoading" style="color: var(--c-text-3); font-size: 13px">发票加载中…</div>
        <div style="display: flex; gap: 12px; flex-wrap: wrap">
          <div v-for="inv in detail.invoices" :key="inv.id" style="width: 140px">
            <template v-if="isPdf(inv.originalName)">
              <div style="width: 100%; height: 100px; background: var(--c-bg); border: 1px solid var(--c-border); border-radius: 6px; display: flex; align-items: center; justify-content: center; color: var(--c-text-2); cursor: pointer; font-size: 13px"
                   @click="openInvoice(inv.id)">📄 PDF</div>
            </template>
            <template v-else>
              <img v-if="invoiceUrls[inv.id]" :src="invoiceUrls[inv.id]" alt="发票"
                   style="width: 100%; height: 100px; object-fit: cover; border: 1px solid var(--c-border); border-radius: 6px; cursor: pointer"
                   @click="openInvoice(inv.id)" />
              <div v-else style="width: 100%; height: 100px; background: var(--c-bg); border: 1px solid var(--c-border); border-radius: 6px; display: flex; align-items: center; justify-content: center; color: var(--c-text-3); font-size: 12px">
                加载中…
              </div>
            </template>
            <div style="font-size: 12px; color: var(--c-text-2); margin-top: 4px; word-break: break-all">
              {{ inv.originalName || '发票' }}
            </div>
          </div>
        </div>
      </div>

      <div class="card card-pad">
        <h3 style="margin: 0 0 12px; font-size: 15px">审批轨迹</h3>
        <div v-if="timelineError" style="color: var(--c-text-3); font-size: 13px">
          暂无轨迹（{{ timelineError }}）
        </div>
        <div v-else-if="!timeline.length" style="color: var(--c-text-3); font-size: 13px">暂无轨迹</div>
        <ul v-else class="timeline">
          <li v-for="(t, i) in timeline" :key="i"
              :class="{ 'tl-pass': t.toStatus && t.toStatus !== 'REJECTED', 'tl-fail': t.toStatus === 'REJECTED' }">
            <div class="tl-time">{{ t.createdAt || t.createTime }}</div>
            <div class="tl-text">
              <b>{{ t.operatorName || t.operatorId || '系统' }}</b>
              {{ actionLabel(t.action) }}
              <template v-if="t.fromStatus && t.toStatus">
                ：{{ statusLabel(t.fromStatus) }} → {{ statusLabel(t.toStatus) }}
              </template>
              <div v-if="commentOf(t)" style="color: var(--c-text-2); font-size: 13px">
                “{{ commentOf(t) }}”
              </div>
            </div>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>
