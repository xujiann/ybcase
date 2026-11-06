<template>
  <el-container class="layout">
    <!-- 手机端点遮罩关闭抽屉菜单 -->
    <div v-if="isMobile && menuOpen" class="backdrop" @click="menuOpen = false" />
    <el-aside width="220px" class="aside" :class="{ 'aside-drawer': isMobile }"
              :style="isMobile ? { transform: menuOpen ? 'translateX(0)' : 'translateX(-100%)' } : {}">
      <div class="logo">案件查办系统</div>
      <el-menu router :default-active="$route.path" background-color="#1c2b45" text-color="#c7d0dc" active-text-color="#409eff"
               @select="onMenuSelect">
        <el-menu-item v-if="!isAgency" index="/dashboard">
          <el-icon><HomeFilled /></el-icon><span>工作台</span>
        </el-menu-item>
        <el-sub-menu v-for="dir in auth.menuTree" :key="dir.id" :index="dir.path || String(dir.id)">
          <template #title>
            <el-icon><component :is="iconOf(dir.icon)" /></el-icon><span>{{ dir.name }}</span>
          </template>
          <el-menu-item v-for="m in dir.children" :key="m.id" :index="m.path">
            {{ m.name }}
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <el-icon v-if="isMobile" class="hamburger" @click="menuOpen = !menuOpen"><Menu /></el-icon>
        <el-input v-if="canUseBureau" v-model="searchQ" :placeholder="isMobile ? '搜索案号/当事人' : '全局搜索：案号/案名/当事人/线索'" clearable
                  class="search-box" @keyup.enter="onSearch">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-tooltip v-if="canUseBureau" content="反馈问题或需求" placement="bottom">
          <el-icon style="cursor: pointer; font-size: 20px; margin-right: 20px" @click="openFeedback"><ChatDotRound /></el-icon>
        </el-tooltip>
        <el-badge v-if="canUseBureau" :value="unread" :hidden="!unread" style="margin-right: 20px">
          <el-icon style="cursor: pointer; font-size: 20px" @click="openMessages"><Bell /></el-icon>
        </el-badge>
        <el-dropdown @command="onCommand">
          <span class="user-name">
            {{ auth.user?.realName || auth.user?.username }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <!-- 强制模式（首次登录/管理员重置后）：不能关、不能点遮罩、不能 Esc，改完自动登出重登 -->
      <el-dialog v-model="pwdVisible" :title="pwdForced ? '首次登录须修改密码（至少8位，含字母和数字）' : '修改密码（至少8位，含字母和数字）'"
                 width="420px" :close-on-click-modal="!pwdForced" :close-on-press-escape="!pwdForced"
                 :show-close="!pwdForced">
        <el-alert v-if="pwdForced" type="warning" :closable="false" class="mb"
                  title="当前口令由管理员设定，出于安全要求须改为本人口令后方可使用系统。" />
        <el-form label-width="80px">
          <el-form-item label="原密码"><el-input v-model="pwdForm.oldPassword" type="password" show-password /></el-form-item>
          <el-form-item label="新密码"><el-input v-model="pwdForm.newPassword" type="password" show-password /></el-form-item>
          <el-form-item label="确认新密码"><el-input v-model="pwdForm.confirm" type="password" show-password /></el-form-item>
        </el-form>
        <template #footer>
          <el-button v-if="!pwdForced" @click="pwdVisible = false">取消</el-button>
          <el-button type="primary" @click="onChangePassword">保存</el-button>
        </template>
      </el-dialog>
      <el-dialog v-model="fbVisible" title="反馈问题或需求（10 秒提交，上下文自动附带）" width="560px"
                 :close-on-click-modal="!fbSaving" :close-on-press-escape="!fbSaving" :show-close="!fbSaving">
        <el-form label-width="70px" :disabled="fbSaving">
          <el-form-item label="类型">
            <el-radio-group v-model="fb.kind">
              <el-radio value="BUG">问题缺陷</el-radio>
              <el-radio value="FEATURE">功能需求</el-radio>
              <el-radio value="QUESTION">使用咨询</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="标题"><el-input v-model="fb.title" maxlength="100" /></el-form-item>
          <el-form-item label="描述">
            <el-input v-model="fb.content" type="textarea" :rows="4"
                      :placeholder="fb.kind === 'BUG' ? '做了什么操作、预期与实际结果' : '希望增加/改进什么，解决什么场景'" />
          </el-form-item>
          <el-form-item label="截图">
            <div class="paste-box" tabindex="0" @paste="onPasteShot">
              <img v-if="fb.screenshotBase64" :src="fb.screenshotBase64" style="max-width: 100%; max-height: 120px" />
              <span v-else class="hint">点击此处后 Ctrl+V 粘贴截图（可选）</span>
            </div>
          </el-form-item>
        </el-form>
        <div class="hint">
          自动附带：页面 {{ $route.path }} · 版本 {{ appVersion }} · 最近错误 {{ recentErrors.length }} 条
          <span v-if="recentErrors.length">（含 request-id，开发可直接定位服务端日志）</span>
        </div>
        <template #footer>
          <el-button text :disabled="fbSaving" @click="fbVisible = false; router.push('/my/feedback')">我的反馈</el-button>
          <el-button :disabled="fbSaving" @click="fbVisible = false">取消</el-button>
          <el-button type="primary" :loading="fbSaving" @click="onSubmitFeedback">提交</el-button>
        </template>
      </el-dialog>

      <el-drawer v-model="msgVisible" title="站内消息" size="420px">
        <div class="mb" style="text-align: right">
          <el-button size="small" @click="onReadAll">全部已读</el-button>
        </div>
        <div v-for="m in messages" :key="m.id" class="msg" :class="{ unreadMsg: !m.read_at }"
             @click="onOpenMessage(m)">
          <div><el-tag size="small" :type="m.kind === 'DEADLINE' ? 'danger' : 'warning'">
            {{ m.kind === 'DEADLINE' ? '期限' : m.kind === 'APPROVAL' ? '审批' : '系统' }}</el-tag>
            <b style="margin-left: 6px">{{ m.title }}</b></div>
          <div class="hint">{{ m.content || '' }}　{{ formatLocalDateTime(m.created_at) }}</div>
        </div>
        <p v-if="!messages.length" class="hint">暂无消息</p>
      </el-drawer>

      <el-dialog v-model="searchVisible" title="搜索结果" width="640px">
        <h4>案件（{{ searchResult.cases?.length || 0 }}）</h4>
        <el-table :data="searchResult.cases" size="small" border class="mb"
                  @row-click="(r: any) => { searchVisible = false; router.push(`/case/detail/${r.id}`) }">
          <el-table-column prop="case_no" label="案号" width="170" />
          <el-table-column prop="name" label="案件名称" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="120" />
        </el-table>
        <h4>线索（{{ searchResult.clues?.length || 0 }}）</h4>
        <el-table :data="searchResult.clues" size="small" border
                  @row-click="() => { searchVisible = false; router.push('/case/clues') }">
          <el-table-column prop="clue_no" label="线索号" width="130" />
          <el-table-column prop="suspect_name" label="嫌疑人" />
          <el-table-column prop="status" label="状态" width="120" />
        </el-table>
      </el-dialog>
      <el-main class="main">
        <router-view v-if="auth.user && !auth.user.mustChangePassword && (!isAgency || $route.path === '/agency/agreements')" />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import client, { recentErrors } from '../api/client'
import { formatLocalDateTime } from '../api/dates'
import { businessReadAccess } from '../api/caseWriteAccess'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'
import { iconOf } from '../icons'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const appVersion = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : 'dev'

const router = useRouter()
const auth = useAuthStore()
const isAgency = computed(() => !!auth.user?.roles.includes('AGENCY'))
const canUseBureau = computed(() => !isAgency.value && businessReadAccess(auth.user?.roles))
watch(() => [isAgency.value, route.path], () => {
  if (isAgency.value && route.path !== '/agency/agreements') void router.replace('/agency/agreements')
}, { immediate: true })
const pwdVisible = ref(false)
const pwdForced = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '' })
// 用户信息就绪后若标记须改密，立即弹出强制改密框
watch(() => auth.user?.mustChangePassword, (v) => {
  if (v) {
    pwdForm.oldPassword = ''; pwdForm.newPassword = ''; pwdForm.confirm = ''
    pwdForced.value = true
    pwdVisible.value = true
  }
}, { immediate: true })
const searchQ = ref('')
const searchVisible = ref(false)
const searchResult = ref<any>({})
let searchSequence = 0
function searchIdentity() {
  return JSON.stringify([auth.user?.username, auth.user?.mustChangePassword, [...(auth.user?.roles || [])].sort()])
}
function clearSearch() {
  searchSequence++
  searchResult.value = {}
  searchVisible.value = false
}
watch(searchQ, clearSearch, { flush: 'sync' })
watch(() => [auth.token, auth.user, searchIdentity()], clearSearch, { flush: 'sync' })
watch(searchVisible, visible => {
  if (!visible) {
    searchSequence++
    searchResult.value = {}
  }
}, { flush: 'sync' })
const unread = ref(0)
const msgVisible = ref(false)
const messages = ref<any[]>([])
let messageLoadSeq = 0, messageActionSeq = 0, messageGeneration = 0
function messageIdentity() {
  return JSON.stringify([auth.user?.id, auth.user?.username, auth.user?.mustChangePassword, [...(auth.user?.roles || [])].sort()])
}
function clearMessages() {
  messageGeneration++; messageLoadSeq++; messageActionSeq++
  messages.value = []
  unread.value = 0
  msgVisible.value = false
}
watch(() => [auth.token, auth.user, messageIdentity()], clearMessages, { flush: 'sync' })
watch(msgVisible, visible => {
  if (!visible) {
    messageGeneration++; messageLoadSeq++; messageActionSeq++
    messages.value = []
  }
}, { flush: 'sync' })

