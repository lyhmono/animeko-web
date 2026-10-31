import postcss, { type AtRule, type Node, type Rule } from 'postcss'

export type StyleNode = { tag: string; classes?: string[]; states?: string[]; parent?: StyleNode }
export type StyleEnvironment = { width: number; hover?: 'hover' | 'none'; motion?: 'reduce' | 'no-preference' }

function applies(rule: Rule, environment: StyleEnvironment): boolean {
  for (let parent: Node | undefined = rule.parent; parent; parent = parent.parent) {
    if (parent.type !== 'atrule') continue
    if ((parent as AtRule).name !== 'media') return false
    const query = (parent as AtRule).params
    const min = query.match(/min-width:\s*(\d+)px/)
    const max = query.match(/max-width:\s*(\d+)px/)
    if (min && environment.width < Number(min[1])) return false
    if (max && environment.width > Number(max[1])) return false
    const hover = query.match(/\(hover:\s*(none|hover)\)/)
    if (hover && (environment.hover ?? 'hover') !== hover[1]) return false
    const motion = query.match(/prefers-reduced-motion:\s*(reduce|no-preference)/)
    if (motion && (environment.motion ?? 'no-preference') !== motion[1]) return false
  }
  return true
}
function matchesSimple(selector: string, node: StyleNode): boolean {
  if (selector === '*') return true
  const tag = selector.match(/^[a-z][\w-]*/i)?.[0]
  if (tag && tag !== node.tag) return false
  let rest = tag ? selector.slice(tag.length) : selector
  const tokens = rest.match(/\.[\w-]+|:[\w-]+/g) ?? []
  for (const token of tokens) {
    if (token[0] === '.' ? !node.classes?.includes(token.slice(1)) : !node.states?.includes(token.slice(1))) return false
    rest = rest.replace(token, '')
  }
  return rest === ''
}
function matches(selector: string, node: StyleNode): boolean {
  const parts = selector.trim().split(/\s+/)
  if (!matchesSimple(parts.pop()!, node)) return false
  let ancestor = node.parent
  while (parts.length) {
    const part = parts.pop()!
    while (ancestor && !matchesSimple(part, ancestor)) ancestor = ancestor.parent
    if (!ancestor) return false
    ancestor = ancestor.parent
  }
  return true
}
function specificity(selector: string): number {
  const ids = selector.match(/#[\w-]+/g)?.length ?? 0
  const classes = selector.match(/\.[\w-]+|:[\w-]+|\[/g)?.length ?? 0
  const tags = selector.match(/(?:^|\s)[a-z][\w-]*/gi)?.length ?? 0
  return ids * 10000 + classes * 100 + tags
}

/** Actual PostCSS declarations, media conditions, specificity and source order.
 * The selector model is intentionally bounded to the simple selectors under test.
 */
export function cascade(css: string, node: StyleNode, environment: StyleEnvironment): Record<string, string> {
  const values = new Map<string, { value: string; weight: number; order: number }>()
  let order = 0
  postcss.parse(css).walkRules((rule) => {
    if (!applies(rule, environment)) return
    const matched = rule.selectors.filter((selector) => matches(selector, node))
    if (!matched.length) return
    const weight = Math.max(...matched.map(specificity))
    rule.walkDecls((declaration) => {
      const previous = values.get(declaration.prop)
      const next = { value: declaration.value, weight: weight + (declaration.important ? 1000000 : 0), order: order++ }
      if (!previous || next.weight > previous.weight || (next.weight === previous.weight && next.order > previous.order)) values.set(declaration.prop, next)
    })
  })
  return Object.fromEntries([...values].map(([property, declaration]) => [property, declaration.value]))
}
export function themeTokens(css: string, light: boolean): Record<string, string> {
  const values: Record<string, string> = {}
  postcss.parse(css).walkRules((rule) => {
    if (rule.selector !== ':root' && !(light && rule.selector === ":root[data-theme='light']")) return
    rule.walkDecls((declaration) => { if (declaration.prop.startsWith('--')) values[declaration.prop] = declaration.value })
  })
  return values
}
export function contrast(first: string, second: string): number {
  const luminance = (hex: string) => {
    const channels = hex.replace('#', '').match(/../g)!.map((part) => {
      const channel = parseInt(part, 16) / 255
      return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
    })
    return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
  }
  const a = luminance(first)
  const b = luminance(second)
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
}
