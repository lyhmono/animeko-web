import { vi } from 'vitest'

/** Minimal focus/event boundary for hook unit tests, not a layout engine. */
export class FocusDocument extends EventTarget {
  activeElement: FocusElement
  body: FocusElement
  constructor() {
    super()
    this.body = new FocusElement(this, 'body')
    this.activeElement = this.body
  }
  querySelectorAll(selector: string) { return this.body.querySelectorAll(selector) }
  querySelector(selector: string) { return this.querySelectorAll(selector)[0] ?? null }
}

export class FocusElement {
  children: FocusElement[] = []
  parentElement: FocusElement | null = null
  inert = false
  hidden = false
  disabled = false
  visible = true
  tabIndex: number
  attributes = new Map<string, string>()
  constructor(public ownerDocument: FocusDocument, public tagName = 'div') {
    this.tabIndex = ['button', 'input', 'select', 'textarea', 'a'].includes(tagName) ? 0 : -1
  }
  get isConnected(): boolean { return this === this.ownerDocument.body || !!this.parentElement?.isConnected }
  append(...children: FocusElement[]) {
    for (const child of children) { child.parentElement = this; this.children.push(child) }
  }
  remove() {
    if (this.parentElement) this.parentElement.children = this.parentElement.children.filter((child) => child !== this)
    this.parentElement = null
    if (this.ownerDocument.activeElement === this) this.ownerDocument.activeElement = this.ownerDocument.body
  }
  contains(node: unknown): boolean { return node === this || this.children.some((child) => child.contains(node)) }
  setAttribute(name: string, value: string) { this.attributes.set(name, value) }
  getAttribute(name: string) { return this.attributes.get(name) ?? null }
  hasAttribute(name: string) { return this.attributes.has(name) }
  removeAttribute(name: string) { this.attributes.delete(name) }
  getClientRects() { return this.visible && !this.closest('[hidden]') ? [{}] : [] }
  closest(selector: string): FocusElement | null {
    if (selector.includes('[inert]') && this.inert) return this
    if (selector.includes('[hidden]') && this.hidden) return this
    return this.parentElement?.closest(selector) ?? null
  }
  matches(selector: string) {
    if (selector === ':disabled') return this.disabled
    if (selector.includes('data-modal-autofocus')) return this.hasAttribute('data-modal-autofocus')
    if (selector.includes('data-modal-return-focus')) return this.hasAttribute('data-modal-return-focus')
    if (selector.includes('autofocus')) return this.hasAttribute('autofocus')
    return this.tabIndex >= 0 && !this.disabled
  }
  querySelectorAll(selector: string): FocusElement[] {
    return this.children.flatMap((child) => [ ...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector) ])
  }
  querySelector(selector: string) { return this.querySelectorAll(selector)[0] ?? null }
  focus() {
    if (!this.isConnected || this.closest('[inert]')) return
    this.ownerDocument.activeElement = this
    this.ownerDocument.dispatchEvent(new Event('focusin'))
  }
}

const observers = new Set<FocusObserver>()
export class FocusObserver {
  constructor(private callback: () => void) {}
  observe() { observers.add(this) }
  disconnect() { observers.delete(this) }
  static mutate() { for (const observer of observers) observer.callback() }
}
export function installFocusDom() {
  const document = new FocusDocument()
  const background = new FocusElement(document)
  const trigger = new FocusElement(document, 'button')
  const mask = new FocusElement(document)
  const modal = new FocusElement(document)
  const cancel = new FocusElement(document, 'button')
  const confirm = new FocusElement(document, 'button')
  background.append(trigger)
  modal.append(cancel, confirm)
  mask.append(modal)
  document.body.append(background, mask)
  trigger.focus()
  vi.stubGlobal('document', document)
  vi.stubGlobal('HTMLElement', FocusElement)
  vi.stubGlobal('MutationObserver', FocusObserver)
  return { document, background, trigger, modal, cancel, confirm, mask }
}
export function key(document: FocusDocument, value: string, shiftKey = false) {
  const event = new Event('keydown', { cancelable: true })
  Object.assign(event, { key: value, shiftKey })
  document.dispatchEvent(event)
  return event
}
