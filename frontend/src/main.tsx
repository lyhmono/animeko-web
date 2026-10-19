import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { getTheme, syncThemeColor } from './lib/storage'
import './styles.css'

// 渲染前应用已保存的主题，避免首屏闪烁
const initialTheme = getTheme()
document.documentElement.dataset.theme = initialTheme
syncThemeColor(initialTheme)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)

// 注册 Service Worker（仅生产构建）。
// dev 下不注册：Vite 的 HMR 与 SW 缓存叠加会出现「改了代码页面不变」的怪现象，
// 排查时极难想到是 SW 在缓存旧模块。
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    // 路径写死 /sw.js：SW 的默认作用域由它所在目录决定，放在根才能覆盖全站
    void navigator.serviceWorker.register('/sw.js').catch(() => {
      // 注册失败不影响任何功能：PWA 装不上而已，页面照常用
    })
  })
}
