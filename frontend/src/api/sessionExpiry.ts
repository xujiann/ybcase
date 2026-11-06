export interface SessionStamp { token: string; generation: number }

/** Coordinate 401s from one sign-in without letting an older request invalidate a newer sign-in. */
export function createSessionExpiry() {
  let token = '', generation = 0, expired = false, notified = false, redirecting = false
  function reset(nextToken = '') {
    token = nextToken; generation++; expired = false; notified = false; redirecting = false
  }
  function capture(nextToken: string): SessionStamp {
    if (nextToken !== token) reset(nextToken)
    return { token, generation }
  }
  function isCurrent(stamp: SessionStamp, currentToken: string) {
    capture(currentToken)
    return stamp.generation === generation && stamp.token === token
  }
  function isExpired(stamp: SessionStamp, currentToken: string) {
    return isCurrent(stamp, currentToken) && expired
  }
  function expire(stamp: SessionStamp, currentToken: string) {
    if (!stamp.token || !isCurrent(stamp, currentToken)) return { current: false, notify: false, first: false }
    const first = !expired, notify = !notified
    expired = true; notified = true
    return { current: true, notify, first }
  }
  function beginRedirect(stamp: SessionStamp, currentToken: string) {
    if (!isCurrent(stamp, currentToken) || redirecting) return false
    redirecting = true
    return true
  }
  return { reset, capture, isCurrent, isExpired, expire, beginRedirect }
}

export const sessionExpiry = createSessionExpiry()
export const SESSION_EXPIRED_EVENT = 'ybcase-session-expired'
