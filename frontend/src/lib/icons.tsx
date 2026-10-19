/** 内联 SVG 图标集（stroke 风格，currentColor 继承文字色） */
import type { SVGProps } from 'react'

interface IconProps extends SVGProps<SVGSVGElement> {
  size?: number
}

function Svg({ size = 18, children, ...rest }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {children}
    </svg>
  )
}

export function IconPlay({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M6 4.5v15l13-7.5z" fill="currentColor" stroke="none" />
    </Svg>
  )
}

export function IconPause({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <rect x={6} y={4.5} width={4} height={15} rx={1} fill="currentColor" stroke="none" />
      <rect x={14} y={4.5} width={4} height={15} rx={1} fill="currentColor" stroke="none" />
    </Svg>
  )
}

export function IconPrev({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M18 5v14L8 12z" fill="currentColor" stroke="none" />
      <rect x={5} y={4.5} width={2.4} height={15} rx={1} fill="currentColor" stroke="none" />
    </Svg>
  )
}

export function IconNext({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M6 5v14l10-7z" fill="currentColor" stroke="none" />
      <rect x={16.6} y={4.5} width={2.4} height={15} rx={1} fill="currentColor" stroke="none" />
    </Svg>
  )
}

export function IconVolume({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M4 9v6h4l5 4V5L8 9H4z" />
      <path d="M16.5 8.5a5 5 0 0 1 0 7" />
      <path d="M19 6a8.5 8.5 0 0 1 0 12" />
    </Svg>
  )
}

export function IconMute({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M4 9v6h4l5 4V5L8 9H4z" />
      <path d="M16 9.5l5 5" />
      <path d="M21 9.5l-5 5" />
    </Svg>
  )
}

export function IconFullscreen({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M8 3H5a2 2 0 0 0-2 2v3" />
      <path d="M16 3h3a2 2 0 0 1 2 2v3" />
      <path d="M16 21h3a2 2 0 0 0 2-2v-3" />
      <path d="M8 21H5a2 2 0 0 1-2-2v-3" />
    </Svg>
  )
}

export function IconFullscreenExit({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M3 8h3a2 2 0 0 0 2-2V3" />
      <path d="M21 8h-3a2 2 0 0 1-2-2V3" />
      <path d="M21 16h-3a2 2 0 0 0-2 2v3" />
      <path d="M3 16h3a2 2 0 0 1 2 2v3" />
    </Svg>
  )
}

export function IconSearch({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <circle cx={11} cy={11} r={7} />
      <path d="M21 21l-4.3-4.3" />
    </Svg>
  )
}

export function IconHeart({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M12 20.5S3.5 15.4 3.5 9.4A4.6 4.6 0 0 1 12 6.7a4.6 4.6 0 0 1 8.5 2.7c0 6-8.5 11.1-8.5 11.1z" />
    </Svg>
  )
}

export function IconHeartFilled({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path
        d="M12 20.5S3.5 15.4 3.5 9.4A4.6 4.6 0 0 1 12 6.7a4.6 4.6 0 0 1 8.5 2.7c0 6-8.5 11.1-8.5 11.1z"
        fill="currentColor"
      />
    </Svg>
  )
}

export function IconTrash({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M3 6h18" />
      <path d="M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2" />
      <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
      <path d="M10 11v6" />
      <path d="M14 11v6" />
    </Svg>
  )
}

export function IconSun({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <circle cx={12} cy={12} r={4} />
      <path d="M12 2v2" />
      <path d="M12 20v2" />
      <path d="M4.9 4.9l1.4 1.4" />
      <path d="M17.7 17.7l1.4 1.4" />
      <path d="M2 12h2" />
      <path d="M20 12h2" />
      <path d="M4.9 19.1l1.4-1.4" />
      <path d="M17.7 6.3l1.4-1.4" />
    </Svg>
  )
}

export function IconMoon({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
    </Svg>
  )
}



export function IconHome({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M3 10.5L12 3l9 7.5" />
      <path d="M5 9.5V21h14V9.5" />
      <path d="M9.5 21v-6h5v6" />
    </Svg>
  )
}

export function IconLayers({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M12 2l9 5-9 5-9-5 9-5z" />
      <path d="M3 12l9 5 9-5" />
      <path d="M3 17l9 5 9-5" />
    </Svg>
  )
}

export function IconSettings({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <circle cx={12} cy={12} r={3} />
      <path d="M19.4 15a1.7 1.7 0 0 0 .34 1.87l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.7 1.7 0 0 0-1.87-.34 1.7 1.7 0 0 0-1.03 1.56V21a2 2 0 1 1-4 0v-.09a1.7 1.7 0 0 0-1.11-1.56 1.7 1.7 0 0 0-1.87.34l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-1.56-1.03H3a2 2 0 1 1 0-4h.09A1.7 1.7 0 0 0 4.65 8.9a1.7 1.7 0 0 0-.34-1.87l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1.03-1.56V3a2 2 0 1 1 4 0v.09c0 .68.4 1.3 1.03 1.56.6.27 1.3.14 1.87-.34l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.7 1.7 0 0 0-.34 1.87v0c.27.6.87 1.03 1.56 1.03H21a2 2 0 1 1 0 4h-.09c-.68 0-1.3.4-1.51 1.03z" />
    </Svg>
  )
}


export function IconHistory({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M3 12a9 9 0 1 0 9-9 9 9 0 0 0-7.6 4.2" />
      <path d="M3 3v4.5h4.5" />
      <path d="M12 7.5V12l3.5 2" />
    </Svg>
  )
}

export function IconClock({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <circle cx={12} cy={12} r={9} />
      <path d="M12 7v5l3.5 2" />
    </Svg>
  )
}

export function IconKeyboard({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <rect x={2.5} y={6} width={19} height={12} rx={2} />
      <path d="M6.5 10h.01" />
      <path d="M10 10h.01" />
      <path d="M13.5 10h.01" />
      <path d="M17.5 10h.01" />
      <path d="M7 14h10" />
    </Svg>
  )
}




export function IconCheck({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M20 6L9 17l-5-5" />
    </Svg>
  )
}


export function IconLogout({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3" />
      <path d="M16 15l3-3-3-3" />
      <path d="M19 12H9" />
    </Svg>
  )
}

export function IconLink({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M10 13a5 5 0 0 0 7 0l2-2a5 5 0 0 0-7-7l-1 1" />
      <path d="M14 11a5 5 0 0 0-7 0l-2 2a5 5 0 0 0 7 7l1-1" />
    </Svg>
  )
}

/** 画中画（Picture in Picture） */
export function IconPip({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <rect x="2" y="4" width="20" height="16" rx="2" />
      <rect x="12" y="12" width="8" height="6" rx="1" fill="currentColor" stroke="none" />
    </Svg>
  )
}

/** 亮度（手势提示与快捷键卡片用） */
export function IconBrightness({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </Svg>
  )
}

/** 倍速（仪表盘式，与文字倍速值配套显示） */
export function IconGauge({ size, ...rest }: IconProps) {
  return (
    <Svg size={size} {...rest}>
      <path d="M12 15l4-6" />
      <path d="M3.5 18a9 9 0 1 1 17 0" />
    </Svg>
  )
}
