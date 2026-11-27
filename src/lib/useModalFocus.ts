import { useEffect, useRef, type RefObject } from 'react'

const FOCUSABLE = 'a[href], button, input, select, textarea, [tabindex], [contenteditable="true"]'

function focusableChildren(modal: HTMLElement): HTMLElement[] {
  return Array.from(modal.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((element) =>
    element.tabIndex >= 0 && !element.matches(':disabled') &&
    !element.closest('[hidden], [inert]') && element.getClientRects().length > 0,
  )
}

/** Own focus while open, without restarting the effect when onClose changes. */
export function useModalFocus(
  ref: RefObject<HTMLElement | null>,
  open: boolean,
  onClose?: () => void,
): void {
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  useEffect(() => {
    const modal = ref.current
    if (!open || !modal) return
    const document = modal.ownerDocument
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const hadTabIndex = modal.hasAttribute('tabindex')
    if (!hadTabIndex) modal.setAttribute('tabindex', '-1')
    // Inert only siblings of the modal's ancestor path, never the modal itself.
    const background = new Map<HTMLElement, boolean>()
    let branch: HTMLElement = modal
    while (branch.parentElement) {
      const parent = branch.parentElement
      for (const sibling of Array.from(parent.children)) {
        if (sibling !== branch && sibling instanceof HTMLElement) {
          background.set(sibling, sibling.inert)
          sibling.inert = true
        }
      }
      if (parent === document.body) break
      branch = parent
    }
    const focusFirst = () => {
      const items = focusableChildren(modal)
      const preferred = items.find((element) => element.hasAttribute('data-modal-autofocus') || element.hasAttribute('autofocus'))
      ;(preferred ?? items[0] ?? modal).focus()
    }
    if (!modal.contains(document.activeElement)) focusFirst()

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && closeRef.current) {
        event.preventDefault()
        event.stopPropagation()
        closeRef.current()
      } else if (event.key === 'Tab') {
        const items = focusableChildren(modal)
        const first = items[0]
        const last = items[items.length - 1]
        if (!first) {
          event.preventDefault()
          modal.focus()
        } else if (!modal.contains(document.activeElement) || document.activeElement === modal ||
          (event.shiftKey ? document.activeElement === first : document.activeElement === last)) {
          event.preventDefault()
          ;(event.shiftKey ? last : first).focus()
        }
      }
    }
    const keepFocus = () => {
      const focused = document.activeElement
      if (!modal.contains(focused) || (focused instanceof HTMLElement &&
        (focused.matches(':disabled') || focused.closest('[hidden], [inert]')))) focusFirst()
    }
    document.addEventListener('keydown', onKey, { capture: true })
    document.addEventListener('focusin', keepFocus, { capture: true })
    const observer = new MutationObserver(keepFocus)
    observer.observe(modal, { childList: true, subtree: true, attributes: true, attributeFilter: ['disabled', 'hidden', 'inert'] })
    return () => {
      observer.disconnect()
      document.removeEventListener('keydown', onKey, { capture: true })
      document.removeEventListener('focusin', keepFocus, { capture: true })
      for (const [element, wasInert] of background) element.inert = wasInert
      if (!hadTabIndex) modal.removeAttribute('tabindex')
      if (previous?.isConnected) previous.focus()
      else document.querySelector<HTMLElement>('[data-modal-return-focus]')?.focus()
    }
  }, [ref, open])
}
