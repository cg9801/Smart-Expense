#!/usr/bin/env node
/**
 * 写路径并发测试（docs/20 §5）：
 *   场景 A：同一张单、同一个 Idempotency-Key 并发提交 → 只允许一次真提交，其余幂等回放/冲突；
 *   场景 B：同一审批节点并发通过 → 只允许一次成功，其余 409。
 *
 * 用法：node scripts/load-write-test.mjs --base http://127.0.0.1:8080 --concurrency 2
 *
 * 数据纪律（docs/07 §14）：
 * - 场景 A 测完自动清理（撤回 → 删除草稿），表里不留脏数据；
 * - 场景 B 会留下一条"审批中"的单据（撤回已不允许），脚本会打印它的 id 和清理 SQL，
 *   由你决定是继续走完流程还是手动删。**不要把它当成演示数据**。
 */
const args = Object.fromEntries(
  process.argv.slice(2).map((a, i, arr) => (a.startsWith('--') ? [a.slice(2), arr[i + 1]] : null)).filter(Boolean)
)
const base = args.base || 'http://127.0.0.1:8080'
const concurrent = Math.max(2, Number(args.concurrency || 2))
const amount = args.amount || '100.00'

async function api(path, { method = 'GET', token, body, headers = {} } = {}) {
  const res = await fetch(`${base}${path}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...headers
    },
    ...(body ? { body: JSON.stringify(body) } : {})
  })
  let json = null
  try {
    json = await res.json()
  } catch {
    // 非 JSON（例如 502）：保留状态码即可
  }
  return { status: res.status, code: json?.code, data: json?.data, message: json?.message }
}

async function login(username, password = '123456') {
  const r = await api('/api/v1/auth/login', {
    method: 'POST',
    body: { username, password }
  })
  if (r.code !== 0) throw new Error(`登录失败 ${username}: ${r.message}`)
  return { token: r.data.token, user: r.data.user }
}

function reportBody(title) {
  return {
    title,
    expenseType: 'TRAVEL',
    remark: 'load-write-test',
    items: [
      {
        expenseDate: '2026-11-01',
        category: '交通',
        amount,
        description: '写路径并发测试'
      }
    ]
  }
}

async function createDraft(token, title) {
  // 申请人身份由后端从 JWT 取，请求体不再携带（docs/03 §2.2）
  const r = await api('/api/v1/reports', { method: 'POST', token, body: reportBody(title) })
  if (r.code !== 0) throw new Error(`建单失败: ${r.message}`)
  return r.data.id
}

/** 同时发起 N 个请求，返回每次的状态码与业务码 */
async function fireInParallel(n, fn) {
  const started = Date.now()
  const results = await Promise.all(Array.from({ length: n }, () => fn()))
  return { results, elapsedMs: Date.now() - started }
}

function summarize(results) {
  const byStatus = {}
  for (const r of results) {
    const key = `${r.status}/${r.code}`
    byStatus[key] = (byStatus[key] || 0) + 1
  }
  return byStatus
}

async function scenarioA(linyi) {
  const key = `idem-concurrent-${Date.now()}`
  const reportId = await createDraft(linyi.token, '写路径-并发提交')
  const { results, elapsedMs } = await fireInParallel(concurrent, () =>
    api(`/api/v1/reports/${reportId}/submit`, {
      method: 'POST',
      token: linyi.token,
      headers: { 'Idempotency-Key': key }
    })
  )

  const detail = await api(`/api/v1/reports/${reportId}`, { token: linyi.token })
  const summary = summarize(results)

  // 收尾：撤回（释放预算）→ 删除草稿；撤回失败说明状态已不是"无人审批的 PENDING"，要人工处理
  const withdraw = await api(`/api/v1/reports/${reportId}/withdraw`, { method: 'POST', token: linyi.token })
  const removed = withdraw.code === 0
    ? await api(`/api/v1/reports/${reportId}`, { method: 'DELETE', token: linyi.token })
    : null

  return {
    scenario: 'A. 同一单并发提交（同 Idempotency-Key）',
    reportId,
    concurrency: concurrent,
    elapsedMs,
    byStatus: summary,
    successCount: results.filter(r => r.status === 200).length,
    reportStatusAfter: detail.data?.status,
    cleanup: removed ? `已撤回并删除草稿（${removed.code === 0 ? 'ok' : '失败'}）` : `撤回失败：${withdraw.message}，请人工处理 id=${reportId}`
  }
}

async function scenarioB(linyi, zhangsan) {
  const key = `idem-approve-${Date.now()}`
  const reportId = await createDraft(linyi.token, '写路径-并发审批')
  const submitted = await api(`/api/v1/reports/${reportId}/submit`, {
    method: 'POST',
    token: linyi.token,
    headers: { 'Idempotency-Key': key }
  })
  if (submitted.code !== 0) throw new Error(`提交失败: ${submitted.message}`)

  const pending = await api('/api/v1/approvals/pending?page=1&size=100', { token: zhangsan.token })
  const node = (pending.data?.records || []).find(r => String(r.reportId) === String(reportId))
  if (!node) throw new Error(`张三的待办里找不到 reportId=${reportId}`)

  const { results, elapsedMs } = await fireInParallel(concurrent, () =>
    api(`/api/v1/approvals/${node.approvalId}/approve`, { method: 'POST', token: zhangsan.token })
  )

  const detail = await api(`/api/v1/reports/${reportId}`, { token: linyi.token })

  return {
    scenario: 'B. 同一审批节点并发通过',
    reportId,
    approvalId: node.approvalId,
    concurrency: concurrent,
    elapsedMs,
    byStatus: summarize(results),
    successCount: results.filter(r => r.status === 200).length,
    reportStatusAfter: detail.data?.status,
    cleanup: `该单已进入审批中，撤回不再允许；如需清理请按 docs/07 §14 的顺序删：approval / status_log / budget_txn / report（reportId=${reportId}）`
  }
}

async function main() {
  const linyi = await login('linyi')
  const zhangsan = await login('zhangsan')

  console.log(`\n写路径并发测试：每场景 ${concurrent} 并发，目标 ${base}\n`)

  const a = await scenarioA(linyi)
  const b = await scenarioB(linyi, zhangsan)

  for (const s of [a, b]) {
    console.log(`\n${s.scenario}`)
    console.log(`  单据 id=${s.reportId}${s.approvalId ? `，审批节点 id=${s.approvalId}` : ''}`)
    console.log(`  并发=${s.concurrency}，总耗时=${s.elapsedMs}ms`)
    console.log(`  状态分布：${JSON.stringify(s.byStatus)}`)
    console.log(`  成功次数=${s.successCount}（期望 1）`)
    console.log(`  单据状态=${s.reportStatusAfter}`)
    console.log(`  收尾：${s.cleanup}`)
  }

  const output = { base, concurrent, generatedAt: new Date().toISOString(), scenarioA: a, scenarioB: b }
  console.log('\nJSON:')
  console.log(JSON.stringify(output, null, 2))
}

main().catch(e => {
  console.error('写路径测试失败:', e.message)
  process.exit(1)
})
