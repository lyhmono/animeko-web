import { useState } from 'react'

/**
 * 封面图。两件事必须兜住，否则用户看到的就是一片空白：
 *  1) 图床不可达或被防盗链拒绝（Bangumi 的 lain.bgm.tv、源站自己的 img CDN，
 *     在部分网络环境下就是拉不到）—— 首次加载失败后改走本站 /api/proxy 同源转发，
 *     由服务器去取图（服务器到多数图床是通的，且带上同源身份也绕开了防盗链与混合内容）。
 *  2) 代理也失败时要落到和「没有封面」完全一样的占位块：直接隐藏 <img> 会在网格里
 *     留一个既没图也没边框的空洞，看起来像渲染坏了（实测浏览器里就是这种感觉）。
 */
export default function Poster({
  src,
  srcSet,
  alt,
  className = '',
  sizes,
}: {
  src: string
  srcSet?: string
  alt: string
  className?: string
  sizes?: string
}) {
  const [viaProxy, setViaProxy] = useState(false)
  const [dead, setDead] = useState(false)

  if (!src || dead) return <div className={`${className} cover-empty`} aria-hidden />

  // 走代理时不能带 srcSet：候选地址是别家的尺寸版本，代理过去只会白白多打几趟
  const effectiveSrc = viaProxy ? `/api/proxy?url=${encodeURIComponent(src)}` : src
  return (
    <img
      className={className}
      src={effectiveSrc}
      srcSet={viaProxy ? undefined : srcSet}
      sizes={sizes}
      alt={alt}
      loading="lazy"
      onError={() => {
        if (viaProxy) setDead(true)
        else setViaProxy(true)
      }}
    />
  )
}
