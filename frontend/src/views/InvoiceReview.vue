<script setup>
import { onMounted, reactive, ref } from 'vue'
import { listInvoices, reviewInvoice, retryInvoice } from '../api/invoice.js'
import StatusTag from '../components/StatusTag.vue'

const rows = ref([])
const loading = ref(true)
const error = ref('')
const notice = ref('')
const page = reactive({ current: 1, size: 10, total: 0 })
const filter = reactive({ status: 'MANUAL' })

const statusOptions = [
  { value: 'MANUAL', label: '待复核' },
  { value: 'FAILED', label: '识别失败' },
  { value: 'SUCCESS', label: '已识别' },
  { value: 'PENDING', label: '识别中' }
]

// 复核弹窗：只编辑发票本身字段，不回写报销单（docs/11 §4 铁律）
const dialog = reactive({
  visible: false,
  row: null,
  form: { invoiceCode: '', invoiceNo: '', invoiceDate: '', amount: '', taxAmount: '' }
})
const dialogError = ref('')
const acting = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listInvoices({ ocrStatus: filter.status, page: page.current, size: page.size })
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

function openReview(row) {
  dialog.row = row
  dialog.form = {
    invoiceCode: row.invoiceCode || '',
    invoiceNo: row.invoiceNo || '',
    invoiceDate: row.invoiceDate || '',
    amount: row.amount ?? '',
    taxAmount: row.taxAmount ?? ''
  }
  dialogError.value = ''
  dialog.visible = true
}

async function submitReview() {
  if (!dialog.form.amount || Number(dialog.form.amount) <= 0) {
    dialogError.value = '发票金额必须大于 0'
    return
  }
  acting.value = true
  dialogError.value = ''
  try {
    await reviewInvoice(dialog.row.id, {
      invoiceCode: dialog.form.invoiceCode.trim() || null,
      invoiceNo: dialog.form.invoiceNo.trim() || null,
      invoiceDate: dialog.form.invoiceDate || null,
      amount: Number(dialog.form.amount),
      taxAmount: dialog.form.taxAmount === '' ? null : Number(dialog.form.taxAmount)
    })
    dialog.visible = false
    notice.value = `发票 ${dialog.row.id} 已复核，状态置为已识别`
    load()
  } catch (e) {
    dialogError.value = e.message
  } finally {
    acting.value = false
  }
}

async function retry(row) {
  if (!window.confirm(`重新识别发票 ${row.id}？会再次调用识别器（可能产生模型费用）。`)) return
  try {
    await retryInvoice(row.id)
    notice.value = `发票 ${row.id} 已重新入队识别`
    load()
  } catch (e) {
    // 并发下可能已被别的财务处理：40901 刷新即可
    if (e.code === 40901 || e.httpStatus === 409) {
      notice.value = '该发票状态已变化，已刷新'
      load()
    } else {
      error.value = e
    }
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
        <h2 class="page-title">发票复核</h2>
        <p class="page-sub">识别失败或置信度不足的发票在这里人工修正；识别结果只进发票，不自动写报销单</p>
      </div>
    </div>

    <div v-if="error" class="alert alert-error">{{ error.message }}</div>
    <div v-if="notice" class="alert alert-success">{{ notice }}</div>

    <div class="card">
      <div class="card-pad" style="padding-bottom: 0">
        <select v-model="filter.status" class="select" style="width: 160px" @change="search">
          <option v-for="opt in statusOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
      </div>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>发票</th><th>发票号</th><th class="num">金额</th><th>状态</th>
              <th>识别器</th><th class="num">置信度</th><th>失败原因</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="8" style="text-align: center; color: var(--c-text-3)">加载中…</td>
            </tr>
            <tr v-else-if="!rows.length">
              <td colspan="8">
                <div class="empty">
                  <div class="empty-icon">🧾</div>
                  {{ error ? '加载失败' : '这个状态下没有发票' }}
                </div>
              </td>
            </tr>
            <tr v-else v-for="r in rows" :key="r.id">
              <td style="font-family: monospace; font-size: 13px">{{ r.originalName || r.id }}</td>
              <td>{{ r.invoiceNo || '—' }}</td>
              <td class="num">¥ {{ Number(r.amount ?? 0).toFixed(2) }}</td>
              <td><StatusTag :status="r.ocrStatus" kind="invoice" /></td>
              <td style="color: var(--c-text-2)">{{ r.recognizer || '—' }}</td>
              <td class="num">{{ r.confidence == null ? '—' : Number(r.confidence).toFixed(2) }}</td>
              <td style="color: var(--c-danger); font-size: 12px">{{ r.errorMsg || '—' }}</td>
              <td>
                <div style="display: flex; gap: 6px">
                  <button
                    v-if="r.ocrStatus === 'MANUAL' || r.ocrStatus === 'SUCCESS'"
                    class="btn btn-primary btn-sm"
                    @click="openReview(r)"
                  >
                    复核
                  </button>
                  <button
                    v-if="r.ocrStatus === 'MANUAL' || r.ocrStatus === 'FAILED'"
                    class="btn btn-outline btn-sm"
                    @click="retry(r)"
                  >
                    重试识别
                  </button>
                </div>
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

    <!-- 复核弹窗 -->
    <div v-if="dialog.visible" class="modal-mask" @click.self="dialog.visible = false">
      <div class="modal">
        <h3 class="modal-title">🧾 复核发票 #{{ dialog.row?.id }}</h3>
        <div v-if="dialogError" class="alert alert-error" style="margin-bottom: 12px">{{ dialogError }}</div>

        <div class="form-item">
          <label class="form-label">发票代码</label>
          <input v-model.trim="dialog.form.invoiceCode" class="input" placeholder="选填" />
        </div>
        <div class="form-item">
          <label class="form-label">发票号码</label>
          <input v-model.trim="dialog.form.invoiceNo" class="input" placeholder="选填" />
        </div>
        <div class="form-item">
          <label class="form-label">开票日期</label>
          <input v-model="dialog.form.invoiceDate" type="date" class="input" />
        </div>
        <div class="form-item">
          <label class="form-label">金额<span class="req">*</span></label>
          <input v-model="dialog.form.amount" type="number" step="0.01" min="0.01" class="input" />
        </div>
        <div class="form-item">
          <label class="form-label">税额</label>
          <input v-model="dialog.form.taxAmount" type="number" step="0.01" min="0" class="input" />
        </div>

        <div class="modal-actions">
          <button class="btn btn-outline" @click="dialog.visible = false">取消</button>
          <button class="btn btn-primary" :disabled="acting" @click="submitReview">
            {{ acting ? '保存中…' : '确认复核' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
