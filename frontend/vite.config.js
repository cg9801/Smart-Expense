import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// dev 期同源策略：浏览器只看到 5173，/api 由 Vite 转发到后端 8080，
// 避免跨域（决策记录见 docs/14-前端设计.md §7）。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    },
    watch: {
      // Windows 下编辑器写文件会生成 .xxx.tmpdir 临时目录，chokidar 监听它会 EBUSY 崩进程。
      // 显式忽略临时文件/目录，保留 Vite 默认忽略项（自定义 ignored 会覆盖默认值）。
      ignored: [
        '**/.git/**',
        '**/node_modules/**',
        '**/test-results/**',
        '**/*.tmpdir/**',
        '**/.tmpdir/**',
        '**/*.tmp'
      ]
    }
  },
  // build 产物可拷入 Spring Boot src/main/resources/static/ 实现单端口演示
  build: {
    outDir: 'dist'
  }
})
