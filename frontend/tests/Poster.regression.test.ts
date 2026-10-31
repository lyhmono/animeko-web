import { describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { elements, HookHarness } from '../src/test/uiHarness'

vi.mock('react', async (importOriginal) => ({
  ...await importOriginal<typeof import('react')>(),
  ...(await import('../src/test/uiHarness')).testHooks,
}))
import Poster from '../src/components/Poster'

const props = { src: 'https://images.test/cover.jpg', srcSet: 'https://images.test/small.jpg 100w',
  sizes: '220px', alt: 'Show', className: 'cover' }

describe('FP-12 Poster allows intentional first-screen priority without changing list defaults', () => {
  it('supports eager/high priority on the real img element', () => {
    const harness = new HookHarness()
    const priority = { ...props, loading: 'eager' as const, fetchPriority: 'high' as const }
    const tree = harness.render(() => Poster(priority))
    const html = renderToStaticMarkup(tree)
    expect(html).toContain('loading="eager"')
    expect(html).toContain('fetchPriority="high"')
  })

  it('keeps lists lazy, responsive and no-referrer', () => {
    const harness = new HookHarness()
    const html = renderToStaticMarkup(harness.render(() => Poster(props)))
    expect(html).toContain('loading="lazy"')
    expect(html).not.toContain('fetchPriority="high"')
    expect(html).toContain('srcSet="https://images.test/small.jpg 100w"')
    expect(html).toContain('sizes="220px"')
    expect(html).toContain('referrerPolicy="no-referrer"')
  })

  it('retains proxy fallback, srcset removal, placeholder and recovery for a new source', () => {
    const harness = new HookHarness()
    let current = { ...props, loading: 'eager' as const, fetchPriority: 'high' as const }
    const render = () => harness.render(() => Poster(current))
    elements(render())[0].props.onError()
    let image = elements(render())[0]
    expect(image.props.src).toBe('/api/proxy?url=' + encodeURIComponent(props.src))
    expect(image.props.srcSet).toBeUndefined()
    expect(image.props.loading).toBe('eager')
    image.props.onError()
    expect(elements(render())[0].props.className).toContain('cover-empty')
    current = { ...current, src: 'https://images.test/new.jpg' }
    image = elements(render())[0]
    expect(image.props.src).toBe(current.src)
    expect(image.props.srcSet).toBe(props.srcSet)
  })
})
