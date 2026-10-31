import { afterEach, describe, expect, it, vi } from 'vitest'
import { HookHarness } from '../test/uiHarness'
import { FocusElement, FocusObserver, installFocusDom, key } from '../test/focusDom'

vi.mock('react', async (importOriginal) => ({
  ...await importOriginal<typeof import('react')>(),
  ...(await import('../test/uiHarness')).testHooks,
}))

import { useModalFocus } from './useModalFocus'


afterEach(() => vi.unstubAllGlobals())
describe('modal focus ownership', () => {
  it('keeps Tab inside the modal, closes with Escape and restores its trigger', () => {
    const dom = installFocusDom()
    const harness = new HookHarness()
    const close = vi.fn()
    const ref = { current: dom.modal as unknown as HTMLElement }
    harness.render(() => { useModalFocus(ref, true, close); return null })
    harness.commit()
    expect(dom.document.activeElement === dom.cancel).toBe(true)
    dom.confirm.focus()
    expect(key(dom.document, 'Tab').defaultPrevented).toBe(true)
    expect(dom.document.activeElement === dom.cancel).toBe(true)
    expect(key(dom.document, 'Tab', true).defaultPrevented).toBe(true)
    expect(dom.document.activeElement).toBe(dom.confirm)
    expect(key(dom.document, 'Escape').defaultPrevented).toBe(true)
    expect(close).toHaveBeenCalledOnce()
    harness.unmount()
    expect(dom.document.activeElement === dom.trigger).toBe(true)
  })

  it('makes background inert and restores existing inert state on close', () => {
    const dom = installFocusDom()
    const untouched = new FocusElement(dom.document)
    untouched.inert = true
    dom.document.body.append(untouched)
    const harness = new HookHarness()
    const ref = { current: dom.modal as unknown as HTMLElement }
    harness.render(() => { useModalFocus(ref, true); return null })
    harness.commit()
    expect(dom.background.inert).toBe(true)
    dom.trigger.focus()
    expect(dom.document.activeElement === dom.cancel).toBe(true)
    harness.unmount()
    expect(dom.background.inert).toBe(false)
    expect(untouched.inert).toBe(true)
  })

  it('restores an explicit page fallback if deletion removes the trigger', () => {
    const dom = installFocusDom()
    const fallback = new FocusElement(dom.document, 'h2')
    fallback.setAttribute('data-modal-return-focus', '')
    dom.background.append(fallback)
    const harness = new HookHarness()
    const ref = { current: dom.modal as unknown as HTMLElement }
    harness.render(() => { useModalFocus(ref, true); return null })
    harness.commit()
    dom.trigger.remove()
    harness.unmount()
    expect(dom.document.activeElement === fallback).toBe(true)
  })

  it('recovers focus after dynamic controls disappear and uses the latest close callback', () => {
    const dom = installFocusDom()
    const harness = new HookHarness()
    const ref = { current: dom.modal as unknown as HTMLElement }
    const firstClose = vi.fn()
    const latestClose = vi.fn()
    harness.render(() => { useModalFocus(ref, true, firstClose); return null })
    harness.commit()
    dom.cancel.remove()
    FocusObserver.mutate()
    expect(dom.document.activeElement === dom.confirm).toBe(true)
    harness.render(() => { useModalFocus(ref, true, latestClose); return null })
    harness.commit()
    key(dom.document, 'Escape')
    expect(firstClose).not.toHaveBeenCalled()
    expect(latestClose).toHaveBeenCalledOnce()
    harness.unmount()
  })
})
