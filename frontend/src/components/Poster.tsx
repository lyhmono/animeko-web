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
      // 不发 Referer。默认行为会把**当前页的完整 URL**送给每家第三方图床
      // （Bangumi 的 CDN、各数据源自己的 img 域名），而搜索页的地址是
      // /search?q=<关键词> —— 等于把用户搜了什么、以及这台自建实例的域名，
      // 一并抄送给结果里每一个源的站点。图床并不靠 Referer 才能出图
      // （防盗链的站点直连失败后本来就会走本站代理那条路）。
      referrerPolicy="no-referrer"
      onError={() => {
        if (viaProxy) setDead(true)
        else setViaProxy(true)
      }}
    />
  )
}
