import { historyReadErrorMessage, isTransientHistoryReadFailure } from './historyRead.ts'

export interface HistoryPage<T> { rows: T[]; total: number; page: number; size: number }
export interface HistoryPageState<T> extends HistoryPage<T> { q: string; loading: boolean; failed: boolean; error: string }
export interface HistoryPageRequest { page: number; size: number; q?: string }
export interface HistoryReadContext { signal: AbortSignal }
export function historyPageState<T>(): HistoryPageState<T> {
  return { rows: [], total: 0, page: 1, size: 20, q: '', loading: false, failed: false, error: '' }
}
function retryPause(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise(resolve => {
    function finish() { clearTimeout(timer); signal.removeEventListener('abort', finish); resolve() }
    const timer = setTimeout(finish, milliseconds)
    signal.addEventListener('abort', finish, { once: true })
    if (signal.aborted) finish()
  })
}
/** The server owns visibility, redaction and total; the browser never slices a capped array. */
export function createHistoryPagination<T>(state: HistoryPageState<T>, request: (params: HistoryPageRequest, context: HistoryReadContext) => Promise<HistoryPage<T>>, retryDelayMs = 250) {
  let generation = 0
  let controller: AbortController | undefined
  let disposed = false
  async function load(): Promise<boolean> {
    if (disposed) return false
    const run = ++generation
    controller?.abort()
    const current = controller = new AbortController()
    const params: HistoryPageRequest = { page: state.page, size: state.size }
    if (state.q.trim()) params.q = state.q.trim()
    state.loading = true
    state.failed = false
    state.error = ''
    try {
      let data: HistoryPage<T>
      try {
        data = await request(params, { signal: current.signal })
      } catch (error) {
        if (run !== generation || current.signal.aborted) return false
        if (!isTransientHistoryReadFailure(error)) throw error
        await retryPause(retryDelayMs, current.signal)
        if (run !== generation || current.signal.aborted) return false
        // One retry only, using the same query/page snapshot. Never replay a mutation.
        data = await request(params, { signal: current.signal })
      }
      if (run !== generation) return false
      const lastPage = Math.max(1, Math.ceil(data.total / data.size))
      if (!data.rows.length && data.total > 0 && data.page > lastPage) {
        state.total = data.total
        state.page = lastPage
        state.size = data.size
        return await load()
      }
      state.rows = data.rows
      state.total = data.total
      state.size = data.size
      state.page = data.total === 0 ? 1 : data.page
      return true
    } catch (error) {
      if (run === generation && !current.signal.aborted) {
        // Reset both together: a pagination widget normalizing an empty total must not
        // emit a different page and accidentally start another retry cycle.
        state.rows = []; state.total = 0; state.page = 1; state.failed = true; state.error = historyReadErrorMessage(error)
      }
      return false
    } finally {
      if (run === generation) state.loading = false
    }
  }
  function firstPage() { state.page = 1; return load() }
  function changePage(value: number) {
    const page = Math.max(1, Math.trunc(value))
    if (page === state.page) return Promise.resolve(false)
    state.page = page
    return load()
  }
  function changeSize(value: number) {
    if (![20,50,100].includes(value) || value === state.size) return Promise.resolve(false)
    state.size = value
    return firstPage()
  }
  return { load, firstPage, search: firstPage, changePage, changeSize, dispose: () => { disposed = true; generation++; controller?.abort(); state.loading = false } }
}
