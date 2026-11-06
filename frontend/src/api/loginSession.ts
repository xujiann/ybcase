/** A token is usable only after its identity has loaded; partial sign-in must roll back. */
export async function loginSession(actions: {
  clear: () => void
  requestToken: () => Promise<unknown>
  setToken: (token: string) => void
  fetchUser: () => Promise<void>
}): Promise<void> {
  actions.clear()
  try {
    const token = await actions.requestToken()
    if (typeof token !== 'string' || !token.trim()) throw new Error('登录响应缺少有效令牌，请重试')
    actions.setToken(token)
    await actions.fetchUser()
  } catch (error) {
    actions.clear()
    throw error
  }
}
