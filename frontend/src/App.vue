<script setup>
import { onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth.js'

const auth = useAuthStore()
const router = useRouter()

function handleLogout() {
  auth.logout()
  router.push({ name: 'login' })
}

// ripple 柔光：事件委托挂在 document，所有 .btn 点击都从点击位置扩散（docs/14 §12）
let onDocClick = null
onMounted(() => {
  onDocClick = (e) => {
    const el = e.target instanceof Element ? e.target.closest('.btn') : null
    if (!el || el.disabled) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const rect = el.getBoundingClientRect()
    const size = Math.max(rect.width, rect.height) * 2
    const ripple = document.createElement('span')
    ripple.className = 'ripple'
    ripple.style.width = `${size}px`
    ripple.style.height = `${size}px`
    ripple.style.left = `${e.clientX - rect.left}px`
    ripple.style.top = `${e.clientY - rect.top}px`
    el.appendChild(ripple)
    setTimeout(() => ripple.remove(), 620)
  }
  document.addEventListener('click', onDocClick)
})
onBeforeUnmount(() => {
  if (onDocClick) document.removeEventListener('click', onDocClick)
})
</script>

<template>
  <div class="app-shell">
    <header v-if="auth.isLoggedIn" class="app-header">
      <div class="app-header-inner">
        <div class="brand"><span class="brand-dot"></span>Smart Expense</div>
        <nav class="nav-links">
          <router-link class="nav-link" :to="{ name: 'report-list' }">我的报销单</router-link>
          <router-link v-if="auth.canApprove" class="nav-link" :to="{ name: 'approval-todo' }">审批待办</router-link>
          <router-link v-if="auth.canPay" class="nav-link" :to="{ name: 'payment-list' }">付款单</router-link>
          <router-link v-if="auth.canPay" class="nav-link" :to="{ name: 'invoice-review' }">发票复核</router-link>
          <router-link v-if="auth.roles.includes('ADMIN')" class="nav-link" :to="{ name: 'admin' }">管理后台</router-link>
        </nav>
        <div class="user-zone">
          <span class="user-name">{{ auth.user?.realName || auth.user?.username }}</span>
          <button class="btn btn-outline btn-sm" @click="handleLogout">退出</button>
        </div>
      </div>
    </header>
    <main class="app-main">
      <router-view :key="$route.fullPath" />
    </main>
  </div>
</template>
