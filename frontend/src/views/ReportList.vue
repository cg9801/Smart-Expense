<script setup>
import { onMounted, reactive, ref } from 'vue'
import { deleteReport, listReports } from '../api/report.js'
import { REPORT_STATUS } from '../constants/status.js'
import StatusTag from '../components/StatusTag.vue'

const rows = ref([])
const loading = ref(true)
const error = ref('')
const page = reactive({ current: 1, size: 10, total: 0 })
const filter = reactive({ status: '' })

const statusOptions = Object.entries(REPORT_STATUS).map(([value, v]) => ({ value, label: v.label }))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listReports({ scope: 'mine', status: filter.status || undefined, page: page.current, size: page.size })
    rows.value = data?.records || []
    page.total = data?.total || 0
  } catch (e) {
    error.value = e
    rows.value = []
  } finally {
    loading.value = false
  }
}

function goPage(next) {
  if (next < 1 || next > Math.ceil(page.total / page.size)) return
  page.current = next
  load()
}

// 删除草稿：只对 DRAFT 显示；二次确认后重载列表（逻辑删除，后端守卫本人+草稿）
async function remove(row) {
  if (!window.confirm(`删除草稿「${row.title}」？删除后不可恢复。`)) return
  try {
    await deleteReport(row.id)
    load()
  } catch (e) {
    error.value = e
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">我的报销单</h2>
        <p class="page-sub">草稿可编辑、提交、撤回；提交后进入审批流</p>
      </div>
      <router-link :to="{ name: 'report-new' }" class="btn btn-primary">+ 新建报销单</router-link>
    </div>

    <div v-if="error" class="alert alert-error">
      {{ error.message }}
    </div>

    <div class="card">
      <div class="card-pad" style="padding-bottom: 0;">
        <select v-model="filter.status" class="select" style="width: 160px" @change="page.current = 1; load()">
          <option value="">全部状态</option>
          <option v-for="opt in statusOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
      </div>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>单号</th><th>标题</th><th>类型</th>
              <th class="num">金额</th><th>状态</th><th>创建时间</th><th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="7" style="text-align: center; color: var(--c-text-3)">加载中…</td>
            </tr>
            <tr v-else-if="!rows.length">
              <td colspan="7">
                <div class="empty">
                  <div class="empty-icon">📭</div>
                  {{ error ? '加载失败' : '还没有报销单，点右上角新建一张吧' }}
                </div>
              </td>
            </tr>
            <tr v-else v-for="r in rows" :key="r.id">
              <td style="font-family: monospace; font-size: 13px">{{ r.reportNo || r.id }}</td>
              <td>{{ r.title }}</td>
              <td>{{ r.expenseType }}</td>
              <td class="num">¥ {{ Number(r.amountTotal ?? 0).toFixed(2) }}</td>
              <td><StatusTag :status="r.status" /></td>
              <td style="color: var(--c-text-2)">{{ r.createdAt }}</td>
              <td>
                <div style="display: flex; gap: 10px; align-items: center">
                  <router-link :to="{ name: 'report-detail', params: { id: r.id } }">详情 →</router-link>
                  <router-link v-if="r.status === 'DRAFT'" :to="{ name: 'report-edit', params: { id: r.id } }">编辑</router-link>
                  <button v-if="r.status === 'DRAFT'" class="btn btn-danger btn-sm" @click="remove(r)">删除</button>
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
  </div>
</template>
