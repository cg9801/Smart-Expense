#!/usr/bin/env node
/**
 * 只读链路压测：登录 → 列表 → 详情，统计 QPS / P95（docs/20）。
 * 用法：node scripts/load-test.mjs --base http://127.0.0.1:8080 --concurrency 10 --duration 15
 *
 * 口径说明（2026-11 修正）：
 * - 并发按**每个接口**均分：10 并发 + 2 个接口 = 每接口 5 并发，输出里写的是 effectiveConcurrency；
 * - QPS 是**单接口**吞吐；两接口合计吞吐看 totalQps；
 * - 只压只读接口，不污染业务数据；识别链路走默认 mock（不出网、不花钱）。
 */
const args = Object.fromEntries(
  process.argv.slice(2).map((a, i, arr) => (a.startsWith('--') ? [a.slice(2), arr[i + 1]] : null)).filter(Boolean)
)
const base = args.base || 'http://127.0.0.1:8080'
const concurrency = Number(args.concurrency || 10)
const durationSec = Number(args.duration || 15)
const username = args.username || 'linyi'
const password = args.password || '123456'

async function login() {
  const res = await fetch(`${base}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
  const json = await res.json()
  if (json.code !== 0) throw new Error(`登录失败: ${json.message}`)
  return json.data.token
}

function percentile(sorted, p) {
  if (!sorted.length) return 0
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)
  return sorted[idx]
}

async function main() {
  const token = await login()
  const headers = { Authorization: `Bearer ${token}` }

  const listRes = await fetch(`${base}/api/v1/reports?scope=mine&page=1&size=1`, { headers })
  const listJson = await listRes.json()
  const reportId = listJson?.data?.records?.[0]?.id
  if (!reportId) throw new Error('列表为空，无法压测详情接口')

  const targets = [
    { name: 'GET /reports（列表）', url: `${base}/api/v1/reports?scope=mine&page=1&size=10` },
    { name: 'GET /reports/{id}（详情）', url: `${base}/api/v1/reports/${reportId}` }
  ]

  // 并发均分到每个接口：目标 5 + 5，而不是 10 个 worker 抢两个接口（旧口径会让人误读成"每接口 10 并发"）
  const perTarget = Math.max(1, Math.floor(concurrency / targets.length))
  const effectiveConcurrency = perTarget
  const results = []
  const endAt = Date.now() + durationSec * 1000

  async function worker(target) {
    while (Date.now() < endAt) {
      const start = performance.now()
      let ok = false
      let status = 0
      try {
        const res = await fetch(target.url, { headers })
        status = res.status
        ok = res.ok
        await res.arrayBuffer()
      } catch {
        ok = false
      }
      results.push({ name: target.name, ok, status, ms: performance.now() - start })
    }
  }

  await Promise.all(targets.flatMap(t => Array.from({ length: perTarget }, () => worker(t))))

  const summary = {}
  for (const r of results) {
    const s = summary[r.name] ||= { total: 0, ok: 0, latencies: [], statuses: {} }
    s.total++
    if (r.ok) s.ok++
    s.statuses[r.status] = (s.statuses[r.status] || 0) + 1
    s.latencies.push(r.ms)
  }

  const totalRequests = results.length
  const output = {
    base,
    concurrency,
    effectiveConcurrency,
    durationSec,
    generatedAt: new Date().toISOString(),
    totalRequests,
    totalQps: Number((totalRequests / durationSec).toFixed(2)),
    scenarios: {}
  }

  console.log(`\n压测结果：总并发 ${concurrency}（每接口 ${effectiveConcurrency}），持续 ${durationSec}s，目标 ${base}\n`)
  console.log('场景'.padEnd(30) + '请求数'.padStart(8) + '成功'.padStart(8) + 'QPS'.padStart(10) +
    'P50(ms)'.padStart(10) + 'P95(ms)'.padStart(10) + 'P99(ms)'.padStart(10))
  for (const [name, s] of Object.entries(summary)) {
    const sorted = s.latencies.sort((a, b) => a - b)
    const qps = s.total / durationSec
    output.scenarios[name] = {
      effectiveConcurrency,
      total: s.total,
      ok: s.ok,
      errorRate: Number(((s.total - s.ok) / s.total).toFixed(4)),
      qps: Number(qps.toFixed(2)),
      p50: Number(percentile(sorted, 50).toFixed(1)),
      p95: Number(percentile(sorted, 95).toFixed(1)),
      p99: Number(percentile(sorted, 99).toFixed(1)),
      statuses: s.statuses
    }
    console.log(name.padEnd(30) + String(s.total).padStart(8) + String(s.ok).padStart(8) +
      qps.toFixed(2).padStart(10) + percentile(sorted, 50).toFixed(1).padStart(10) +
      percentile(sorted, 95).toFixed(1).padStart(10) + percentile(sorted, 99).toFixed(1).padStart(10))
  }

  console.log(`\n合计：${totalRequests} 请求 / ${durationSec}s = ${output.totalQps} QPS（单接口数字不能当总吞吐报）`)
  console.log('\nJSON:')
  console.log(JSON.stringify(output, null, 2))
}

main().catch(e => {
  console.error('压测失败:', e.message)
  process.exit(1)
})
