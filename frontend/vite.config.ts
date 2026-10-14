import { defineConfig } from 'vite'
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
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