// Deliberate actions still reach the request client after expiration so it can
// redirect to login. Only their callbacks may apply data in the captured session.
function messageContext() {
  const token = localStorage.getItem('bureau_token') || ''
  if (layoutDisposed || !auth.user || auth.user.mustChangePassword || !canUseBureau.value
      || !token || token !== auth.token) return null
  const stamp = sessionExpiry.capture(token), identity = messageIdentity(), generation = messageGeneration
  return () => {
    const currentToken = localStorage.getItem('bureau_token') || ''
    return !layoutDisposed && generation === messageGeneration && identity === messageIdentity()
      && token === auth.token && token === currentToken && canUseBureau.value && !auth.user?.mustChangePassword
      && sessionExpiry.isCurrent(stamp, currentToken) && !sessionExpiry.isExpired(stamp, currentToken)
  }
}

// 手机端（≤768px）：侧边栏收进抽屉，汉堡按钮开合
const isMobile = ref(window.innerWidth <= 768)
const menuOpen = ref(false)
const onResize = () => {
  isMobile.value = window.innerWidth <= 768
  if (!isMobile.value) menuOpen.value = false  // 转回大屏时确保抽屉态复位
}
function onMenuSelect() {
  if (isMobile.value) menuOpen.value = false  // 手机上点菜单跳转后自动收起
}

