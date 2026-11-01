<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createDraft, updateDraft, getDetail } from '../api/report.js'
import { uploadInvoice, fetchInvoice, fetchInvoiceBlobUrl } from '../api/invoice.js'
import { useAuthStore } from '../stores/auth.js'
import { EXPENSE_TYPES, ITEM_CATEGORIES } from '../constants/status.js'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const isEdit = computed(() => route.name === 'report-edit')
const reportId = computed(() => Number(route.params.id))

const form = reactive({
  title: '',
  expenseType: '差旅',
  remark: '',
  items: [newItem()]
})
const loading = ref(false)
const saving = ref(false)
const error = ref('')

function newItem() {
  // invoiceId 在建单时随明细提交，服务端校验必须本人上传且未绑定（docs/11 §3.4）
  // invoiceStatus/needsReview 是识别回填用的本地态，不进 DTO（docs/19 §1）
  return {
    expenseDate: '', category: '交通', amount: '', description: '',
    invoiceId: null, invoiceName: '', uploading: false,
    invoiceStatus: null, invoiceConfidence: null, needsReview: [],
    invoiceRecognizer: null,
    // 预览与识别摘要（docs/19 §1.6）：blob URL 由 fetchInvoiceBlobUrl 生成，卸载时释放
    invoicePreviewUrl: null, recognizedNo: '', recognizedDate: ''
  }
}

// 金额合计：明细相加，服务端会重新校验（ReportCreateDTO 注释：不信任前端总额）
const total = computed(() =>
  form.items.reduce((sum, it) => sum + (Number(it.amount) || 0), 0)
)

function addItem() { form.items.push(newItem()) }
function removeItem(i) {
  if (form.items.length > 1) form.items.splice(i, 1)
}

function validate() {
  if (!form.title.trim()) return '标题不能为空'
  if (!form.items.length) return '至少一条明细'
  for (const [i, it] of form.items.entries()) {
    if (!it.expenseDate) return `第 ${i + 1} 条明细的日期没填`
    if (!it.category) return `第 ${i + 1} 条明细的类别没选`
    if (!(Number(it.amount) > 0)) return `第 ${i + 1} 条明细的金额要大于 0`
  }
  if (total.value <= 0) return '合计金额要大于 0'
  return ''
}

