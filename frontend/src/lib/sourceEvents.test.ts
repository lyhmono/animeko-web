import { describe, expect, it, vi } from 'vitest'

import { getSourceRevision, notifySourcesChanged, onSourcesChanged } from './sourceEvents'

describe('source configuration revisions', () => {
  it('notifies subscribed search consumers with a monotonic revision and supports cleanup', () => {
    const before = getSourceRevision()
    const listener = vi.fn()
    const unsubscribe = onSourcesChanged(listener)
    notifySourcesChanged()
    expect(getSourceRevision()).toBe(before + 1)
    expect(listener).toHaveBeenCalledWith(before + 1)
    unsubscribe()
    notifySourcesChanged()
    expect(getSourceRevision()).toBe(before + 2)
    expect(listener).toHaveBeenCalledOnce()
  })
})
