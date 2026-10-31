import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { public: true } },
  { path: '/', redirect: '/reports' },
  { path: '/reports', name: 'report-list', component: () => import('../views/ReportList.vue') },
  { path: '/reports/new', name: 'report-new', component: () => import('../views/ReportForm.vue') },
  { path: '/reports/:id/edit', name: 'report-edit', component: () => import('../views/ReportForm.vue') },
  { path: '/reports/:id', name: 'report-detail', component: () => import('../views/ReportDetail.vue') },
  { path: '/approvals', name: 'approval-todo', component: () => import('../views/ApprovalTodo.vue') },
  // 付款单：财务/管理员（docs/03 §4 权限矩阵），后端 @RequireRole 再挡一次
  { path: '/payments', name: 'payment-list', component: () => import('../views/PaymentView.vue'), meta: { roles: ['FINANCE', 'ADMIN'] } },
  // 发票复核：财务/管理员（docs/03 §2.5 复核队列）
  { path: '/invoices', name: 'invoice-review', component: () => import('../views/InvoiceReview.vue'), meta: { roles: ['FINANCE', 'ADMIN'] } },
  // 管理后台：仅 ADMIN，前端守卫挡一次，后端 @RequireRole 再挡一次
  { path: '/admin', name: 'admin', component: () => import('../views/AdminView.vue'), meta: { roles: ['ADMIN'] } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 登录守卫：无 token 一律去 /login（401 的处理在 request.js，那里会主动跳转）
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 角色守卫：/admin 只有 ADMIN 能进，直接访问会被弹回列表
  if (to.meta.roles && !to.meta.roles.some(role => auth.roles.includes(role))) {
    return { name: 'report-list' }
  }
})

export default router
