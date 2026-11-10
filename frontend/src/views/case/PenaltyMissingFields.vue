<template>
  <el-button :disabled="!params" @click="open">核对报表缺项</el-button>
  <el-dialog v-model="visible" title="行政处罚报表缺项核对" width="860px" style="max-width: 95vw">
    <p>按处罚决定日期筛选。请查阅原案卷核实缺项，历史资料缺失时可在报表中留空。</p>
    <p v-if="result">决定日期 {{ result.start }} 至 {{ result.end }}，执行截至 {{ result.cutoff }}</p>
    <el-alert v-if="failed" type="error" :closable="false" title="缺项清单加载失败，请刷新重试。" />
    <el-table v-loading="loading" :data="loading ? [] : result?.rows || []" border max-height="420"
              :empty-text="failed ? '暂未获取核对结果' : '当前区间未发现需核查的缺项'">
      <el-table-column prop="caseNo" label="案号" min-width="160" show-overflow-tooltip />
      <el-table-column prop="partyName" label="当事人" min-width="145" show-overflow-tooltip />
      <el-table-column prop="decidedAt" label="决定日期" width="114" />
      <el-table-column label="缺项" min-width="250">
        <template #default="{ row }">{{ row.missingFields.join('、') }}</template>
      </el-table-column>
      <el-table-column label="办理入口" width="130">
        <template #default="{ row }"><RouterLink :to="{path: `/case/detail/${row.caseId}`, hash: '#report-supplement'}">打开案卷核对</RouterLink></template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="result" class="pagination" :current-page="page" :page-size="pageSize" :total="result.total"
                   :disabled="loading" layout="total, prev, pager, next" @current-change="changePage" />
    <template #footer>
      <el-button :loading="loading" @click="load(page)">刷新清单</el-button>
      <el-button @click="close">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import client, { type R } from '../../api/client'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../../api/sessionExpiry'
import { useAuthStore } from '../../stores/auth'

interface MissingPenalty {
  caseId: number
  caseNo: string
  partyName: string
  decidedAt: string
  missingFields: string[]
}

interface MissingPenalties {
  rows: MissingPenalty[]
  total: number
  start: string
  end: string
  cutoff: string
}

const props = defineProps<{ params?: Record<string, string> }>()
const auth = useAuthStore()
const identity = computed(() => [auth.token, auth.user?.id, auth.user?.accountCategory, ...(auth.user?.roles ?? [])])
const visible = ref(false)
const loading = ref(false)
const failed = ref(false)
const result = ref<MissingPenalties>()
const page = ref(1)
const pageSize = 30
let generation = 0

function open() {
  if (!props.params || !auth.token || visible.value) return
  visible.value = true
  return load(1)
}

function close() {
  visible.value = false
}

async function load(next: number) {
  const params = props.params
  if (!visible.value || !params || !auth.token) return
  const run = ++generation
  const stamp = sessionExpiry.capture(auth.token)
  const isCurrent = () => run === generation && visible.value && props.params === params
    && sessionExpiry.isCurrent(stamp, auth.token) && !sessionExpiry.isExpired(stamp, auth.token)
  page.value = next
  loading.value = true
  failed.value = false
  try {
    const { data } = await client.get<R<MissingPenalties>>('/bureau/stats/penalties/missing-fields',
      { params: { ...params, page: next, size: pageSize } })
    if (!isCurrent()) return
    const last = Math.max(1, Math.ceil(data.data.total / pageSize))
    if (next > last) return await load(last)
    result.value = data.data
  } catch {
    if (isCurrent()) {
      result.value = undefined
      failed.value = true
    }
  } finally {
    if (run === generation) {
      loading.value = false
      if (!isCurrent()) close()
    }
  }
}

function changePage(next: number) {
  if (!loading.value && page.value !== next) void load(next)
}

watch(visible, shown => {
  if (shown) return
  generation++
  result.value = undefined
  page.value = 1
  loading.value = false
  failed.value = false
}, { flush: 'sync' })
watch([identity, () => props.params], close, { flush: 'sync' })
onMounted(() => window.addEventListener(SESSION_EXPIRED_EVENT, close))
onBeforeUnmount(() => {
  close()
  window.removeEventListener(SESSION_EXPIRED_EVENT, close)
})
</script>

<style scoped>
.pagination { margin-top: 12px; }
</style>
