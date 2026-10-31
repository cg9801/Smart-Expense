<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { listBudgets, adjustBudget, listUsers, updateUserRoles, getBudgetPool, setBudgetPool } from '../api/admin.js'
import DonutChart from '../components/DonutChart.vue'

const tab = ref('overview')

const ROLE_LABELS = {
  EMPLOYEE: '员工',
  SUPERVISOR: '直属主管',
  FINANCE: '财务',
  DIRECTOR: '分管',
  ADMIN: '管理员'
}
const ROLE_OPTIONS = Object.keys(ROLE_LABELS)
const COLORS = ['#2563eb', '#16a34a', '#f59e0b', '#8b5cf6', '#ef4444', '#06b6d4', '#ec4899']

function currentMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
function money(v) {
  return '¥ ' + Number(v || 0).toFixed(2)
}
function percent(v) {
  return (Number(v || 0) * 100).toFixed(1) + '%'
}

// ---------- 预算 ----------
const period = ref(currentMonth())
const budgets = ref([])
const loadingBudgets = ref(false)
const budgetError = ref('')

const summary = computed(() => {
  const sum = (key) => budgets.value.reduce((acc, b) => acc + Number(b[key] || 0), 0)
  return {
    total: sum('totalAmount'),
    used: sum('usedAmount'),
    frozen: sum('frozenAmount'),
    available: sum('availableAmount')
  }
})

// 环状图数据：各部门 total 占比（动画与渐变描边由 DonutChart 组件负责，docs/14 §12）
const donutLegend = computed(() => {
  const list = budgets.value.filter(b => Number(b.totalAmount) > 0)
  const total = list.reduce((acc, b) => acc + Number(b.totalAmount), 0)
  return list.map((b, i) => ({
    name: b.deptName || `部门${b.deptId}`,
    color: COLORS[i % COLORS.length],
    percent: total > 0 ? (Number(b.totalAmount) / total) * 100 : 0,
    amount: Number(b.totalAmount || 0)
  }))
})

async function loadBudgets() {
  loadingBudgets.value = true
  budgetError.value = ''
  try {
    budgets.value = (await listBudgets(period.value)) || []
  } catch (e) {
    budgetError.value = e.message
    budgets.value = []
  } finally {
    loadingBudgets.value = false
  }
}

// 预算调整弹窗（分发预算）
const budgetDialog = reactive({ visible: false, deptId: null, deptName: '', currentTotal: 0, totalAmount: '', error: '', saving: false })

function openAdjust(row) {
  budgetDialog.deptId = row.deptId
  budgetDialog.deptName = row.deptName || `部门${row.deptId}`
  budgetDialog.currentTotal = Number(row.totalAmount || 0)
  budgetDialog.totalAmount = String(Number(row.totalAmount || 0))
  budgetDialog.error = ''
  budgetDialog.visible = true
}

async function saveAdjust() {
  budgetDialog.error = ''
  const amount = Number(budgetDialog.totalAmount)
  if (!(amount >= 0)) {
    budgetDialog.error = '预算总额必须是非负数字'
    return
  }
  budgetDialog.saving = true
  try {
    await adjustBudget(budgetDialog.deptId, { period: period.value, totalAmount: amount })
    budgetDialog.visible = false
    loadBudgets()
  } catch (e) {
    budgetDialog.error = e.message
  } finally {
    budgetDialog.saving = false
  }
}

// ---------- 总预算池（设置总预算） ----------
const pool = ref(null)
const loadingPool = ref(false)

async function loadPool() {
  loadingPool.value = true
  try {
    pool.value = await getBudgetPool(period.value)
  } catch {
    pool.value = null
  } finally {
    loadingPool.value = false
  }
}

const poolDialog = reactive({ visible: false, totalAmount: '', error: '', saving: false })

function openPool() {
  poolDialog.totalAmount = pool.value?.totalAmount != null ? String(Number(pool.value.totalAmount)) : ''
  poolDialog.error = ''
  poolDialog.visible = true
}

