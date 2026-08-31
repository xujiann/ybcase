<template>
  <el-card>
    <div class="toolbar">
      <h3>我的反馈<span class="hint">（处理进展会同步到站内消息；"已解决"请验证后确认关闭）</span></h3>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ KIND[row.kind] }}</template>
      </el-table-column>
      <el-table-column prop="title" label="标题" width="220" show-overflow-tooltip />
      <el-table-column prop="content" label="描述" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="(STATUS_TAG[row.status] as any)">{{ STATUS[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reply" label="处理回复" show-overflow-tooltip />
      <el-table-column label="提交时间" width="140">
        <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-if="row.status === 'RESOLVED'" size="small" type="success"
                     @click="onClose(row)">确认关闭</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { fmtTime } from './labels'
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const KIND: Record<string, string> = { BUG: '问题缺陷', FEATURE: '功能需求', QUESTION: '使用咨询' }
const STATUS: Record<string, string> = { NEW: '待处理', PROCESSING: '处理中', RESOLVED: '已解决', REJECTED: '不采纳', CLOSED: '已关闭' }
const STATUS_TAG: Record<string, string> = { NEW: 'warning', PROCESSING: 'primary', RESOLVED: 'success', REJECTED: 'info', CLOSED: 'info' }
const rows = ref<any[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/bureau/feedback/mine')).data.data
  } finally {
    loading.value = false
  }
}

async function onClose(row: any) {
  await client.post(`/bureau/feedback/${row.id}/close`)
  ElMessage.success('已确认关闭，感谢反馈')
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
