<script setup>
import { onMounted, reactive, ref } from 'vue'
import { listPayments, confirmPayment } from '../api/payment.js'
import { PAYMENT_STATUS } from '../constants/status.js'
import StatusTag from '../components/StatusTag.vue'

const rows = ref([])
const loading = ref(true)
const error = ref('')
const notice = ref('')
const page = reactive({ current: 1, size: 10, total: 0 })
const filter = reactive({ status: '', reportNo: '' })
const actingId = ref(null)

const statusOptions = Object.entries(PAYMENT_STATUS).map(([value, v]) => ({ value, label: v.label }))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listPayments({
      status: filter.status || undefined,
      reportNo: filter.reportNo.trim() || undefined,
      page: page.current,
      size: page.size
    })
    rows.value = data?.records || []
    page.total = data?.total || 0
  } catch (e) {
    error.value = e
    rows.value = []
  } finally {
    loading.value = false
  }
}

function search() {
  page.current = 1
  load()
}

// 二次确认：付款是真金白银的终态动作，点错没得撤（docs/02 §3 T6）
async function confirm(row) {
  if (!window.confirm(`确认付款 ${row.paymentNo}（¥${Number(row.amount ?? 0).toFixed(2)}）？确认后单据变为已付款。`)) {
    return
  }
  actingId.value = row.id
  error.value = ''
  notice.value = ''
  try {
    await confirmPayment(row.id)
    notice.value = `付款单 ${row.paymentNo} 已确认，单据进入已付款`
    load()
  } catch (e) {
    // 40901：别人刚确认过（并发），刷新即可看到最新状态
    if (e.code === 40901 || e.httpStatus === 409) {
      notice.value = '该付款单已处理，已刷新列表'
      load()
    } else {
      error.value = e
    }
  } finally {
    actingId.value = null
  }
}

function goPage(next) {
  if (next < 1 || next > Math.ceil(page.total / page.size)) return
  page.current = next
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">付款单</h2>
        <p class="page-sub">财务确认银行回执后触发 T6（APPROVED → PAID）；每单只有一张付款单</p>
      </div>
    </div>

    <div v-if="error" class="alert alert-error">{{ error.message }}</div>
    <div v-if="notice" class="alert alert-success">{{ notice }}</div>

    <div class="card">
      <div class="card-pad" style="padding-bottom: 0; display: flex; gap: 8px; flex-wrap: wrap">
        <select v-model="filter.status" class="select" style="width: 160px" @change="search">
          <option value="">全部状态</option>
          <option v-for="opt in statusOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <input
          v-model="filter.reportNo"
          class="input"
          style="width: 220px"
          placeholder="按报销单号搜索"
          @keyup.enter="search"
        />
        <button class="btn btn-outline btn-sm" @click="search">查询</button>
      </div>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>付款单号</th><th>报销单号</th><th>标题</th><th>收款人</th>
              <th class="num">金额</th><th>状态</th><th>创建时间</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="8" style="text-align: center; color: var(--c-text-3)">加载中…</td>
            </tr>
            <tr v-else-if="!rows.length">
              <td colspan="8">
                <div class="empty">
                  <div class="empty-icon">💸</div>
                  {{ error ? '加载失败' : '没有待付款的单据' }}
                </div>
              </td>
            </tr>
            <tr v-else v-for="r in rows" :key="r.id">
              <td style="font-family: monospace; font-size: 13px">{{ r.paymentNo || r.id }}</td>
              <td>
                <router-link :to="{ name: 'report-detail', params: { id: r.reportId } }">
                  {{ r.reportNo || r.reportId }}
                </router-link>
              </td>
              <td>{{ r.title }}</td>
              <td>{{ r.payeeName }}</td>
              <td class="num">¥ {{ Number(r.amount ?? 0).toFixed(2) }}</td>
              <td><StatusTag :status="r.status" kind="payment" /></td>
              <td style="color: var(--c-text-2)">{{ r.createdAt }}</td>
              <td>
                <button
                  v-if="r.status === 'CREATED'"
                  class="btn btn-primary btn-sm"
                  :disabled="actingId === r.id"
                  @click="confirm(r)"
                >
                  {{ actingId === r.id ? '确认中…' : '确认付款' }}
                </button>
                <span v-else style="color: var(--c-text-3); font-size: 13px">
                  {{ r.paidAt || '—' }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="rows.length" class="card-pad pager">
        <span>共 {{ page.total }} 条 · 第 {{ page.current }} / {{ Math.max(1, Math.ceil(page.total / page.size)) }} 页</span>
        <span style="display: flex; gap: 8px">
          <button class="btn btn-outline btn-sm" :disabled="page.current <= 1" @click="goPage(page.current - 1)">上一页</button>
          <button class="btn btn-outline btn-sm" :disabled="page.current * page.size >= page.total" @click="goPage(page.current + 1)">下一页</button>
        </span>
      </div>
    </div>
  </div>
</template>
