export type PreflightAction = 'DECIDE' | 'CLOSE'
export interface PreflightResult {
  action: PreflightAction
  ready: boolean
  items: { key: string; label: string; passed: boolean; message: string; role: string }[]
}
export interface PreflightState { result: PreflightResult | null; checking: boolean }

/** A read-only check is valid only for the exact case and form that produced it. */
export function createPreflightCheck(
  state: PreflightState,
  snapshot: () => { caseId: string; body: Record<string, unknown> },
  request: (caseId: string, body: Record<string, unknown>) => Promise<PreflightResult>,
) {
  let generation = 0
  function invalidate() {
    generation++
    state.result = null
    state.checking = false
  }
  async function check(): Promise<boolean> {
    const run = ++generation
    const submitted = JSON.parse(JSON.stringify(snapshot()))
    state.result = null
    state.checking = true
    try {
      const result = await request(submitted.caseId, submitted.body)
      if (run !== generation || JSON.stringify(snapshot()) !== JSON.stringify(submitted)) return false
      if (result?.action !== submitted.body.action || !Array.isArray(result.items)) return false
      state.result = result
      return result.ready === true && result.items.every(item => item.passed === true)
    } finally {
      if (run === generation) state.checking = false
    }
  }
  return { check, invalidate }
}

/** Keep server order within each group and leave the response untouched. */
export function prioritizePreflightItems(items: PreflightResult['items']): PreflightResult['items'] {
  return [...items.filter(item => !item.passed), ...items.filter(item => item.passed)]
}
