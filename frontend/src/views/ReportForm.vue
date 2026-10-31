<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createDraft, updateDraft, getDetail } from '../api/report.js'
import { uploadInvoice } from '../api/invoice.js'
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
  return { expenseDate: '', category: '交通', amount: '', description: '', invoiceId: null, invoiceName: '', uploading: false }
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
    form.items = (d.items || []).map(it => ({
      expenseDate: it.expenseDate || '',
      category: it.category,
      amount: it.amount,
      description: it.description || '',
      invoiceId: it.invoiceId || null,
      invoiceName: it.invoiceId && invMap[it.invoiceId] ? invMap[it.invoiceId].originalName : '',
      uploading: false
    }))
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
  try {
    const inv = await uploadInvoice(file)
    item.invoiceId = inv.id
    item.invoiceName = inv.originalName
  } catch (e) {
    alert(`发票上传失败：${e.message}`)
  } finally {
    item.uploading = false
  }
}

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
              <input v-model="item.expenseDate" type="date" class="input" />
            </div>
            <div class="form-item" style="max-width: 160px">
              <label class="form-label">类别<span class="req">*</span></label>
              <select v-model="item.category" class="select">
                <option v-for="c in ITEM_CATEGORIES" :key="c" :value="c">{{ c }}</option>
              </select>
            </div>
            <div class="form-item" style="max-width: 160px">
              <label class="form-label">金额（元）<span class="req">*</span></label>
              <input v-model="item.amount" type="number" min="0.01" step="0.01" class="input" style="font-variant-numeric: tabular-nums" />
            </div>
          </div>
          <div class="form-row" style="align-items: flex-end">
            <div class="form-item" style="margin-bottom: 0">
              <label class="form-label">说明</label>
              <input v-model.trim="item.description" class="input" placeholder="选填" />
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
              </div>
            </div>
            <button type="button" class="btn btn-outline btn-sm" style="margin-bottom: 4px"
                    :disabled="form.items.length <= 1" @click="removeItem(i)">删除</button>
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
  </div>
</template>
