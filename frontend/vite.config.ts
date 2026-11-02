import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// 前端与后端同源部署（后端托管 dist 并代理 /api）
export default defineConfig({
  plugins: [react()],
  // 必须用绝对路径：后端以 SPA 回退方式服务 dist，相对路径 './assets/...'
  // 在 /subject/xxx 等深路径下会被解析为 /subject/assets/...，命中回退拿到
  // index.html（MIME 为 text/html），导致 module script 加载失败、页面白屏
  base: '/',
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1600,
  },
  test: {
    // 默认 5s 在慢机器上会把负载误判成失败：FavoritesPage 那条在 28 个 worker 并行时
    // 实测 4.9s（超时判红），单独跑 3.4s（绿）—— 同一份代码两种结论。放宽到 20s，
    // 让红绿只由断言决定，不由机器当时多忙决定。
    testTimeout: 20000,
    hookTimeout: 20000,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/main.tsx'],
      // 当前实测（include 全部 src，含尚未覆盖的页面）：
      // 语句 57.3 / 分支 46.1 / 函数 53.3 / 行 60.6。
      // 阈值取略低于现状，只用来拦「新代码完全没测试」的回退，不追求数字好看。
      thresholds: {
        statements: 55,
        branches: 44,
        functions: 51,
        lines: 58,
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // 用 127.0.0.1 避免 localhost 在某些环境解析到 ::1，而后端只监听 IPv4。
        target: 'http://127.0.0.1:8080',
        // 必须保留浏览器发来的 Host。后端对写请求比较 Origin 与 Host 的
        // host+port 来拦 CSRF；changeOrigin=true 会把 Host 改成 8080，
        // 于是 dev 页面（5173）的登录、收藏、导入数据源全部被判成跨站 403。
        changeOrigin: false,
      },
    },
  },
})