async function loadForEdit() {
  loading.value = true
  try {
    const d = await getDetail(reportId.value)
    const invMap = Object.fromEntries((d.invoices || []).map(x => [x.id, x]))
    form.title = d.title
    form.expenseType = d.expenseType
    form.remark = d.remark || ''
    form.items = (d.items || []).map(it => {
      const inv = it.invoiceId ? invMap[it.invoiceId] : null
      return {
        expenseDate: it.expenseDate || '',
        category: it.category,
        amount: it.amount,
        description: it.description || '',
        invoiceId: it.invoiceId || null,
        invoiceName: inv ? inv.originalName : '',
        uploading: false,
        invoiceStatus: inv?.ocrStatus || null,
        invoiceConfidence: inv?.confidence ?? null,
        invoiceRecognizer: inv?.recognizer || null,
        needsReview: inv?.needsReview || [],
        invoicePreviewUrl: null,
        recognizedNo: '',
        recognizedDate: ''
      }
    })
    if (!form.items.length) form.items.push(newItem())
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}

/** 选文件即上传，拿到 invoiceId 随明细一起提交；上传失败不影响已填内容 */
async function onPickInvoice(i, ev) {
  const file = ev.target.files?.[0]
  ev.target.value = '' // 允许再次选择同一个文件
  if (!file) return
  const item = form.items[i]
  item.uploading = true
  item.invoiceStatus = null
  releasePreview(item)
  try {
    const inv = await uploadInvoice(file)
    item.invoiceId = inv.id
    item.invoiceName = inv.originalName
    // 拿到 blob URL 才能显示图片：文件接口需要 Bearer，<img src> 带不了头（docs/19 §1.6）
    try {
      item.invoicePreviewUrl = await fetchInvoiceBlobUrl(inv.id)
    } catch {
      item.invoicePreviewUrl = null
    }
    pollInvoice(i, inv.id)
  } catch (e) {
    alert(`发票上传失败：${e.message}`)
  } finally {
    item.uploading = false
  }
}

/** 释放上一张图的 blob URL，避免重复上传时泄漏内存 */
function releasePreview(item) {
  if (item.invoicePreviewUrl) {
    URL.revokeObjectURL(item.invoicePreviewUrl)
    item.invoicePreviewUrl = null
  }
}

onBeforeUnmount(() => {
  form.items.forEach(releasePreview)
})

/**
 * 轮询识别结果（docs/19 §1）：2s 一次、最多 15 次。
 * 只预填表单，不自动保存——用户点「保存草稿」才写库（docs/11 §4 铁律）。
 */
async function pollInvoice(i, invoiceId) {
  const item = form.items[i]
  item.invoiceStatus = 'PROCESSING'
  for (let n = 0; n < 15; n++) {
    await new Promise(resolve => setTimeout(resolve, 2000))
    let inv
    try {
      inv = await fetchInvoice(invoiceId)
    } catch {
      item.invoiceStatus = 'FAILED'
      return
    }
    item.invoiceStatus = inv.ocrStatus
    item.invoiceConfidence = inv.confidence ?? null
    item.invoiceRecognizer = inv.recognizer || null
    item.needsReview = inv.needsReview || []
    if (inv.ocrStatus === 'SUCCESS') {
      applyRecognizedFields(item, inv.fields || [])
      return
    }
    if (inv.ocrStatus === 'MANUAL' || inv.ocrStatus === 'FAILED') {
      return
    }
  }
  item.invoiceStatus = 'TIMEOUT'
}

/** 字段映射（docs/19 §1.3）：只填空值，用户手填过的内容不覆盖 */
function applyRecognizedFields(item, fields) {
  const map = Object.fromEntries(fields.map(f => [f.name, f]))
  item.recognizedNo = [map.invoiceCode?.value, map.invoiceNo?.value].filter(Boolean).join(' ')
  item.recognizedDate = map.invoiceDate?.value || ''
  if (map.amount && !item.amount) {
    const amount = Number(map.amount.value)
    if (amount > 0) item.amount = amount.toFixed(2)
  }
  if (map.invoiceDate && !item.expenseDate && map.invoiceDate.value) {
    item.expenseDate = map.invoiceDate.value
  }
  if (!item.description) {
    const code = map.invoiceCode?.value
    const no = map.invoiceNo?.value
    if (code || no) item.description = '发票号：' + [code, no].filter(Boolean).join(' ')
  }
}

// 大图预览（点击缩略图打开，点遮罩关闭）
const previewUrl = ref('')

async function save() {
  error.value = ''
  const msg = validate()
  if (msg) { error.value = { message: msg } ; return }

  saving.value = true
  try {
    // applicantId / applicantDeptId 从登录态取，不留给用户填（ReportCreateDTO 契约）
    const dto = {
      applicantId: auth.user.id,
      applicantDeptId: auth.user.deptId,
      title: form.title.trim(),
      expenseType: form.expenseType,
      remark: form.remark.trim(),
      items: form.items.map(it => ({
        expenseDate: it.expenseDate,
        category: it.category,
        amount: Number(it.amount),
        description: (it.description || '').trim(),
        invoiceId: it.invoiceId || null
      }))
    }
    const saved = isEdit.value
      ? await updateDraft(reportId.value, dto)
      : await createDraft(dto)
    router.push({ name: 'report-detail', params: { id: saved.id } })
  } catch (e) {
    error.value = e
  } finally {
    saving.value = false
  }
}

onMounted(() => { if (isEdit.value) loadForEdit() })
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">{{ isEdit ? '编辑报销单' : '新建报销单' }}</h2>
        <p class="page-sub">保存后为草稿，提交时才进入审批并冻结预算（docs/02 T1）</p>
      </div>
      <router-link :to="{ name: 'report-list' }" class="btn btn-outline">← 返回列表</router-link>
    </div>

    <div v-if="error" class="alert alert-error">
      {{ error.message }}
    </div>

    <div class="card card-pad">
      <form @submit.prevent="save">
        <div class="form-row">
          <div class="form-item">
            <label class="form-label">标题<span class="req">*</span></label>
            <input v-model.trim="form.title" class="input" maxlength="128" placeholder="例如：10 月杭州出差报销" />
          </div>
          <div class="form-item" style="max-width: 200px">
            <label class="form-label">费用类型<span class="req">*</span></label>
            <select v-model="form.expenseType" class="select">
              <option v-for="t in EXPENSE_TYPES" :key="t" :value="t">{{ t }}</option>
            </select>
          </div>
        </div>
        <div class="form-item">
          <label class="form-label">备注</label>
          <textarea v-model.trim="form.remark" class="textarea" maxlength="512" placeholder="选填，最多 512 字"></textarea>
        </div>

        <h3 style="font-size: 15px; margin: 8px 0 12px">费用明细</h3>
        <div v-for="(item, i) in form.items" :key="i"
             class="card" style="padding: 14px; margin-bottom: 12px; box-shadow: none">
          <div class="form-row">
            <div class="form-item">
              <label class="form-label">费用日期<span class="req">*</span></label>
              <input v-model="item.expenseDate" type="date" class="input"
                     :class="{ 'input-warn': item.needsReview?.includes('invoiceDate') }" />
            </div>
            <div class="form-item" style="max-width: 160px">
              <label class="form-label">类别<span class="req">*</span></label>
              <select v-model="item.category" class="select">
                <option v-for="c in ITEM_CATEGORIES" :key="c" :value="c">{{ c }}</option>
              </select>
            </div>
            <div class="form-item" style="max-width: 160px">
              <label class="form-label">金额（元）<span class="req">*</span></label>
              <input v-model="item.amount" type="number" min="0.01" step="0.01" class="input"
                     :class="{ 'input-warn': item.needsReview?.includes('amount') }"
                     style="font-variant-numeric: tabular-nums" />
            </div>
          </div>
          <div class="form-row" style="align-items: flex-end">
            <div class="form-item" style="margin-bottom: 0">
              <label class="form-label">说明</label>
              <input v-model.trim="item.description" class="input" placeholder="选填"
                     :class="{ 'input-warn': item.needsReview?.includes('invoiceNo') || item.needsReview?.includes('invoiceCode') }" />
            </div>
            <div class="form-item" style="margin-bottom: 0; flex: 0 0 auto">
              <label class="form-label">发票</label>
              <div style="display: flex; align-items: center; gap: 8px">
                <!-- label 包隐藏 input：点击按钮即选文件，选完立刻上传 -->
                <label class="btn btn-outline btn-sm" style="margin: 0; cursor: pointer">
                  {{ item.uploading ? '上传中…' : item.invoiceId ? '重新上传' : '上传发票' }}
                  <input type="file" accept="image/*,.pdf" style="display: none"
                         :disabled="item.uploading" @change="onPickInvoice(i, $event)" />
                </label>
                <span v-if="item.invoiceName" style="font-size: 12px; color: var(--c-text-2); max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">
                  📎 {{ item.invoiceName }}
                </span>
                <img v-if="item.invoicePreviewUrl" :src="item.invoicePreviewUrl" class="invoice-thumb"
                     alt="发票缩略图" title="点击查看大图" @click="previewUrl = item.invoicePreviewUrl" />
                <span v-if="item.invoiceStatus === 'PROCESSING' || item.invoiceStatus === 'PENDING'"
                      style="font-size: 12px; color: #b45309">识别中…</span>
                <span v-else-if="item.invoiceStatus === 'SUCCESS'"
                      style="font-size: 12px; color: var(--c-success)">
                  已识别{{ item.invoiceConfidence != null ? `（置信度 ${Number(item.invoiceConfidence).toFixed(2)}）` : '' }}
                  <span v-if="item.invoiceRecognizer === 'mock'" style="color: #b45309">· 演示数据，非真实识别</span>
                </span>
                <span v-else-if="item.invoiceStatus === 'MANUAL'"
                      style="font-size: 12px; color: #b45309">待人工复核，可先手填</span>
                <span v-else-if="item.invoiceStatus === 'FAILED'"
                      style="font-size: 12px; color: var(--c-danger)">识别失败，可到发票复核页重试</span>
                <span v-else-if="item.invoiceStatus === 'TIMEOUT'"
                      style="font-size: 12px; color: var(--c-text-3)">识别较慢，稍后刷新</span>
              </div>
            </div>
            <button type="button" class="btn btn-outline btn-sm" style="margin-bottom: 4px"
                    :disabled="form.items.length <= 1" @click="removeItem(i)">删除</button>
          </div>
          <!-- 识别摘要：和图片对照用（docs/19 §1.6），低置信度字段标黄 -->
          <div v-if="item.invoiceStatus === 'SUCCESS'" class="invoice-meta">
            <span>识别结果：</span>
            <span :class="{ warn: item.needsReview?.includes('amount') }">金额 {{ item.amount || '—' }}</span>
            <span :class="{ warn: item.needsReview?.includes('invoiceDate') }">
              日期 {{ item.recognizedDate || item.expenseDate || '—' }}
            </span>
            <span :class="{ warn: item.needsReview?.includes('invoiceNo') || item.needsReview?.includes('invoiceCode') }">
              号码 {{ item.recognizedNo || '—' }}
            </span>
            <span v-if="item.needsReview?.length" class="warn">黄色字段置信度较低，请对照图片核对</span>
          </div>
        </div>

        <div style="display: flex; justify-content: space-between; align-items: center; margin: 12px 0 20px">
          <button type="button" class="btn btn-outline" @click="addItem">+ 添加一条明细</button>
          <div style="font-size: 15px; font-weight: 600">
            合计：<span class="num" style="color: var(--c-primary)">¥ {{ total.toFixed(2) }}</span>
          </div>
        </div>

        <div style="display: flex; gap: 12px; justify-content: flex-end">
          <button class="btn btn-primary" :disabled="saving || loading" type="submit">
            {{ saving ? '保存中…' : '保存草稿' }}
          </button>
        </div>
      </form>
    </div>

    <!-- 发票大图预览：点击遮罩关闭（docs/19 §1.6） -->
    <div v-if="previewUrl" class="invoice-preview-mask" @click="previewUrl = ''">
      <img :src="previewUrl" alt="发票大图" />
      <span class="invoice-preview-hint">点击空白处关闭</span>
    </div>
  </div>
</template>
