export interface BlobPreviewState { url: string; loading: boolean; failed: boolean }
export function createBlobPreview(state: BlobPreviewState, urls: { createObjectURL: (blob: Blob) => string; revokeObjectURL: (url: string) => void } = URL) {
  let generation = 0
  function clear() {
    generation++
    if (state.url) urls.revokeObjectURL(state.url)
    state.url = ''; state.loading = false; state.failed = false
  }
  async function show(request?: () => Promise<Blob>) {
    clear()
    const run = generation
    if (!request) return
    state.loading = true
    try {
      const blob = await request()
      if (run === generation) state.url = urls.createObjectURL(blob)
    } catch { if (run === generation) state.failed = true }
    finally { if (run === generation) state.loading = false }
  }
  return { show, clear }
}
