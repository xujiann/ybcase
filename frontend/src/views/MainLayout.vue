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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'

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

onMounted(() => {
  if (!auth.user) auth.fetchMe().catch(() => {})
  refreshUnread()
  setInterval(refreshUnread, 60000)  // 轻量轮询未读数
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
.msg { padding: 8px; border-bottom: 1px solid #eee; cursor: pointer; }
.msg:hover { background: #f5f7fa; }
.unreadMsg { background: #fdf6ec; }
</style>
