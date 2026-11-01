<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listPending, listHandled, approve, reject, transfer, listTransferCandidates } from '../api/approval.js'
import StatusTag from '../components/StatusTag.vue'

const router = useRouter()

// Tab：待办（PENDING）/ 已办（APPROVED/REJECTED/TRANSFERRED），docs/03 §2.3
const tab = ref('pending')
const rows = ref([])
const loading = ref(true)
const error = ref(null)
const page = reactive({ current: 1, size: 10, total: 0 })
const acting = ref(false)

// 弹窗状态：mode = approve / reject / transfer
const dialog = reactive({ visible: false, mode: '', row: null, comment: '', targetUserId: '' })
const dialogError = ref('')
// 转交候选人：服务端已排除本人 + 本单历史审批人（docs/02 §5 防环）
const candidates = ref([])
const candidatesLoading = ref(false)

async function load() {
  loading.value = true
  try {
    const data = tab.value === 'pending'
      ? await listPending({ page: page.current, size: page.size })
      : await listHandled({ page: page.current, size: page.size })
    rows.value = data?.records || []
    page.total = data?.total || 0
    error.value = null
  } catch (e) {
    error.value = e
    rows.value = []
  } finally {
    loading.value = false
  }
}

function switchTab(name) {
  if (tab.value === name) return
  tab.value = name
  page.current = 1
  rows.value = []
  load()
}

function openDialog(mode, row) {
  dialog.mode = mode
  dialog.row = row
  dialog.comment = ''
  dialog.targetUserId = ''
  dialogError.value = ''
  dialog.visible = true
  if (mode === 'transfer') {
    loadCandidates(row.reportId)
  }
}

async function loadCandidates(reportId) {
  candidatesLoading.value = true
  candidates.value = []
  try {
    candidates.value = (await listTransferCandidates(reportId)) || []
  } catch (e) {
    dialogError.value = e.message
  } finally {
    candidatesLoading.value = false
  }
}