let unreadTimer: number | undefined
let expiryTimer: number | undefined
let unreadGeneration = 0, unreadBusy = false, layoutMounted = false, layoutDisposed = false

function stopSessionTimers() {
  unreadGeneration++
  unreadBusy = false
  if (unreadTimer !== undefined) window.clearInterval(unreadTimer)
  if (expiryTimer !== undefined) window.clearTimeout(expiryTimer)
  unreadTimer = undefined; expiryTimer = undefined
}

function startSessionTimers() {
  stopSessionTimers()
  if (!layoutMounted || layoutDisposed || !auth.user) return
  const token = localStorage.getItem('bureau_token') || ''
  if (!token || token !== auth.token) return
  const stamp = sessionExpiry.capture(token)
  if (sessionExpiry.isExpired(stamp, token)) return
  if (!auth.user.mustChangePassword && canUseBureau.value) {
    refreshUnread()
    unreadTimer = window.setInterval(refreshUnread, 60000)
  }
  // Keep the existing pre-expiry reminder bound to this sign-in, and dispose it
  // together with polling. A delayed callback cannot warn a different account.
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    const msLeft = Number(payload.exp) * 1000 - Date.now() - 10 * 60 * 1000
    if (Number.isFinite(msLeft) && msLeft > 0 && msLeft <= 2147483647) {
      expiryTimer = window.setTimeout(() => {
        const currentToken = localStorage.getItem('bureau_token') || ''
        if (!layoutDisposed && sessionExpiry.isCurrent(stamp, currentToken)
            && !sessionExpiry.isExpired(stamp, currentToken))
          ElMessage.warning('登录将在 10 分钟后过期，请及时保存正在填写的内容')
      }, msLeft)
    }
  } catch { /* 令牌格式异常时不提示，实际授权仍由服务端校验 */ }
}

