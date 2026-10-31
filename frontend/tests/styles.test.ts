import { readFileSync } from 'node:fs'
import postcss from 'postcss'
import { describe, expect, it } from 'vitest'
import { cascade, contrast, themeTokens, type StyleNode } from '../src/test/styleCascade'

// Vitest stubs CSS imports (including ?raw) when CSS processing is disabled.
// Keep Node-only IO outside src so production does not need Node typings.
const stylesheet = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
const css = () => stylesheet

it('loads the real stylesheet rather than Vitest’s empty CSS module', () => {
  expect(stylesheet).toContain('.nav-tabs .nav-icon')
  expect(stylesheet).toContain('--bg-card:')
})

describe.each([false, true])('dim text contrast (light=%s)', (light) => {
  it.each(['--bg', '--bg-card', '--bg-elevated', '--bg-hover'])('meets normal-text AA on %s', (background) => {
    const tokens = themeTokens(css(), light)
    const ratio = contrast(tokens['--text-dim'], tokens[background])
    expect(ratio, `${tokens['--text-dim']} on ${tokens[background]}`).toBeGreaterThanOrEqual(4.5)
  })
})

describe('shared poster-card styles', () => {
  it.each([
    ['', 'background'], [':hover', 'transform'], [' .cover', 'aspect-ratio'],
    [' .info', 'padding'], [' .title', 'min-height'], [' .meta', 'margin-top'],
  ])('maintains %s common declarations in one shared rule', (suffix, property) => {
    const shared: string[] = []
    postcss.parse(css()).walkRules((rule) => {
      if (rule.selectors.includes(`.subject-card${suffix}`) && rule.selectors.includes(`.fav-card${suffix}`)) {
        rule.walkDecls(property, (decl) => { shared.push(decl.value) })
      }
    })
    expect(shared).toHaveLength(1)
  })

  it.each([480, 720, 721, 1101])('preserves card geometry and responsive grids at %spx', (width) => {
    for (const kind of ['subject-card', 'fav-card']) {
      const card: StyleNode = { tag: kind === 'subject-card' ? 'a' : 'div', classes: [kind] }
      expect(cascade(css(), card, { width })).toMatchObject({
        background: 'var(--bg-card)', border: '1px solid var(--border)',
        'border-radius': 'var(--radius)', overflow: 'hidden', display: 'block', position: 'relative',
        transition: 'transform 0.12s ease, border-color 0.15s, box-shadow 0.15s, background 0.15s',
      })
      expect(cascade(css(), { ...card, states: ['hover'] }, { width })).toMatchObject({
        transform: 'translateY(-3px)', 'border-color': 'var(--accent)', 'box-shadow': 'var(--shadow-hover)',
      })
      expect(cascade(css(), card, { width, motion: 'reduce' }).transition).toBe('none')
      expect(cascade(css(), { tag: 'img', classes: ['cover'], parent: card }, { width })).toMatchObject({
        width: '100%', 'aspect-ratio': '3 / 4', 'object-fit': 'cover', display: 'block',
      })
      expect(cascade(css(), { tag: 'div', classes: ['info'], parent: card }, { width }).padding).toBe('10px 12px')
      expect(cascade(css(), { tag: 'div', classes: ['title'], parent: card }, { width })).toMatchObject({
        'font-size': '14px', 'font-weight': '500', display: '-webkit-box', '-webkit-line-clamp': '2',
        '-webkit-box-orient': 'vertical', overflow: 'hidden', 'min-height': '44px',
      })
      const meta = cascade(css(), { tag: 'div', classes: ['meta'], parent: card }, { width })
      expect(meta).toMatchObject({ display: 'flex', 'margin-top': '6px', 'font-size': '12px', 'justify-content': 'space-between' })
      expect(meta['align-items']).toBe(kind === 'fav-card' ? 'center' : undefined)
    }
    for (const grid of ['result-grid', 'fav-grid']) {
      expect(cascade(css(), { tag: 'div', classes: [grid] }, { width })).toMatchObject({
        display: 'grid', gap: width <= 720 ? '10px' : '16px',
        'grid-template-columns': `repeat(auto-fill, minmax(${width <= 720 ? 110 : 150}px, 1fr))`,
      })
    }
  })
})

describe('favorite keyboard focus cascade', () => {
  it('reveals the remove action for keyboard focus without requiring hover', () => {
    const card: StyleNode = { tag: 'div', classes: ['fav-card'], states: ['focus-within'] }
    const remove: StyleNode = { tag: 'button', classes: ['fav-remove'], states: ['focus', 'focus-visible'], parent: card }
    const focused = cascade(css(), remove, { width: 1101, hover: 'hover' })
    expect(focused.opacity).toBe('1')
    expect(focused.outline).toBe('2px solid var(--accent)')
  })

  it.each(['watching', 'wish', 'onhold', 'done'])('preserves a visible outline on the %s status select', (status) => {
    const select: StyleNode = { tag: 'select', classes: ['fav-status', `status-${status}`], states: ['focus', 'focus-visible'] }
    const focused = cascade(css(), select, { width: 1101, hover: 'hover' })
    expect(focused.outline).toBe('2px solid var(--accent)')
    expect(focused['outline-offset']).toBe('2px')
  })
})

describe('navigation responsive cascade', () => {
  it.each([720, 721, 900, 1100, 1101])('keeps visible navigation labels at width %s', (width) => {
    const tabs: StyleNode = { tag: 'div', classes: ['nav-tabs'] }
    const link: StyleNode = { tag: 'a', parent: tabs }
    const icon = cascade(css(), { tag: 'svg', classes: ['nav-icon'], parent: link }, { width }).display ?? 'inline'
    const label = cascade(css(), { tag: 'span', classes: ['nav-text'], parent: link }, { width }).display ?? 'inline'
    expect(icon !== 'none' || label !== 'none').toBe(true)
    if (width > 720 && width <= 1100) {
      expect(icon).toBe('inline-flex')
      expect(cascade(css(), link, { width }).padding).toBe('7px 10px')
    }
  })
})
