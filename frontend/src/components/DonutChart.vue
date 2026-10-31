<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 环状图（docs/14 §12）：
 * - 进入视口后从 0 生长：每段 <circle> 用 pathLength=100 + dasharray 随 rAF 进度增长
 * - 错峰：每段延迟 120ms，段内 800ms，easeOutCubic
 * - 渐变描边：每段一个 linearGradient（亮化色 → 本色）+ drop-shadow 柔光
 * - 中心数字同步滚动，完成后轻微脉冲
 * - prefers-reduced-motion：直接渲染终态
 */
const props = defineProps({
  // [{ name, color, amount }]
  items: { type: Array, default: () => [] },
  total: { type: Number, default: 0 },
  label: { type: String, default: '部门预算占比' },
  size: { type: Number, default: 220 }
})

const root = ref(null)
const growth = ref(0)      // 全局进度 0..1（rAF 驱动）
const displayed = ref(0)   // 中心数字滚动值
const done = ref(false)

const DURATION = 1000
const STAGGER = 120
const SEG_DURATION = 800

const reduce = (typeof window !== 'undefined' && window.matchMedia)
  ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
  : false

const segments = computed(() => {
  const list = props.items.filter(it => Number(it.amount) > 0)
  const sum = list.reduce((acc, it) => acc + Number(it.amount), 0)
  let acc = 0
  return list.map((it, index) => {
    const pct = sum > 0 ? (Number(it.amount) / sum) * 100 : 0
    const seg = { ...it, pct, start: acc, index }
    acc += pct
    return seg
  })
})

const easeOutCubic = t => 1 - Math.pow(1 - t, 3)
const clamp = (v, a, b) => Math.min(b, Math.max(a, v))

// 每段目标弧长随本地进度增长；dasharray 单位是 pathLength=100 后的百分比
function segDash(seg) {
  const local = clamp((growth.value * DURATION - seg.index * STAGGER) / SEG_DURATION, 0, 1)
  const p = seg.pct * easeOutCubic(local)
  return `${p} ${100 - p}`
}

// 把十六进制色向白色提亮，做渐变描边的高光端
function lighten(hex, amt = 0.42) {
  const c = (hex || '#2563eb').replace('#', '')
  const n = parseInt(c.length === 6 ? c : '2563eb', 16)
  let r = (n >> 16) & 255
  let g = (n >> 8) & 255
  let b = n & 255
  r = Math.round(r + (255 - r) * amt)
  g = Math.round(g + (255 - g) * amt)
  b = Math.round(b + (255 - b) * amt)
  return `#${((r << 16) | (g << 8) | b).toString(16).padStart(6, '0')}`
}

function fmt(v) {
  return '¥ ' + Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

let rafId = 0
let io = null
let started = false

function start() {
  if (started) return
  started = true
  if (reduce) {
    growth.value = 1
    displayed.value = props.total
    done.value = true
    return
  }
  const t0 = performance.now()
  const step = now => {
    const p = clamp((now - t0) / DURATION, 0, 1)
    growth.value = p
    displayed.value = props.total * easeOutCubic(p)
    if (p < 1) {
      rafId = requestAnimationFrame(step)
    } else {
      done.value = true
      displayed.value = props.total
    }
  }
  rafId = requestAnimationFrame(step)
}

onMounted(() => {
  if ('IntersectionObserver' in window) {
    io = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) {
        start()
        io.disconnect()
      }
    }, { threshold: 0.3 })
    io.observe(root.value)
  } else {
    start()
  }
})

onBeforeUnmount(() => {
  if (rafId) cancelAnimationFrame(rafId)
  if (io) io.disconnect()
})
</script>

<template>
  <div ref="root" class="donut-root" :style="{ width: `${size}px`, height: `${size}px` }">
    <svg class="donut-svg" viewBox="0 0 200 200" role="img" :aria-label="label">
      <defs>
        <linearGradient v-for="seg in segments" :key="`grad-${seg.index}`"
                        :id="`seg-grad-${seg.index}`" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" :stop-color="lighten(seg.color, 0.5)" />
          <stop offset="100%" :stop-color="seg.color" />
        </linearGradient>
      </defs>
      <!-- 底环：内容完成前的占位，保证空态也有形 -->
      <circle cx="100" cy="100" r="70" fill="none" stroke="var(--c-border)" stroke-width="22" opacity=".35" />
      <circle v-for="seg in segments" :key="seg.index"
              cx="100" cy="100" r="70" fill="none"
              :stroke="`url(#seg-grad-${seg.index})`"
              stroke-width="22" stroke-linecap="butt"
              :pathLength="100"
              :stroke-dasharray="segDash(seg)"
              :transform="`rotate(${seg.start * 3.6 - 90} 100 100)`"
              :style="{ filter: `drop-shadow(0 0 8px ${seg.color}55)` }" />
    </svg>
    <div class="donut-center" :class="{ 'is-done': done }">
      <div class="donut-label">{{ label }}</div>
      <div class="donut-value">{{ fmt(displayed) }}</div>
    </div>
  </div>
</template>

<style scoped>
.donut-root { position: relative; flex: 0 0 auto; }
.donut-svg { width: 100%; height: 100%; overflow: visible; }
.donut-center {
  position: absolute; inset: 0;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  pointer-events: none;
}
.donut-label { font-size: 12px; color: var(--c-text-2); }
.donut-value { font-size: 18px; font-weight: 700; font-variant-numeric: tabular-nums; }
.donut-center.is-done { animation: donut-pulse 900ms cubic-bezier(.34, 1.56, .64, 1) 1; }
@keyframes donut-pulse {
  0% { transform: scale(1); }
  38% { transform: scale(1.045); }
  100% { transform: scale(1); }
}
@media (prefers-reduced-motion: reduce) {
  .donut-center.is-done { animation: none; }
}
</style>
