import { afterEach, describe, expect, it, vi } from 'vitest'
import { elements, HookHarness } from '../src/test/uiHarness'

vi.mock('react', async (importOriginal) => ({
  ...await importOriginal<typeof import('react')>(),
  ...(await import('../src/test/uiHarness')).testHooks,
}))
import VideoPlayer from '../src/components/VideoPlayer'

const harnesses: HookHarness[] = []
afterEach(() => {
  for (const harness of harnesses.splice(0)) harness.unmount()
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

function rateMenu() {
  vi.useFakeTimers()
  const document = { activeElement: null as unknown, pictureInPictureEnabled: false }
  vi.stubGlobal('document', document)
  vi.stubGlobal('window', new EventTarget())
  const harness = new HookHarness()
  harnesses.push(harness)
  const render = () => harness.render(() => VideoPlayer({ src: 'https://media.test/a.mp4' }))
  let trigger = elements(render()).find((element) => element.props['aria-label'] === '倍速')!
  trigger.props.onClick()
  const tree = render()
  trigger = elements(tree).find((element) => element.props['aria-label'] === '倍速')!
  const menu = elements(tree).find((element) => element.props.role === 'menu')!
  const buttons = elements(menu).filter((element) => element.props.role === 'menuitemradio')
  const focusItems = buttons.map(() => ({ focus: vi.fn(function (this: unknown) { document.activeElement = this }) }))
  const selected = buttons.findIndex((button) => button.props['aria-checked'])
  const triggerFocus = vi.fn(() => { document.activeElement = trigger })
  if (trigger.props.ref) trigger.props.ref.current = { focus: triggerFocus }
  elements(tree).find((element) => element.props.className === 'video-shell')!.props.ref.current = {
    contains: () => true, querySelector: () => focusItems[selected],
  }
  harness.commit()
  return { harness, render, menu, buttons, focusItems, selected, document, triggerFocus }
}

describe('FP-07 speed menu has a keyboard menu model', () => {
  it('focuses the selected item and exposes a single tab stop', () => {
    const { buttons, focusItems, selected, document } = rateMenu()
    expect(document.activeElement === focusItems[selected]).toBe(true)
    expect(buttons.filter((button) => button.props.tabIndex === 0)).toHaveLength(1)
    expect(buttons.every((button) => button.props.tabIndex === 0 || button.props.tabIndex === -1)).toBe(true)
  })

  it('handles arrows, Home/End and Escape without changing video seek or losing trigger focus', () => {
    const { render, menu, focusItems, selected, document, triggerFocus } = rateMenu()
    expect(typeof menu.props.onKeyDown).toBe('function')
    const invoke = (key: string) => {
      const event = { key, currentTarget: { querySelectorAll: () => focusItems }, preventDefault: vi.fn(), stopPropagation: vi.fn() }
      menu.props.onKeyDown(event)
      expect(event.preventDefault).toHaveBeenCalledOnce()
      expect(event.stopPropagation).toHaveBeenCalledOnce()
    }
    invoke('ArrowDown')
    expect(document.activeElement === focusItems[selected + 1]).toBe(true)
    invoke('End')
    expect(document.activeElement === focusItems[focusItems.length - 1]).toBe(true)
    invoke('Home')
    expect(document.activeElement === focusItems[0]).toBe(true)
    invoke('ArrowUp')
    expect(document.activeElement === focusItems[focusItems.length - 1]).toBe(true)
    invoke('Escape')
    expect(triggerFocus).toHaveBeenCalledOnce()
    expect(elements(render()).some((element) => element.props.role === 'menu')).toBe(false)
  })
})
