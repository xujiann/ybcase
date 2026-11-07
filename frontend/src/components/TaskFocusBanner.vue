<template>
  <el-alert v-if="focus !== undefined" :type="focus === 0 || failed || (!loading && count === 0) ? 'warning' : 'info'"
            :closable="false" class="task-focus-banner">
    <template #title>{{ title }}</template>
    <p>{{ explanation }} <el-button link type="primary" @click="clearFocus">返回完整列表</el-button></p>
  </el-alert>
</template>
<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { parseTaskFocus, withoutTaskFocus } from '../api/taskFocus'

const props = withDefaults(defineProps<{ paramKey?: string; loading?: boolean; failed?: boolean; count?: number; label?: string }>(),
  { paramKey: 'focus', loading: false, failed: false, label: '待办来源' })
const emit = defineEmits<{ change: [value: number | undefined] }>()
const route = useRoute()
const router = useRouter()
const focus = computed(() => parseTaskFocus(route.query[props.paramKey]))
watch(focus, value => emit('change', value), { immediate: true, flush: 'sync' })
const title = computed(() => focus.value === 0 ? '待办链接中的编号无效'
  : props.failed ? '未能读取当前待办来源'
  : props.loading ? '正在定位待办来源'
  : props.count === 0 ? '未找到当前可查看的待办来源'
  : `正在查看${props.label} #${focus.value}`)
const explanation = computed(() => focus.value === 0 ? '请从当前待办重新进入，或返回完整列表。'
  : props.failed ? '该事项可能已失效、超出当前账号权限或读取失败，请刷新当前待办后重试。'
  : props.count === 0 && !props.loading && !props.failed ? '该事项可能已办结、失效或超出当前账号权限，请核对当前待办。'
  : '当前只定位链接指定的来源，按当前账号权限读取，不受其他列表筛选条件影响。')
function clearFocus() { void router.replace({ query: withoutTaskFocus(route.query, props.paramKey) as typeof route.query }) }
</script>
<style scoped>.task-focus-banner{margin-bottom:12px}.task-focus-banner p{margin:4px 0 0}</style>
