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
