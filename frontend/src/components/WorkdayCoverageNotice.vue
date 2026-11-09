<template>
  <el-alert v-if="missingYears.length" type="warning" show-icon :closable="false" class="workday-coverage"
            :title="`${missingYears.join('、')} 年尚无放假记录，涉及该年度的工作日期限可能有偏差，请联系管理员维护日历并核对期限。`" />
  <el-alert v-else-if="failed" type="warning" :closable="false" class="workday-coverage"
            title="暂时无法检查工作日历覆盖情况。" />
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import client from '../api/client'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const missingYears = ref<number[]>([])
const failed = ref(false)
let generation = 0

function clear() {
  generation++
  missingYears.value = []
  failed.value = false
}

async function load() {
  clear()
  const run = generation, token = auth.token, userId = auth.user?.id, stamp = sessionExpiry.capture(token)
  if (!token || !userId || sessionExpiry.isExpired(stamp, token)) return
  const current = () => run === generation && userId === auth.user?.id
    && sessionExpiry.isCurrent(stamp, auth.token) && !sessionExpiry.isExpired(stamp, auth.token)
  try {
    const result = (await client.get<{ data: { missingYears: number[] } }>('/bureau/holidays/coverage',
      { headers: { 'X-Background': '1' } })).data.data
    if (current()) {
      if (!Array.isArray(result?.missingYears)) failed.value = true
      else missingYears.value = result.missingYears
    }
  } catch {
    if (current()) failed.value = true
  }
}

watch(() => [auth.token, auth.user?.id], load, { flush: 'sync' })
onMounted(() => { window.addEventListener(SESSION_EXPIRED_EVENT, clear); load() })
onUnmounted(() => { window.removeEventListener(SESSION_EXPIRED_EVENT, clear); clear() })
</script>

<style scoped>
.workday-coverage { margin-bottom: 12px; }
</style>
