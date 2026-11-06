export interface LoadableResource<T> { data: T; ready: boolean; loading: boolean; failed: boolean }
export function resourceState<T>(data: T): LoadableResource<T> { return { data, ready: false, loading: false, failed: false } }
export function createResourceLoader<T>(state: LoadableResource<T>, request: () => Promise<T>) {
  let generation = 0
  async function load() {
    const run = ++generation
    state.ready = false; state.loading = true; state.failed = false
    try {
      const data = await request()
      if (run !== generation) return false
      state.data = data; state.ready = true
      return true
    } catch { if (run === generation) state.failed = true; return false }
    finally { if (run === generation) state.loading = false }
  }
  // Closing or changing a detail panel invalidates in-flight results immediately.
  function invalidate() {
    generation++
    state.ready = false; state.loading = false; state.failed = false
  }
  return { load, invalidate }
}
