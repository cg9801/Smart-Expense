import { reactive } from 'vue'

/**
 * 轻量 auth store：模块级 reactive 单例，不引入 pinia（依赖最小化，docs/14 §3）。
 * token 持久化到 localStorage；401 时由 request.js 调 logout()。
 */
const STORAGE_KEY = 'se_token'
const USER_KEY = 'se_user'

function loadUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export const authState = reactive({
  token: localStorage.getItem(STORAGE_KEY) || '',
  user: loadUser()
})

export function useAuthStore() {
  return {
    get token() { return authState.token },
    get user() { return authState.user },
    get isLoggedIn() { return !!authState.token },
    get roles() { return authState.user?.roles || [] },
    // 顶栏入口按角色点亮：主管/财务/分管可见审批待办（docs/03 §4 权限矩阵）
    get canApprove() {
      return this.roles.some(r => ['SUPERVISOR', 'FINANCE', 'DIRECTOR'].includes(r))
    },
    // 付款单只有财务/管理员能看（docs/03 §4）
    get canPay() {
      return this.roles.some(r => ['FINANCE', 'ADMIN'].includes(r))
    },
    setLogin({ token, user }) {
      authState.token = token
      authState.user = user
      localStorage.setItem(STORAGE_KEY, token)
      localStorage.setItem(USER_KEY, JSON.stringify(user))
    },
    logout() {
      authState.token = ''
      authState.user = null
      localStorage.removeItem(STORAGE_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
}