watch(() => [auth.token, messageIdentity(), canUseBureau.value], startSessionTimers, { flush: 'sync' })
const onSessionExpired = () => { stopSessionTimers(); clearSearch(); clearMessages(); invalidateFeedback() }

async function refreshUnread() {
  if (!auth.user || auth.user.mustChangePassword || !canUseBureau.value) return
  const token = localStorage.getItem('bureau_token') || ''
  if (layoutDisposed || unreadBusy || !token || token !== auth.token) return
  const stamp = sessionExpiry.capture(token)
  if (sessionExpiry.isExpired(stamp, token)) return
  const generation = unreadGeneration, identity = messageIdentity()
  unreadBusy = true
  try {
    const value = (await client.get('/bureau/messages/unread-count', { headers: { 'X-Background': '1' } })).data.data
    const currentToken = localStorage.getItem('bureau_token') || ''
    if (layoutDisposed || generation !== unreadGeneration || identity !== messageIdentity() || token !== auth.token
        || !sessionExpiry.isCurrent(stamp, currentToken) || sessionExpiry.isExpired(stamp, currentToken)) return
    unread.value = value
  } catch { /* 未登录等场景忽略 */ }
  finally { if (generation === unreadGeneration) unreadBusy = false }
}

async function reloadMessages() {
  if (!canUseBureau.value) return false
  const current = messageContext()
  if (!current) return false
  const seq = ++messageLoadSeq
  messages.value = []
  try {
    const data = (await client.get('/bureau/messages')).data.data
    if (seq !== messageLoadSeq || !current()) return false
    messages.value = data
    return true
  } catch {
    // The client reports the error; failed refreshes keep no old scoped rows.
    return false
  }
}
async function openMessages() {
  if (!canUseBureau.value) return
  if (await reloadMessages()) msgVisible.value = true
}

async function onOpenMessage(m: any) {
  if (!canUseBureau.value) return
  const current = messageContext()
  if (!current || !messages.value.includes(m)) return
  const action = ++messageActionSeq
  const selected = () => action === messageActionSeq && current() && messages.value.includes(m)
  if (!m.read_at) {
    try { await client.post(`/bureau/messages/${m.id}/read`) }
    catch { return }
    if (!selected()) return
    m.read_at = new Date().toISOString()
    refreshUnread()
  }
  if (selected() && m.link) {
    msgVisible.value = false
    router.push(m.link)
  }
}

async function onReadAll() {
  if (!canUseBureau.value) return
  const current = messageContext()
  if (!current) return
  const action = ++messageActionSeq
  try { await client.post('/bureau/messages/read-all') }
  catch { return }
  if (action !== messageActionSeq || !current()) return
  await Promise.allSettled([reloadMessages(), refreshUnread()])
}

