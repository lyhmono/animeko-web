import type { ReactElement, ReactNode } from 'react'

type Cell = {
  value?: unknown
  deps?: readonly unknown[]
  cleanup?: () => void
  effect?: () => void | (() => void)
}
let active: HookHarness | undefined

/** A dependency-free hook driver: invokes real page JSX and effect callbacks.
 * It does not emulate browser layout or React DOM commits. Those remain E2E work.
 */
export class HookHarness {
  private cells: Cell[] = []
  private cursor = 0
  private pending = new Set<number>()
  writes = 0
  tree: ReactNode = null

  cell(): Cell {
    const index = this.cursor++
    return (this.cells[index] ??= {})
  }

  effect(create: Cell['effect'], deps?: readonly unknown[]) {
    const index = this.cursor
    const cell = this.cell()
    if (!cell.effect || !sameDeps(cell.deps, deps)) {
      cell.effect = create
      cell.deps = deps
      this.pending.add(index)
    }
  }

  render(component: () => ReactNode): ReactNode {
    this.cursor = 0
    active = this
    try {
      this.tree = component()
      return this.tree
    } finally {
      active = undefined
    }
  }

  commit() {
    const pending = [...this.pending]
    this.pending.clear()
    for (const index of pending) {
      const cell = this.cells[index]
      cell.cleanup?.()
      const cleanup = cell.effect?.()
      cell.cleanup = typeof cleanup === 'function' ? cleanup : undefined
    }
  }

  unmount() {
    for (const cell of this.cells) cell.cleanup?.()
    this.pending.clear()
  }
}

function sameDeps(a?: readonly unknown[], b?: readonly unknown[]) {
  return !!a && !!b && a.length === b.length && a.every((value, index) => Object.is(value, b[index]))
}
function owner() {
  if (!active) throw new Error('Render the tested component through HookHarness')
  return active
}

export const testHooks = {
  useState<T>(initial: T | (() => T)) {
    const harness = owner()
    const cell = harness.cell()
    if (!('value' in cell)) cell.value = typeof initial === 'function' ? (initial as () => T)() : initial
    return [cell.value as T, (next: T | ((previous: T) => T)) => {
      harness.writes++
      cell.value = typeof next === 'function' ? (next as (previous: T) => T)(cell.value as T) : next
    }] as const
  },
  useRef<T>(initial: T) {
    const cell = owner().cell()
    if (!('value' in cell)) cell.value = { current: initial }
    return cell.value as { current: T }
  },
  useMemo<T>(create: () => T, deps?: readonly unknown[]) {
    const cell = owner().cell()
    if (!('value' in cell) || !sameDeps(cell.deps, deps)) {
      cell.value = create()
      cell.deps = deps
    }
    return cell.value as T
  },
  useCallback<T>(callback: T, deps?: readonly unknown[]) {
    return testHooks.useMemo(() => callback, deps)
  },
  useEffect(create: () => void | (() => void), deps?: readonly unknown[]) {
    owner().effect(create, deps)
  },
  useLayoutEffect(create: () => void | (() => void), deps?: readonly unknown[]) {
    owner().effect(create, deps)
  },
}

export type TestElement = ReactElement<Record<string, any>>
export function elements(tree: ReactNode): TestElement[] {
  if (Array.isArray(tree)) return tree.flatMap(elements)
  if (!tree || typeof tree !== 'object' || !('props' in tree)) return []
  const element = tree as TestElement
  return [element, ...elements(element.props.children)]
}
export function text(tree: ReactNode): string {
  if (Array.isArray(tree)) return tree.map(text).join('')
  if (typeof tree === 'string' || typeof tree === 'number') return String(tree)
  if (tree && typeof tree === 'object' && 'props' in tree) return text((tree as TestElement).props.children)
  return ''
}
export function button(tree: ReactNode, label: string): TestElement {
  const found = elements(tree).find((element) => element.type === 'button' && text(element.props.children) === label)
  if (!found) throw new Error(`Button not found: ${label}`)
  return found
}
export function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
export async function settle() {
  // Flush short .then/.catch/.finally chains without real timers or network.
  for (let i = 0; i < 16; i++) await Promise.resolve()
}
