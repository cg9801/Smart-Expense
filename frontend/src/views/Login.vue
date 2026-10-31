<script setup>
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { login } from '../api/auth.js'
import { useAuthStore } from '../stores/auth.js'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const error = ref('')

async function doLogin() {
  error.value = ''
  if (!form.username || !form.password) {
    error.value = '账号和密码都要填哦'
    return
  }
  loading.value = true
  try {
    const data = await login(form.username, form.password)
    auth.setLogin(data)
    router.push(route.query.redirect || { name: 'report-list' })
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function fillDemo(username) {
  form.username = username
  form.password = '123456'
}
</script>

<template>
  <div class="login-wrap">
    <div class="card card-pad login-card">
      <div class="login-brand">
        <h1>🐋 Smart Expense</h1>
        <p>智能报销审批系统</p>
      </div>

      <div v-if="error" class="alert alert-error">{{ error }}</div>

      <form @submit.prevent="doLogin">
        <div class="form-item">
          <label class="form-label">账号<span class="req">*</span></label>
          <input v-model.trim="form.username" class="input" placeholder="用户名" autocomplete="username" />
        </div>
        <div class="form-item">
          <label class="form-label">密码<span class="req">*</span></label>
          <input v-model="form.password" type="password" class="input" placeholder="密码" autocomplete="current-password" />
        </div>
        <button class="btn btn-primary" style="width: 100%" :disabled="loading" type="submit">
          {{ loading ? '登录中…' : '登 录' }}
        </button>
      </form>

      <!-- 演示账号来自 sql/seed.sql，点击自动填充 -->
      <div class="demo-accounts">
        <div class="demo-title">演示账号（密码 123456，点击填充）</div>
        <span class="demo-chip" @click="fillDemo('linyi')">linyi · 员工</span>
        <span class="demo-chip" @click="fillDemo('zhangsan')">zhangsan · 主管</span>
        <span class="demo-chip" @click="fillDemo('wangwu')">wangwu · 财务</span>
        <span class="demo-chip" @click="fillDemo('zhaoliu')">zhaoliu · 分管</span>
        <span class="demo-chip" @click="fillDemo('admin')">admin · 管理员</span>
      </div>
    </div>
  </div>
</template>