async function savePool() {
  poolDialog.error = ''
  const amount = Number(poolDialog.totalAmount)
  if (!(amount >= 0)) {
    poolDialog.error = '总预算必须是非负数字'
    return
  }
  poolDialog.saving = true
  try {
    pool.value = await setBudgetPool({ period: period.value, totalAmount: amount })
    poolDialog.visible = false
    loadBudgets()
  } catch (e) {
    poolDialog.error = e.message
  } finally {
    poolDialog.saving = false
  }
}

// 本部门可分配上限 = 总预算 − 其他部门已分配（未设置池时给演示上限）
const adjustMax = computed(() => {
  if (pool.value?.totalAmount == null) return 1000000
  const others = budgets.value
    .filter(b => b.deptId !== budgetDialog.deptId)
    .reduce((sum, b) => sum + Number(b.totalAmount || 0), 0)
  return Math.max(0, Number(pool.value.totalAmount) - others)
})

// 粗滑块填色百分比（CSS 变量驱动轨道渐变）
const adjustFill = computed(() => {
  if (!(adjustMax.value > 0)) return '0%'
  const pct = (Number(budgetDialog.totalAmount || 0) / adjustMax.value) * 100
  return `${Math.max(0, Math.min(100, pct))}%`
})

function onSliderInput(e) {
  budgetDialog.totalAmount = String(Number(e.target.value))
}

// 细对焦：±100 / ±1000 微调，夹在 [0, adjustMax]
function bump(delta) {
  const next = Math.max(0, Math.min(adjustMax.value, Number(budgetDialog.totalAmount || 0) + delta))
  budgetDialog.totalAmount = String(Math.round(next * 100) / 100)
}

function refresh() {
  loadBudgets()
  loadPool()
}

// ---------- 用户与角色 ----------
const users = ref([])
const loadingUsers = ref(false)
const userError = ref('')

async function loadUsers() {
  loadingUsers.value = true
  userError.value = ''
  try {
    users.value = (await listUsers()) || []
  } catch (e) {
    userError.value = e.message
    users.value = []
  } finally {
    loadingUsers.value = false
  }
}

const roleDialog = reactive({ visible: false, user: null, selected: [], error: '', saving: false })

function openRoles(user) {
  roleDialog.user = user
  roleDialog.selected = [...(user.roles || [])]
  roleDialog.error = ''
  roleDialog.visible = true
}

function toggleRole(code) {
  const idx = roleDialog.selected.indexOf(code)
  if (idx >= 0) roleDialog.selected.splice(idx, 1)
  else roleDialog.selected.push(code)
}

async function saveRoles() {
  roleDialog.error = ''
  if (!roleDialog.selected.length) {
    roleDialog.error = '至少保留一个角色'
    return
  }
  roleDialog.saving = true
  try {
    await updateUserRoles(roleDialog.user.id, roleDialog.selected)
    roleDialog.visible = false
    loadUsers()
  } catch (e) {
    roleDialog.error = e.message
  } finally {
    roleDialog.saving = false
  }
}

function switchTab(name) {
  tab.value = name
  if (name === 'users' && !users.value.length && !loadingUsers.value) loadUsers()
}

