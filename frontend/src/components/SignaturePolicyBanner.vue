<template>
  <el-alert v-if="notice" :title="notice" :type="formal ? 'info' : 'warning'" :closable="false" class="mb" />
  <el-alert v-else-if="failed" title="运行模式未能加载，请重试核对后再办理签章。" type="error" :closable="false" class="mb" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'
const auth = useAuthStore()
const notice = ref(''), formal = ref(false), failed = ref(false)
let request = 0, alive = true
watch(() => [auth.user?.username, auth.user?.roles?.join('|'), auth.token], async () => {
  const current = ++request
  notice.value = ''; formal.value = false; failed.value = false
  if (!auth.user) return
  try {
    const response = await client.get('/bureau/signatures/policy')
    if (!alive || request !== current) return
    const value = response.data.data
    if (!['SIMULATION', 'FORMAL'].includes(value?.mode) || typeof value?.notice !== 'string') throw new Error('Invalid runtime policy')
    notice.value = value.notice; formal.value = value.formal === true
  } catch {
    if (alive && request === current) failed.value = true
  }
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { alive = false; request++ })
</script>
