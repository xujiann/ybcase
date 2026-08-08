<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">案件查办系统</div>
      <el-menu router :default-active="$route.path" background-color="#1c2b45" text-color="#c7d0dc" active-text-color="#409eff">
        <el-menu-item index="/dashboard">
          <el-icon><HomeFilled /></el-icon><span>工作台</span>
        </el-menu-item>
        <el-sub-menu v-for="dir in auth.menuTree" :key="dir.id" :index="dir.path || String(dir.id)">
          <template #title>
            <el-icon><component :is="dir.icon || 'Menu'" /></el-icon><span>{{ dir.name }}</span>
          </template>
          <el-menu-item v-for="m in dir.children" :key="m.id" :index="m.path">
            {{ m.name }}
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <el-input v-model="searchQ" placeholder="全局搜索：案号/案名/当事人/线索" clearable
                  style="width: 320px" @keyup.enter="onSearch">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-tooltip content="反馈问题或需求" placement="bottom">
          <el-icon style="cursor: pointer; font-size: 20px; margin-right: 20px" @click="openFeedback"><ChatDotRound /></el-icon>
        </el-tooltip>
        <el-badge :value="unread" :hidden="!unread" style="margin-right: 20px">
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
      <el-dialog v-model="pwdVisible" title="修改密码（至少8位，含字母和数字）" width="420px">
        <el-form label-width="80px">
          <el-form-item label="原密码"><el-input v-model="pwdForm.oldPassword" type="password" show-password /></el-form-item>
          <el-form-item label="新密码"><el-input v-model="pwdForm.newPassword" type="password" show-password /></el-form-item>
          <el-form-item label="确认新密码"><el-input v-model="pwdForm.confirm" type="password" show-password /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="pwdVisible = false">取消</el-button>
          <el-button type="primary" @click="onChangePassword">保存</el-button>
        </template>
      </el-dialog>
      <el-dialog v-model="fbVisible" title="反馈问题或需求（10 秒提交，上下文自动附带）" width="560px">
        <el-form label-width="70px">
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
          <el-button text @click="fbVisible = false; router.push('/my/feedback')">我的反馈</el-button>
          <el-button @click="fbVisible = false">取消</el-button>
          <el-button type="primary" @click="onSubmitFeedback">提交</el-button>
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
          <div class="hint">{{ m.content || '' }}　{{ m.created_at?.slice(0, 16).replace('T', ' ') }}</div>
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
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import client, { recentErrors } from '../api/client'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const appVersion = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : 'dev'

const router = useRouter()
const auth = useAuthStore()
const pwdVisible = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const searchQ = ref('')
const searchVisible = ref(false)
const searchResult = ref<any>({})
const unread = ref(0)
const msgVisible = ref(false)
const messages = ref<any[]>([])

async function refreshUnread() {
  try {
    unread.value = (await client.get('/bureau/messages/unread-count')).data.data
  } catch { /* 未登录等场景忽略 */ }
}

async function openMessages() {
  messages.value = (await client.get('/bureau/messages')).data.data
  msgVisible.value = true
}

async function onOpenMessage(m: any) {
  if (!m.read_at) {
    await client.post(`/bureau/messages/${m.id}/read`)
    m.read_at = new Date().toISOString()
    refreshUnread()
  }
  if (m.link) {
    msgVisible.value = false
    router.push(m.link)
  }
}

async function onReadAll() {
  await client.post('/bureau/messages/read-all')
  messages.value.forEach((m) => { if (!m.read_at) m.read_at = new Date().toISOString() })
  refreshUnread()
}

async function onSearch() {
  if (searchQ.value.trim().length < 2) return
  const resp = await client.get('/bureau/search', { params: { q: searchQ.value.trim() } })
  searchResult.value = resp.data.data
  searchVisible.value = true
}

const fbVisible = ref(false)
const fb = reactive<any>({ kind: 'BUG', title: '', content: '', screenshotBase64: '' })

function openFeedback() {
  fbVisible.value = true
}

function onPasteShot(e: ClipboardEvent) {
  const item = Array.from(e.clipboardData?.items || []).find((i) => i.type.startsWith('image/'))
  if (!item) return
  const reader = new FileReader()
  reader.onload = () => { fb.screenshotBase64 = reader.result as string }
  reader.readAsDataURL(item.getAsFile()!)
}

async function onSubmitFeedback() {
  if (!fb.title.trim() || !fb.content.trim()) {
    ElMessage.warning('标题与描述必填')
    return
  }
  const lastErr = recentErrors[recentErrors.length - 1]
  await client.post('/bureau/feedback', {
    kind: fb.kind, title: fb.title, content: fb.content,
    pageRoute: route.path,
    caseRef: route.path.startsWith('/case/detail/') ? route.path.split('/').pop() : null,
    appVersion, userAgent: navigator.userAgent.slice(0, 250),
    requestId: lastErr?.requestId || null,
    recentErrors: recentErrors.length ? JSON.stringify(recentErrors) : null,
    screenshotBase64: fb.screenshotBase64 || null,
  })
  ElMessage.success('已提交，处理进展会通过站内消息通知您')
  fbVisible.value = false
  fb.title = ''
  fb.content = ''
  fb.screenshotBase64 = ''
}

let unreadTimer: number | undefined
const on500 = () => { fb.kind = 'BUG'; fbVisible.value = true }

onMounted(() => {
  if (!auth.user) auth.fetchMe().catch(() => {})
  refreshUnread()
  unreadTimer = window.setInterval(refreshUnread, 60000)  // 轻量轮询未读数
  // 系统级错误发生时自动弹反馈框（预填 BUG 类型）
  window.addEventListener('ybcase-api-500', on500)
})

onUnmounted(() => {
  // 登出/卸载须清理，否则轮询累积并在登出后持续打 401
  if (unreadTimer) window.clearInterval(unreadTimer)
  window.removeEventListener('ybcase-api-500', on500)
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
  border-bottom: 1px solid #eee;
  background: #fff;
}
.user-name { cursor: pointer; color: #333; display: flex; align-items: center; gap: 4px; }
.main { background: #f5f7fa; }
.mb { margin-bottom: 12px; }
h4 { margin: 6px 0; }
.paste-box { width: 100%; min-height: 60px; border: 1px dashed #ccc; border-radius: 4px; padding: 8px; outline: none; }
.paste-box:focus { border-color: #409eff; }
.msg { padding: 8px; border-bottom: 1px solid #eee; cursor: pointer; }
.msg:hover { background: #f5f7fa; }
.unreadMsg { background: #fdf6ec; }
</style>
