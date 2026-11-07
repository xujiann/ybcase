/** Undefined means no focus, zero means an invalid link. Never turn an invalid ID into an unfiltered search. */
export function parseTaskFocus(value: unknown): number | undefined {
  if (value === undefined || value === null) return undefined
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) return 0
  const id = Number(value)
  return Number.isSafeInteger(id) ? id : 0
}

export function withoutTaskFocus(query: Record<string, unknown>, key = 'focus'): Record<string, unknown> {
  const next = { ...query }
  delete next[key]
  return next
}
