// 弹幕设置与本地持久化。
export interface DanmakuFilterRule {
  id: string;
  keyword: string;
  type: 'normal' | 'regex';
  enabled: boolean;
}

export interface DanmakuSettings {
  enabled: boolean;
  opacity: number; // 0.1 ~ 1
  fontSize: number; // px
  speed: number; // 滚动时长（秒），越小越快
  marginTop: number; // px
  marginBottom: number; // px
  maxlength: number; // 单条弹幕长度上限
  unlimited: boolean; // 同屏不限数量
  synchronousPlayback: boolean; // 与播放同步
  filterRules: DanmakuFilterRule[];
}

export const DEFAULT_DANMAKU_SETTINGS: DanmakuSettings = {
  enabled: true,
  opacity: 0.9,
  fontSize: 24,
  speed: 6,
  marginTop: 10,
  marginBottom: 60,
  maxlength: 100,
  unlimited: false,
  synchronousPlayback: true,
  filterRules: [],
};

const KEY = 'danmaku_settings';

export function getDanmakuSettings(): DanmakuSettings {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...DEFAULT_DANMAKU_SETTINGS };
    const parsed = JSON.parse(raw) as Partial<DanmakuSettings>;
    return {
      ...DEFAULT_DANMAKU_SETTINGS,
      ...parsed,
      opacity: clamp(parsed.opacity ?? DEFAULT_DANMAKU_SETTINGS.opacity, 0.1, 1),
      fontSize: clamp(parsed.fontSize ?? 24, 12, 48),
      speed: clamp(parsed.speed ?? 6, 2, 15),
      filterRules: Array.isArray(parsed.filterRules) ? parsed.filterRules : [],
    };
  } catch {
    return { ...DEFAULT_DANMAKU_SETTINGS };
  }
}

export function saveDanmakuSettings(next: Partial<DanmakuSettings>): DanmakuSettings {
  const merged = { ...getDanmakuSettings(), ...next };
  try {
    localStorage.setItem(KEY, JSON.stringify(merged));
  } catch {
    // 隐私模式忽略
  }
  return merged;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, Number.isFinite(value) ? value : min));
}

/** 弹幕过滤：普通包含匹配 + 正则。 */
export function buildDanmakuFilter(rules: DanmakuFilterRule[]): (text: string) => boolean {
  const active = rules.filter((r) => r.enabled && r.keyword);
  if (!active.length) return () => true;
  const compiled = active.map((rule) => {
    if (rule.type === 'regex') {
      try {
        return { regex: new RegExp(rule.keyword, 'i'), keyword: null };
      } catch {
        return { regex: null, keyword: rule.keyword.toLowerCase() };
      }
    }
    return { regex: null, keyword: rule.keyword.toLowerCase() };
  });
  return (text: string) => {
    const lower = text.toLowerCase();
    return !compiled.some((c) => (c.regex ? c.regex.test(text) : c.keyword && lower.includes(c.keyword)));
  };
}

/** 弹弹 play p 字段 → ArtPlayer 弹幕格式。
 *  p = "时间,模式,颜色,时间戳,[...]"，模式 1 滚动 / 4 底部 / 5 顶部。 */
export interface ArtDanmaku {
  text: string;
  time: number;
  color: string;
  mode: 0 | 1 | 2;
}

export function convertComments(comments: Array<{ p: string; m: string }>): ArtDanmaku[] {
  const result: ArtDanmaku[] = [];
  for (const c of comments) {
    const parts = c.p.split(',');
    const time = Number(parts[0]);
    const rawMode = Number(parts[1]);
    const colorNum = Number(parts[2]);
    if (!Number.isFinite(time) || !c.m) continue;
    result.push({
      text: c.m,
      time,
      color: Number.isFinite(colorNum) ? `#${(colorNum >>> 0).toString(16).padStart(6, '0').slice(-6)}` : '#ffffff',
      mode: rawMode === 5 ? 1 : rawMode === 4 ? 2 : 0,
    });
  }
  return result.sort((a, b) => a.time - b.time);
}

/** 前端解析本地上传的 B 站 / 弹弹 play XML 弹幕。 */
export function parseLocalDanmakuXml(xml: string): Array<{ p: string; m: string }> {
  const result: Array<{ p: string; m: string }> = [];
  const doc = new DOMParser().parseFromString(xml, 'text/xml');
  doc.querySelectorAll('d').forEach((el) => {
    const p = el.getAttribute('p') ?? '';
    const m = el.textContent ?? '';
    if (p && m) result.push({ p, m });
  });
  return result;
}
