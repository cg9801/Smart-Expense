import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 前端单测：jsdom 环境 + Vue SFC 支持（docs/14 §16.6）
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.js']
  }
})