async function onSearch() {
  if (!canUseBureau.value) return
  clearSearch()
  const query = searchQ.value.trim(), token = localStorage.getItem('bureau_token') || ''
  if (layoutDisposed || auth.user?.mustChangePassword || query.length < 2
      || !token || token !== auth.token) return
  const stamp = sessionExpiry.capture(token), identity = searchIdentity(), sequence = searchSequence
  const current = () => {
    const currentToken = localStorage.getItem('bureau_token') || ''
    return !layoutDisposed && sequence === searchSequence && query === searchQ.value.trim()
      && token === auth.token && token === currentToken && identity === searchIdentity()
      && canUseBureau.value && !auth.user?.mustChangePassword
      && sessionExpiry.isCurrent(stamp, currentToken) && !sessionExpiry.isExpired(stamp, currentToken)
  }
  try {
    const resp = await client.get('/bureau/search', { params: { q: query } })
    if (!current()) return
    searchResult.value = resp.data.data
    searchVisible.value = true
  } catch {
    // The request client reports errors and handles deliberate actions after session expiry.
    if (current()) clearSearch()
  }
}

const fbVisible = ref(false)
const fb = reactive<any>({ kind: 'BUG', title: '', content: '', screenshotBase64: '' })
const fbSaving = ref(false)
let feedbackGeneration = 0, feedbackSubmitSequence = 0, feedbackShotSequence = 0
let feedbackOwner = ''

function feedbackIdentity() {
  return JSON.stringify([auth.user?.id, auth.user?.username, auth.user?.mustChangePassword, [...(auth.user?.roles || [])].sort()])
}
function invalidateFeedback() {
  feedbackGeneration++; feedbackSubmitSequence++; feedbackShotSequence++
  fbSaving.value = false
}
watch(fbVisible, invalidateFeedback, { flush: 'sync' })
watch(() => [auth.token, auth.user, feedbackIdentity()], () => {
  invalidateFeedback()
  fbVisible.value = false
}, { flush: 'sync' })

function feedbackContext() {
  const token = localStorage.getItem('bureau_token') || ''
  if (layoutDisposed || !auth.user || auth.user.mustChangePassword || !canUseBureau.value
      || !token || token !== auth.token) return null
  const stamp = sessionExpiry.capture(token), identity = feedbackIdentity(), generation = feedbackGeneration
  return () => {
    const currentToken = localStorage.getItem('bureau_token') || ''
    return !layoutDisposed && fbVisible.value && generation === feedbackGeneration && identity === feedbackIdentity()
      && token === auth.token && token === currentToken && canUseBureau.value && !auth.user?.mustChangePassword
      && sessionExpiry.isCurrent(stamp, currentToken) && !sessionExpiry.isExpired(stamp, currentToken)
  }
}
function feedbackSnapshot() {
  return JSON.stringify([fb.kind, fb.title, fb.content, fb.screenshotBase64])
}

function openFeedback() {
  if (!canUseBureau.value || !feedbackContext()) return
  const owner = JSON.stringify([auth.user?.id, auth.user?.username])
  // Keep an unsaved draft for its owner on expiry; another account starts empty.
  if (feedbackOwner && feedbackOwner !== owner)
    Object.assign(fb, { kind: 'BUG', title: '', content: '', screenshotBase64: '' })
  feedbackOwner = owner
  fbVisible.value = true
}

function onPasteShot(e: ClipboardEvent) {
  if (!fbVisible.value || fbSaving.value) return
  const current = feedbackContext()
  if (!current || !current()) return
  const item = Array.from(e.clipboardData?.items || []).find((i) => i.type.startsWith('image/'))
  const file = item?.getAsFile()
  if (!file) return
  const sequence = ++feedbackShotSequence
  const reader = new FileReader()
  reader.onload = () => {
    if (sequence === feedbackShotSequence && current() && !fbSaving.value)
      fb.screenshotBase64 = reader.result as string
  }
  reader.readAsDataURL(file)
}