async function confirmDialog() {
  const row = dialog.row
  if (!row) return
  if (dialog.mode === 'reject' && !dialog.comment.trim()) {
    dialogError.value = '驳回意见必填（docs/03 §2.3）'
    return
  }
  if (dialog.mode === 'transfer' && !dialog.targetUserId) {
    dialogError.value = '请选择转交目标'
    return
  }

  acting.value = true
  try {
    if (dialog.mode === 'approve') {
      await approve(row.approvalId, dialog.comment.trim() || undefined)
    } else if (dialog.mode === 'reject') {
      await reject(row.approvalId, dialog.comment.trim())
    } else {
      await transfer(row.approvalId, Number(dialog.targetUserId), dialog.comment.trim())
    }
    dialog.visible = false
    load()
  } catch (e) {
    // 40901：节点已被处理（重复点击/会签并发），幂等语义——刷新即可（docs/03 §1.2）
    if (e.code === 40901 || e.httpStatus === 409) {
      dialog.visible = false
      load()
    } else {
      dialogError.value = e.message
    }
  } finally {
    acting.value = false
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
        <h2 class="page-title">{{ tab === 'pending' ? '审批待办' : '我的已办' }}</h2>
        <p class="page-sub">
          {{ tab === 'pending'
            ? '按截止时间升序；会签场景下他人已批会自动跳过'
            : '我处理过的节点（含转交），按处理时间倒序' }}
        </p>
      </div>
    </div>

    <div style="display: flex; gap: 8px; margin-bottom: 12px">
      <button class="btn btn-sm" :class="tab === 'pending' ? 'btn-primary' : 'btn-outline'" @click="switchTab('pending')">待办</button>
      <button class="btn btn-sm" :class="tab === 'handled' ? 'btn-primary' : 'btn-outline'" @click="switchTab('handled')">已办</button>
    </div>

    <div v-if="error" class="alert alert-error">
      {{ error.message }}
      <span v-if="error.code === 40301" class="trace">当前角色无审批权限（docs/03 §4）</span>
    </div>

    <div class="card">
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>单号</th><th>标题</th><th>申请人</th><th>级别</th>
              <th class="num">金额</th>
              <th v-if="tab === 'pending'">截止时间</th><th v-else>处理时间</th>
              <th v-if="tab === 'pending'">操作</th><th v-else>状态 / 意见</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="7" style="text-align: center; color: var(--c-text-3)">加载中…</td>
            </tr>
            <tr v-else-if="!rows.length">
              <td colspan="7">
                <div class="empty">
                  <div class="empty-icon">🎉</div>
                  {{ error ? '加载失败' : (tab === 'pending' ? '没有待办，喝茶去吧' : '还没有处理记录') }}
                </div>
              </td>
            </tr>
            <tr v-else v-for="r in rows" :key="r.approvalId">
              <td style="font-family: monospace; font-size: 13px">{{ r.reportNo || r.reportId }}</td>
              <td>
                <router-link :to="{ name: 'report-detail', params: { id: r.reportId } }">{{ r.title }}</router-link>
              </td>
              <td>{{ r.applicantName }}</td>
              <td>{{ r.stepNo }} · {{ r.stepName }}</td>
              <td class="num">¥ {{ Number(r.amountTotal ?? 0).toFixed(2) }}</td>
              <td style="color: var(--c-text-2)">{{ tab === 'pending' ? r.deadlineAt : r.actedAt }}</td>
              <td v-if="tab === 'pending'">
                <div style="display: flex; gap: 6px">
                  <button class="btn btn-primary btn-sm" @click="openDialog('approve', r)">通过</button>
                  <button class="btn btn-danger btn-sm" @click="openDialog('reject', r)">驳回</button>
                  <button class="btn btn-outline btn-sm" @click="openDialog('transfer', r)">转交</button>
                </div>
              </td>
              <td v-else>
                <StatusTag :status="r.status" kind="node" />
                <span style="margin-left: 6px; color: var(--c-text-2); font-size: 12px">{{ r.comment || '—' }}</span>
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

    <!-- 审批操作弹窗 -->
    <div v-if="dialog.visible" class="modal-mask" @click.self="dialog.visible = false">
      <div class="modal">
        <h3 class="modal-title">
          {{ dialog.mode === 'approve' ? '✅ 通过' : dialog.mode === 'reject' ? '❌ 驳回' : '↪️ 转交' }}
          —— {{ dialog.row?.title }}
        </h3>
        <div v-if="dialogError" class="alert alert-error" style="margin-bottom: 12px">{{ dialogError }}</div>

        <div v-if="dialog.mode === 'transfer'" class="form-item">
          <label class="form-label">转交给<span class="req">*</span></label>
          <select v-model="dialog.targetUserId" class="input" :disabled="candidatesLoading">
            <option value="">{{ candidatesLoading ? '加载中…' : '请选择审批人' }}</option>
            <option v-for="c in candidates" :key="c.userId" :value="String(c.userId)">
              {{ c.realName }}（{{ c.deptName || '未分配部门' }}）
            </option>
          </select>
          <div class="form-hint">服务端已排除本人与本单历史审批人（防环，docs/02 §5）</div>
        </div>

        <div class="form-item">
          <label class="form-label">
            意见<span v-if="dialog.mode === 'reject'" class="req">*</span>
            <span v-else style="color: var(--c-text-3); font-weight: 400">（选填）</span>
          </label>
          <textarea v-model.trim="dialog.comment" class="textarea" placeholder="例如：行程与发票一致，同意"></textarea>
        </div>

        <div class="modal-actions">
          <button class="btn btn-outline" @click="dialog.visible = false">取消</button>
          <button class="btn btn-primary" :disabled="acting" @click="confirmDialog">
            {{ acting ? '提交中…' : '确认' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
