export const SOURCE_CONFIG_CHANGED_EVENT = 'animeko-source-config-change'

let revision = 0
const listeners = new Set<(revision: number) => void>()

/** Session-local revision: reads alone do not invalidate otherwise useful search snapshots. */
export function getSourceRevision(): number {
  return revision
}

/** Call only after a source mutation has succeeded (including partial subscription import). */
export function notifySourcesChanged(): void {
  revision++
  for (const listener of listeners) listener(revision)
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(SOURCE_CONFIG_CHANGED_EVENT, { detail: revision }))
  }
}

export function onSourcesChanged(listener: (revision: number) => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}
