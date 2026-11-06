import { defineStore } from 'pinia'
import client from '../api/client'
import { loginSession } from '../api/loginSession'
import { sessionExpiry } from '../api/sessionExpiry'

export interface MenuItem {
  id: number
  parentId: number
  name: string
  type: 'DIR' | 'MENU' | 'BUTTON'
  path: string
  perm: string
  icon: string
}

export interface UserInfo {
  id: number
  username: string
  realName: string
  accountCategory?: 'DISTRICT' | 'BUREAU' | 'AGENCY' | ''
  roles: string[]
  menus: MenuItem[]
  /** 初始口令由管理员设定，首次登录须改；MainLayout 据此弹出不可关闭的改密框 */
  mustChangePassword?: boolean
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('bureau_token') || '',
    user: null as UserInfo | null,
  }),
  getters: {
    loggedIn: (s) => !!s.token,
    menuTree(s): (MenuItem & { children: MenuItem[] })[] {
      const menus = s.user?.menus ?? []
      return menus
        .filter((m) => m.type === 'DIR')
        .map((dir) => ({
          ...dir,
          children: menus.filter((m) => m.parentId === dir.id && m.type === 'MENU'
            && (s.user?.accountCategory !== 'DISTRICT' || m.path !== '/case/supervise')),
        }))
    },
  },
  actions: {
    async login(username: string, password: string) {
      await loginSession({
        clear: () => this.logout(),
        requestToken: async () => (await client.post<{ data: { token: string } }>('/auth/login', { username, password })).data.data?.token,
        setToken: token => { sessionExpiry.reset(token); this.token = token; localStorage.setItem('bureau_token', token) },
        fetchUser: () => this.fetchMe(),
      })
    },
    async fetchMe() {
      const token = this.token, stamp = sessionExpiry.capture(token)
      const resp = await client.get<{ data: UserInfo }>('/auth/me')
      const currentToken = localStorage.getItem('bureau_token') || ''
      if (this.token !== token || !sessionExpiry.isCurrent(stamp, currentToken)
          || sessionExpiry.isExpired(stamp, currentToken)) return
      this.user = resp.data.data
    },
    logout() {
      sessionExpiry.reset()
      this.token = ''
      this.user = null
      localStorage.removeItem('bureau_token')
    },
  },
})
