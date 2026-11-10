/** Keep a draft's key through an uncertain response so a retry reuses the original approval. */
export function createApprovalRequestKey() {
  let previous: { fingerprint: string; key: string } | undefined
  return {
    get(body: object, context?: unknown) {
      const fingerprint = JSON.stringify([context, body])
      if (previous?.fingerprint === fingerprint) return previous.key
      const key = Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('')
      previous = { fingerprint, key }
      return key
    },
    clear() { previous = undefined },
  }
}