onMounted(refresh)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">管理后台</h2>
        <p class="page-sub">部门预算总览 · 分发预算 · 用户权限</p>
      </div>
      <div style="display: flex; gap: 8px; align-items: center">
        <input v-model="period" type="month" class="input" style="width: 160px" @change="refresh" />
        <button class="btn btn-outline btn-sm" :disabled="loadingBudgets" @click="refresh">刷新</button>
      </div>
    </div>

    <div class="tabs">
      <button class="tab-btn" :class="{ active: tab === 'overview' }" @click="switchTab('overview')">总览</button>
      <button class="tab-btn" :class="{ active: tab === 'budgets' }" @click="switchTab('budgets')">预算管理</button>
      <button class="tab-btn" :class="{ active: tab === 'users' }" @click="switchTab('users')">用户权限</button>
    </div>

    <div v-if="budgetError" class="alert alert-error">{{ budgetError }}</div>

    <!-- 总览：汇总卡片 + 环状图 -->
    <div v-if="tab === 'overview'" class="detail-grid">
      <div style="display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px">
        <div class="card card-pad stat-card">
          <div class="stat-label">总预算（{{ period }}）</div>
          <div class="stat-value">{{ money(summary.total) }}</div>
        </div>
        <div class="card card-pad stat-card">
          <div class="stat-label">已实扣</div>
          <div class="stat-value" style="color: var(--c-success)">{{ money(summary.used) }}</div>
        </div>
        <div class="card card-pad stat-card">
          <div class="stat-label">在途冻结</div>
          <div class="stat-value" style="color: #b45309">{{ money(summary.frozen) }}</div>
        </div>
        <div class="card card-pad stat-card">
          <div class="stat-label">可用额度</div>
          <div class="stat-value" style="color: var(--c-primary)">{{ money(summary.available) }}</div>
        </div>
      </div>

      <!-- 总预算池：顶层约束，部门分配之和不得超过它 -->
      <div class="card card-pad" style="display: flex; gap: 24px; align-items: center; flex-wrap: wrap">
        <div class="stat-card">
          <div class="stat-label">总预算池（{{ period }}）</div>
          <div class="stat-value" :style="{ color: pool?.totalAmount == null ? 'var(--c-text-3)' : 'var(--c-text)' }">
            {{ pool?.totalAmount == null ? '未设置' : money(pool.totalAmount) }}
          </div>
        </div>
        <div>
          <div class="stat-label">已分配</div>
          <div class="stat-value">{{ money(pool?.allocatedAmount) }}</div>
        </div>
        <div>
          <div class="stat-label">未分配</div>
          <div class="stat-value"
               :style="{ color: (pool?.unallocatedAmount ?? 0) < 0 ? 'var(--c-danger)' : 'var(--c-primary)' }">
            {{ pool?.unallocatedAmount == null ? '—' : money(pool.unallocatedAmount) }}
          </div>
        </div>
        <button class="btn btn-outline btn-sm" style="margin-left: auto" :disabled="loadingPool" @click="openPool">
          设置总预算
        </button>
      </div>

      <div class="card card-pad" style="display: flex; gap: 32px; align-items: center; flex-wrap: wrap">
        <DonutChart :items="donutLegend" :total="summary.total" label="部门预算占比" />
        <div style="flex: 1; min-width: 220px">
          <div v-if="!donutLegend.length" style="color: var(--c-text-3)">当月还没有预算数据</div>
          <div v-for="item in donutLegend" :key="item.name" class="legend-row">
            <span class="legend-dot" :style="{ background: item.color }"></span>
            <span style="flex: 1">{{ item.name }}</span>
            <span style="color: var(--c-text-2)">{{ item.percent.toFixed(1) }}%</span>
            <span style="font-variant-numeric: tabular-nums; margin-left: 10px">{{ money(item.amount) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 预算管理：表格 + 调整弹窗 -->
    <div v-if="tab === 'budgets'" class="card">
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>部门</th><th class="num">总额</th><th class="num">已实扣</th><th class="num">冻结</th>
              <th class="num">可用</th><th>使用率</th><th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loadingBudgets"><td colspan="7" style="text-align: center; color: var(--c-text-3)">加载中…</td></tr>
            <tr v-else-if="!budgets.length"><td colspan="7"><div class="empty"><div class="empty-icon">📊</div>该账期还没有预算，点"分发"给部门分配</div></td></tr>
            <tr v-else v-for="b in budgets" :key="b.deptId">
              <td>{{ b.deptName || ('部门' + b.deptId) }}</td>
              <td class="num">{{ money(b.totalAmount) }}</td>
              <td class="num">{{ money(b.usedAmount) }}</td>
              <td class="num">{{ money(b.frozenAmount) }}</td>
              <td class="num" style="color: var(--c-primary)">{{ money(b.availableAmount) }}</td>
              <td style="min-width: 120px">
                <div class="progress"><div class="progress-bar" :style="{ width: Math.min(100, Number(b.usageRate || 0) * 100) + '%' }"></div></div>
                <span style="font-size: 12px; color: var(--c-text-2)">{{ percent(b.usageRate) }}</span>
              </td>
              <td><button class="btn btn-outline btn-sm" @click="openAdjust(b)">调整</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 用户权限 -->
    <div v-if="tab === 'users'" class="card">
      <div v-if="userError" class="alert alert-error" style="margin: 16px 16px 0">{{ userError }}</div>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr><th>姓名</th><th>账号</th><th>部门</th><th>角色</th><th>状态</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-if="loadingUsers"><td colspan="6" style="text-align: center; color: var(--c-text-3)">加载中…</td></tr>
            <tr v-else-if="!users.length"><td colspan="6"><div class="empty">暂无用户</div></td></tr>
            <tr v-else v-for="u in users" :key="u.id">
              <td>{{ u.realName }}</td>
              <td style="font-family: monospace; font-size: 13px">{{ u.username }}</td>
              <td>{{ u.deptName || '—' }}</td>
              <td>
                <span v-for="r in u.roles" :key="r" class="tag tag-blue" style="margin-right: 4px">{{ ROLE_LABELS[r] || r }}</span>
                <span v-if="!u.roles?.length" style="color: var(--c-text-3)">无角色</span>
              </td>
              <td>{{ u.status === 1 ? '在职' : '离职' }}</td>
              <td><button class="btn btn-outline btn-sm" @click="openRoles(u)">编辑角色</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 预算调整弹窗：粗滑块定位 + 细对焦微调 -->
    <div v-if="budgetDialog.visible" class="modal-mask" @click.self="budgetDialog.visible = false">
      <div class="modal">
        <h3 class="modal-title">分配预算 —— {{ budgetDialog.deptName }}</h3>
        <div v-if="budgetDialog.error" class="alert alert-error" style="margin-bottom: 12px">{{ budgetDialog.error }}</div>

        <div style="display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 12px">
          <div>
            <div class="stat-label">当前总额</div>
            <div class="stat-value" style="font-size: 15px; color: var(--c-text-2)">{{ money(budgetDialog.currentTotal) }}</div>
          </div>
          <div style="text-align: right">
            <div class="stat-label">新总额</div>
            <div class="stat-value" style="color: var(--c-primary)">{{ money(budgetDialog.totalAmount) }}</div>
          </div>
        </div>

        <div class="form-item">
          <label class="form-label">直接输入（元）</label>
          <input v-model="budgetDialog.totalAmount" type="number" min="0" step="0.01" class="input"
                 style="font-variant-numeric: tabular-nums" />
        </div>

        <!-- 粗范围定位：0 ~ 本部门可分配上限，步长 1000 -->
        <div class="form-item">
          <label class="form-label">粗调（滑动定位，步长 1000）</label>
          <input class="slider" type="range" min="0" :max="adjustMax" step="1000"
                 :value="budgetDialog.totalAmount" :style="{ '--fill': adjustFill }" @input="onSliderInput" />
          <div class="slider-scale"><span>¥ 0</span><span>{{ money(adjustMax) }}</span></div>
        </div>

        <!-- 细对焦：±100 / ±1000 微调 -->
        <div class="form-item">
          <label class="form-label">细调（微调对齐）</label>
          <div class="stepper">
            <button type="button" class="btn btn-outline btn-sm" @click="bump(-1000)">− 1000</button>
            <button type="button" class="btn btn-outline btn-sm" @click="bump(-100)">− 100</button>
            <button type="button" class="btn btn-outline btn-sm" @click="bump(100)">+ 100</button>
            <button type="button" class="btn btn-outline btn-sm" @click="bump(1000)">+ 1000</button>
          </div>
        </div>

        <div class="form-hint">
          本部门可分配上限 {{ money(adjustMax) }}（总预算 − 其他部门已分配）；不能低于已用 + 冻结
        </div>

        <div class="modal-actions">
          <button class="btn btn-outline" @click="budgetDialog.visible = false">取消</button>
          <button class="btn btn-primary" :disabled="budgetDialog.saving" @click="saveAdjust">
            {{ budgetDialog.saving ? '保存中…' : '确认分发' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 设置总预算弹窗 -->
    <div v-if="poolDialog.visible" class="modal-mask" @click.self="poolDialog.visible = false">
      <div class="modal">
        <h3 class="modal-title">设置总预算 —— {{ period }}</h3>
        <div v-if="poolDialog.error" class="alert alert-error" style="margin-bottom: 12px">{{ poolDialog.error }}</div>
        <div class="form-item">
          <label class="form-label">公司总预算池（元）<span class="req">*</span></label>
          <input v-model="poolDialog.totalAmount" type="number" min="0" step="0.01" class="input"
                 style="font-variant-numeric: tabular-nums" />
          <div class="form-hint">不能低于已分配（当前 {{ money(pool?.allocatedAmount) }}），部门分配之和不得超过它</div>
        </div>
        <div class="modal-actions">
          <button class="btn btn-outline" @click="poolDialog.visible = false">取消</button>
          <button class="btn btn-primary" :disabled="poolDialog.saving" @click="savePool">
            {{ poolDialog.saving ? '保存中…' : '保存总预算' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 角色编辑弹窗 -->
    <div v-if="roleDialog.visible" class="modal-mask" @click.self="roleDialog.visible = false">
      <div class="modal">
        <h3 class="modal-title">编辑角色 —— {{ roleDialog.user?.realName }}</h3>
        <div v-if="roleDialog.error" class="alert alert-error" style="margin-bottom: 12px">{{ roleDialog.error }}</div>
        <div style="display: flex; flex-direction: column; gap: 10px">
          <label v-for="code in ROLE_OPTIONS" :key="code" class="role-option">
            <input type="checkbox" :checked="roleDialog.selected.includes(code)" @change="toggleRole(code)" />
            {{ ROLE_LABELS[code] }}（{{ code }}）
          </label>
        </div>
        <div class="modal-actions">
          <button class="btn btn-outline" @click="roleDialog.visible = false">取消</button>
          <button class="btn btn-primary" :disabled="roleDialog.saving" @click="saveRoles">
            {{ roleDialog.saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--c-border);
}
.tab-btn {
  padding: 8px 16px;
  border: none;
  background: transparent;
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-2);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}
.tab-btn:hover { color: var(--c-text); }
.tab-btn.active { color: var(--c-primary); border-bottom-color: var(--c-primary); }

.stat-label { font-size: 12px; color: var(--c-text-2); margin-bottom: 4px; }
.stat-value { font-size: 20px; font-weight: 700; font-variant-numeric: tabular-nums; }

.donut {
  width: 200px;
  height: 200px;
  border-radius: 50%;
  position: relative;
  flex: 0 0 auto;
}
.legend-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--c-border);
  font-size: 13px;
}
.legend-row:last-child { border-bottom: none; }
.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  flex: 0 0 auto;
}

.progress {
  height: 6px;
  background: var(--c-bg);
  border-radius: 999px;
  overflow: hidden;
  margin-bottom: 2px;
}
.progress-bar {
  height: 100%;
  background: var(--c-primary);
  border-radius: 999px;
  transition: width .3s;
}

.role-option {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  cursor: pointer;
}

/* ===== 预算滑块（粗调）与微调组（细对焦）===== */
.slider {
  width: 100%;
  height: 6px;
  border-radius: 999px;
  -webkit-appearance: none;
  appearance: none;
  outline: none;
  cursor: pointer;
  /* 已填充部分跟随拇指位置，未填充用浅灰：--fill 由 Vue 绑定 */
  background: linear-gradient(90deg, var(--c-primary) 0%, var(--c-primary) var(--fill, 0%), var(--c-border) var(--fill, 0%));
  transition: filter .2s ease;
}
.slider:hover { filter: brightness(1.03); }
.slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 20px; height: 20px; border-radius: 50%;
  background: linear-gradient(180deg, #fff 0%, #eef3fb 100%);
  border: 2px solid var(--c-primary);
  box-shadow: 0 2px 6px rgba(37, 99, 235, .35), inset 0 1px 0 #fff;
  cursor: grab;
  transition: transform .2s cubic-bezier(.34, 1.56, .64, 1), box-shadow .2s ease;
}
.slider::-webkit-slider-thumb:active { transform: scale(1.15); cursor: grabbing; }
.slider::-moz-range-thumb {
  width: 18px; height: 18px; border-radius: 50%;
  background: linear-gradient(180deg, #fff 0%, #eef3fb 100%);
  border: 2px solid var(--c-primary);
  box-shadow: 0 2px 6px rgba(37, 99, 235, .35);
  cursor: grab;
}
.slider::-moz-range-track { height: 6px; border-radius: 999px; background: var(--c-border); }
.slider-scale {
  display: flex; justify-content: space-between;
  font-size: 12px; color: var(--c-text-3); margin-top: 4px;
}
.stepper { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