async function onSubmitFeedback() {
  if (!canUseBureau.value || !fbVisible.value || fbSaving.value) return
  const current = feedbackContext()
  if (!current) return
  if (!fb.title.trim() || !fb.content.trim()) {
    ElMessage.warning('标题与描述必填')
    return
  }
  const sequence = ++feedbackSubmitSequence, snapshot = feedbackSnapshot()
  feedbackShotSequence++
  fbSaving.value = true
  const lastErr = recentErrors[recentErrors.length - 1]
  try {
    // A deliberate action after expiry still reaches the client for its login flow.
    await client.post('/bureau/feedback', {
      kind: fb.kind, title: fb.title, content: fb.content,
      pageRoute: route.path,
      caseRef: route.path.startsWith('/case/detail/') ? route.path.split('/').pop() : null,
      appVersion, userAgent: navigator.userAgent.slice(0, 250),
      requestId: lastErr?.requestId || null,
      recentErrors: recentErrors.length ? JSON.stringify(recentErrors) : null,
      screenshotBase64: fb.screenshotBase64 || null,
    })
    if (sequence !== feedbackSubmitSequence || !current() || snapshot !== feedbackSnapshot()) return
    ElMessage.success('已提交，处理进展会通过站内消息通知您')
    fbVisible.value = false
    fb.title = ''
    fb.content = ''
    fb.screenshotBase64 = ''
  } finally {
    if (sequence === feedbackSubmitSequence) fbSaving.value = false
  }
}

const on500 = () => {
  if (canUseBureau.value && !fbSaving.value && !fbVisible.value) { fb.kind = 'BUG'; openFeedback() }
}

onMounted(() => {
  layoutMounted = true
  window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired)
  if (!auth.user) auth.fetchMe().catch(() => {})
  else startSessionTimers()
  // 系统级错误发生时自动弹反馈框（预填 BUG 类型）
  window.addEventListener('ybcase-api-500', on500)
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  layoutDisposed = true
  clearSearch()
  clearMessages()
  invalidateFeedback()
  // 登出/卸载须清理，否则轮询累积并在登出后持续打 401
  stopSessionTimers()
  window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired)
  window.removeEventListener('ybcase-api-500', on500)
  window.removeEventListener('resize', onResize)
})

function onCommand(cmd: string) {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/login')
  } else if (cmd === 'password') {
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirm = ''
    pwdVisible.value = true
  }
}

async function onChangePassword() {
  if (pwdForm.newPassword !== pwdForm.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  await client.post('/auth/change-password', {
    oldPassword: pwdForm.oldPassword,
    newPassword: pwdForm.newPassword,
  })
  ElMessage.success('密码已修改，请重新登录')
  pwdVisible.value = false
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100%; }
.aside { background: #1c2b45; }
.logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  background: #142138;
}
.aside :deep(.el-menu) { border-right: none; }
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border-bottom: 1px solid #eee;
  background: #fff;
}
.search-box { width: 320px; }
.hamburger { font-size: 22px; cursor: pointer; color: #333; flex-shrink: 0; }
.user-name { cursor: pointer; color: #333; display: flex; align-items: center; gap: 4px; }

/* ===== 手机端（≤768px）：侧栏变滑出抽屉 ===== */
@media (max-width: 768px) {
  .aside.aside-drawer {
    position: fixed;
    top: 0;
    left: 0;
    height: 100%;
    z-index: 2000;
    transition: transform 0.25s ease;
    box-shadow: 2px 0 8px rgba(0, 0, 0, 0.25);
  }
  /* 展开/收起由内联 :style 的 transform 驱动（内联样式必定生效，避开 scoped 优先级问题） */
  .backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
    z-index: 1999;
  }
  .search-box { width: auto; flex: 1; min-width: 0; }
  .header { padding: 0 10px; }
  /* 头部图标间距在窄屏收紧 */
  .header :deep(.el-badge), .header :deep(.el-tooltip) { margin-right: 12px !important; }
}
.main { background: #f5f7fa; }
.mb { margin-bottom: 12px; }
h4 { margin: 6px 0; }
.paste-box { width: 100%; min-height: 60px; border: 1px dashed #ccc; border-radius: 4px; padding: 8px; outline: none; }
.paste-box:focus { border-color: #409eff; }
.msg { padding: 8px; border-bottom: 1px solid #eee; cursor: pointer; }
.msg:hover { background: #f5f7fa; }
.unreadMsg { background: #fdf6ec; }
</style>
