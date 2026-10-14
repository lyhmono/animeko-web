import { useEffect, useRef } from 'react'
import type { DanmakuOptions, DdComment } from '../types'

interface Props {
  comments: DdComment[]
  /** 当前播放时间（秒） */
  currentTime: number
  playing: boolean
  options: DanmakuOptions
}

interface ActiveDanmaku {
  text: string
  color: string
  x: number
  y: number
  width: number
  mode: number
  /** 滚动速度 px/s */
  speed: number
  /** 顶部/底部弹幕剩余显示时间 */
  ttl: number
}

const TRACK_GAP = 4
const TOP_BOTTOM_TTL = 4.0
const MAX_ACTIVE_BASE = 60

/** 十进制 RGB → #rrggbb */
function toHex(color: number): string {
  const c = color & 0xffffff
  return '#' + c.toString(16).padStart(6, '0')
}

export default function DanmakuCanvas({ comments, currentTime, playing, options }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const activeRef = useRef<ActiveDanmaku[]>([])
  const spawnedRef = useRef<Set<number>>(new Set())
  const trackLastSpawnRef = useRef<Map<number, { x: number; time: number }>>(new Map())
  const lastTimeRef = useRef(currentTime)
  const lastFrameRef = useRef(0)
  const sizeRef = useRef({ width: 0, height: 0 })

  const optionsRef = useRef(options)
  optionsRef.current = options
  // 播放进度/状态经 ref 读取：currentTime 每约 250ms 变化一次，
  // 若列入 effect 依赖会导致 rAF 主循环被反复重建（弹幕移动呈跳变）
  const currentTimeRef = useRef(currentTime)
  currentTimeRef.current = currentTime
  const playingRef = useRef(playing)
  playingRef.current = playing

  // 弹幕按出现时间预排序 + 发射游标：每帧只处理新到时的条目，
  // 避免每帧对全量 comments 做 filter（弹幕量大时每帧 O(n) → O(新增)）
  const sortedRef = useRef<DdComment[]>([])
  const cursorRef = useRef(0)
  // 屏蔽词按输入变化缓存：避免每帧 split/filter
  const blockWordsRef = useRef<string[]>([])
  useEffect(() => {
    blockWordsRef.current = options.blockWords
      .split(/[,，\s]+/)
      .map((w) => w.trim())
      .filter(Boolean)
  }, [options.blockWords])

  // 弹幕数据/进度变化时重置已发射标记（换集时重新发射）
  useEffect(() => {
    spawnedRef.current = new Set()
    activeRef.current = []
    trackLastSpawnRef.current = new Map()
    sortedRef.current = [...comments].sort((a, b) => a.time - b.time)
    cursorRef.current = 0
  }, [comments])

  // 尺寸自适应
  useEffect(() => {
    const container = containerRef.current
    const canvas = canvasRef.current
    if (!container || !canvas) return
    const resize = () => {
      const rect = container.getBoundingClientRect()
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      canvas.width = Math.max(1, Math.round(rect.width * dpr))
      canvas.height = Math.max(1, Math.round(rect.height * dpr))
      const ctx = canvas.getContext('2d')
      if (ctx) ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      sizeRef.current = { width: rect.width, height: rect.height }
    }
    resize()
    const observer = new ResizeObserver(resize)
    observer.observe(container)
    return () => observer.disconnect()
  }, [])

  // 主渲染循环
  useEffect(() => {
    let raf = 0
    const loop = (now: number) => {
      raf = requestAnimationFrame(loop)
      const canvas = canvasRef.current
      const ctx = canvas?.getContext('2d')
      if (!canvas || !ctx) return
      const opts = optionsRef.current
      const { width, height } = sizeRef.current
      const dt = lastFrameRef.current === 0 ? 0 : Math.min((now - lastFrameRef.current) / 1000, 0.25)
      lastFrameRef.current = now

      // 检测 seek 回退：重置发射状态
      if (currentTimeRef.current < lastTimeRef.current - 1) {
        spawnedRef.current.clear()
        activeRef.current = []
        trackLastSpawnRef.current.clear()
        cursorRef.current = 0
      }
      lastTimeRef.current = currentTimeRef.current

      ctx.clearRect(0, 0, width, height)

      if (!opts.enabled || !playingRef.current || opts.density <= 0) {
        activeRef.current = []
        return
      }

      // ---- 发射新弹幕 ----
      const fontSize = opts.fontSize
      const trackHeight = fontSize + TRACK_GAP
      const rollingTracks = Math.max(1, Math.floor((height * 0.75) / trackHeight))
      const fixedTracks = Math.max(1, Math.floor((height * 0.5) / trackHeight))
      const maxActive = Math.round(MAX_ACTIVE_BASE * opts.density)
      const rollingSpeed = (width / 6) * opts.speed

      const blockWords = blockWordsRef.current
      const sorted = sortedRef.current
      const spawnBefore = currentTimeRef.current + 0.1
      // 指针游标推进：只处理新到时的条目；已发射/同屏占满时停在原地不下帧重试
      while (cursorRef.current < sorted.length) {
        const c = sorted[cursorRef.current]
        if (c.time > spawnBefore) break
        if (spawnedRef.current.has(c.cid) || activeRef.current.length >= maxActive) break
        if (blockWords.some((w) => c.text.includes(w))) {
          // 屏蔽词：消费并标记，避免每帧重复匹配
          cursorRef.current++
          spawnedRef.current.add(c.cid)
          continue
        }
        ctx.font = `bold ${fontSize}px sans-serif`
        const textWidth = ctx.measureText(c.text).width
        let placed = false

        if (c.mode === 1) {
          for (let track = 0; track < rollingTracks; track++) {
            const last = trackLastSpawnRef.current.get(track)
            if (last) {
              const elapsed = currentTimeRef.current - last.time
              const rightEdge = last.x - elapsed * rollingSpeed
              // 要求上一条弹幕右边缘已越过发射点，且留出间距
              if (rightEdge > width - textWidth - 24) continue
            }
            trackLastSpawnRef.current.set(track, { x: width, time: currentTimeRef.current })
            activeRef.current.push({
              text: c.text,
              color: toHex(c.color),
              x: width,
              y: track * trackHeight + TRACK_GAP,
              width: textWidth,
              mode: 1,
              speed: rollingSpeed,
              ttl: 0,
            })
            placed = true
            break
          }
        } else {
          const isTop = c.mode === 5
          const base = isTop ? 0 : height - trackHeight
          const trackOffset = Math.floor(Math.random() * fixedTracks)
          for (let i = 0; i < fixedTracks; i++) {
            const track = (trackOffset + i) % fixedTracks
            const key = isTop ? 1000 + track : 2000 + track
            const last = trackLastSpawnRef.current.get(key)
            if (last && currentTimeRef.current - last.time < TOP_BOTTOM_TTL) continue
            trackLastSpawnRef.current.set(key, { x: 0, time: currentTimeRef.current })
            activeRef.current.push({
              text: c.text,
              color: toHex(c.color),
              x: (width - textWidth) / 2,
              y: base - track * trackHeight,
              width: textWidth,
              mode: c.mode,
              speed: 0,
              ttl: TOP_BOTTOM_TTL,
            })
            placed = true
            break
          }
        }
        cursorRef.current++
        spawnedRef.current.add(c.cid)
        if (!placed) {
          // 轨道占满：丢弃该条以避免堆积（与原行为一致）
        }
      }

      // ---- 更新与绘制 ----
      ctx.globalAlpha = opts.opacity
      ctx.textBaseline = 'top'
      const survivors: ActiveDanmaku[] = []
      for (const d of activeRef.current) {
        if (d.mode === 1) {
          d.x -= d.speed * dt
          if (d.x + d.width < -10) continue
          ctx.strokeStyle = 'rgba(0,0,0,0.7)'
          ctx.lineWidth = Math.max(2, fontSize / 8)
          ctx.strokeText(d.text, d.x, d.y)
          ctx.fillStyle = d.color
          ctx.fillText(d.text, d.x, d.y)
        } else {
          d.ttl -= dt
          if (d.ttl <= 0) continue
          const fade = Math.min(1, d.ttl / 0.4)
          ctx.globalAlpha = opts.opacity * fade
          ctx.strokeStyle = 'rgba(0,0,0,0.7)'
          ctx.lineWidth = Math.max(2, fontSize / 8)
          ctx.strokeText(d.text, d.x, d.y)
          ctx.fillStyle = d.color
          ctx.fillText(d.text, d.x, d.y)
          ctx.globalAlpha = opts.opacity
        }
        survivors.push(d)
      }
      activeRef.current = survivors
    }
    raf = requestAnimationFrame(loop)
    return () => cancelAnimationFrame(raf)
  }, [comments])

  return (
    <div className="danmaku-layer" ref={containerRef}>
      <canvas ref={canvasRef} />
    </div>
  )
}
