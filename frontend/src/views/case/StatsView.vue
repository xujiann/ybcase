<template>
  <div v-loading="loading">
    <el-alert type="info" :closable="false" :title="d.scopeLabel || '按当前账号可见范围统计'" class="mb" />
    <el-alert v-if="overviewResource.failed || publishedResource.failed || reportResource.failed" type="warning" :closable="false" title="部分统计未能加载，请刷新重试；未加载成功的报表暂不能导出。" class="mb" />
    <el-button class="mb" :loading="loading" @click="loadStatistics">刷新统计</el-button>
    <el-card v-if="canExportPenalties" class="mb">
      <h4>行政处罚情况统计表</h4>
      <p>内部核对版按决定年度选取案件，按执行截至日分别核对罚款本金、加处罚款和退回基金。</p>
      <el-input-number v-model="penaltyYear" :min="1900" :max="reportYear" :precision="0" aria-label="处罚统计年度" />
      <el-date-picker v-model="penaltyStart" type="date" value-format="YYYY-MM-DD" placeholder="决定起始日（选填）" />
      <el-date-picker v-model="penaltyEnd" type="date" value-format="YYYY-MM-DD" placeholder="决定结束日（选填）" />
      <el-date-picker v-model="penaltyCutoff" type="date" value-format="YYYY-MM-DD" placeholder="执行截至日（默认今天）" />
      <el-button :loading="penaltyExporting" :disabled="!penaltyYear" @click="onAnnualPenaltyExport">导出内部核对版</el-button>
      <el-button :loading="penaltyExporting" @click="onPenaltyExport">导出 2024–2026 年原表</el-button>
    </el-card>
    <el-card v-if="canExportPenalties" class="mb">
      <h4>基金监管部门执法人员办理案件情况统计表</h4>
      <p>按统计期间汇总在办和已办结的机构案件，以及尚未转为案件的已完成检查。导出附有内部对账说明。</p>
      <el-date-picker v-model="workStart" type="date" value-format="YYYY-MM-DD" placeholder="统计开始日" />
      <el-date-picker v-model="workEnd" type="date" value-format="YYYY-MM-DD" placeholder="统计截止日" />
      <el-button :loading="penaltyExporting" :disabled="!workStart || !workEnd || workStart > workEnd"
                 @click="onEnforcementWorkExport">导出 Excel</el-button>
    </el-card>
    <el-card v-if="canExportPenalties" class="mb">
      <h4>医保部门约谈定点医药机构台账</h4>
      <p>按实际约谈日期汇总各单位及处室已登记的约谈记录。</p>
      <el-input-number v-model="interviewYear" :min="1900" :max="reportYear" :precision="0" aria-label="约谈统计年度" />
      <el-date-picker v-model="interviewStart" type="date" value-format="YYYY-MM-DD" placeholder="约谈起始日（选填）" />
      <el-date-picker v-model="interviewCutoff" type="date" value-format="YYYY-MM-DD" placeholder="统计截至日（默认今天）" />
      <el-button :loading="penaltyExporting" :disabled="!interviewYear" @click="onInterviewExport">导出 Excel</el-button>
    </el-card>
    <el-row :gutter="12" class="mb">
      <el-col :span="4" v-for="card in cards" :key="card.label">
        <el-card>
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-row :gutter="12">
      <el-col :span="8">
        <el-card class="mb">
          <h4>案件状态分布</h4>
          <el-table :data="d.caseByStatus" size="small" border>
            <el-table-column label="状态">
              <template #default="{ row }">{{ CASE_STATUS[row.status] || row.status }}</template>
            </el-table-column>
            <el-table-column prop="cnt" label="件数" width="80" align="right" />
          </el-table>
        </el-card>
        <el-card>
          <h4>当事人类别分布</h4>
          <el-table :data="d.byPartyType" size="small" border>
            <el-table-column label="类别">
              <template #default="{ row }">{{ PARTY_TYPE[row.party_type] || row.party_type }}</template>
            </el-table-column>
            <el-table-column prop="cnt" label="件数" width="80" align="right" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card class="mb">
          <h4>处罚决定公示（脱敏导出）
            <el-button size="small" :disabled="!publishedResource.ready" style="float: right" @click="onExport">导出CSV</el-button>
          </h4>
          <el-table :data="published" size="small" border max-height="260">
            <el-table-column prop="party_name" label="当事人" width="130" show-overflow-tooltip />
            <el-table-column prop="decision_no" label="决定书文号" show-overflow-tooltip />
            <el-table-column prop="fine_amount" label="罚款" width="90" align="right" />
          </el-table>
        </el-card>
        <el-card>
          <h4>高频案由 TOP10</h4>
          <el-table :data="d.byCause" size="small" border>
            <el-table-column prop="category" label="案由" show-overflow-tooltip />
            <el-table-column prop="cnt" label="件数" width="80" align="right" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card class="mb">
          <h4>统计上报（{{ reportYear }} 年月报）
            <el-button size="small" :disabled="!reportResource.ready" style="float: right" @click="onReportExport">导出CSV</el-button>
          </h4>
          <el-table :data="report.monthly" size="small" border max-height="300">
            <el-table-column prop="month" label="月" width="55" />
            <el-table-column prop="filed" label="立案" width="60" align="right" />
            <el-table-column prop="closed" label="结案" width="60" align="right" />
            <el-table-column prop="fine_decided" label="决定罚款" align="right" />
            <el-table-column prop="collected" label="收缴" align="right" />
          </el-table>
        </el-card>
        <el-card>
          <h4>近12个月立案趋势</h4>
          <el-table :data="d.monthlyFiled" size="small" border>
            <el-table-column prop="ym" label="月份" width="100" />
            <el-table-column prop="cnt" label="立案数" align="right" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { createResourceLoader, resourceState } from '../../api/loadableResource'
import { CASE_STATUS, PARTY_TYPE } from './labels'
import { useAuthStore } from '../../stores/auth'
import { sessionExpiry } from '../../api/sessionExpiry'
import { localDate } from '../../api/dates'

const auth = useAuthStore()
const canExportPenalties = computed(() => auth.user?.accountCategory === 'BUREAU'
  && auth.user?.roles.some(role => ['LEADER', 'LEGAL', 'ADMIN'].includes(role)))
const penaltyExporting = ref(false)
const workEnd = ref(localDate())
const workStart = ref(workEnd.value.slice(0, 4) + '-01-01')
const penaltyDownload = new AbortController()
onBeforeUnmount(() => penaltyDownload.abort())

const overviewResource = reactive(resourceState<any>({}))
const publishedResource = reactive(resourceState<any[]>([]))
const reportResource = reactive(resourceState<any>({ monthly: [] }))
const d = computed(() => overviewResource.data)
const published = computed(() => publishedResource.data)
const report = computed(() => reportResource.data)
const reportYear = new Date().getFullYear()
const penaltyYear = ref<number | undefined>(reportYear)
const penaltyStart = ref<string>()
const penaltyEnd = ref<string>()
const penaltyCutoff = ref<string>()
const interviewYear = ref<number | undefined>(reportYear)
const interviewStart = ref<string>()
const interviewCutoff = ref<string>()
const overviewLoader = createResourceLoader(overviewResource, async () => (await client.get('/bureau/stats/overview')).data.data)
const publishedLoader = createResourceLoader(publishedResource, async () => (await client.get('/bureau/decisions/publish-export')).data.data)
const reportLoader = createResourceLoader(reportResource, async () => (await client.get('/bureau/stats/report', { params: { year: reportYear } })).data.data)
const loading = computed(() => overviewResource.loading || publishedResource.loading || reportResource.loading)
async function loadStatistics() {
  await Promise.allSettled([overviewLoader.load(), publishedLoader.load(), reportLoader.load()])
}

/** CSV 字段转义：逗号/引号防错列；=+-@ 开头前置单引号防 Excel 公式注入（对外报送件） */
function q(v: any) {
  let t = String(v ?? '')
  if (/^[=+\-@\t\r]/.test(t)) t = "'" + t
  return /[",\r\n]/.test(t) ? '"' + t.replace(/"/g, '""') + '"' : t
}

function download(blob: Blob, name: string) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  URL.revokeObjectURL(a.href)
}

function onPenaltyExport() {
  return downloadWorkbook('/bureau/stats/penalties/download', '2024-2026年行政处罚情况统计表.xlsx')
}

function onAnnualPenaltyExport() {
  const year = penaltyYear.value
  if (!year) return
  const params = annualReportParams(year, penaltyStart.value, penaltyCutoff.value)
  if (penaltyEnd.value) params.end = penaltyEnd.value
  return downloadWorkbook('/bureau/stats/penalties/download', `${year}年行政处罚情况统计表（内部核对）.xlsx`, params)
}

function annualReportParams(year: number, start?: string, cutoff?: string) {
  const params: Record<string, string> = { year: String(year) }
  if (start) params.start = start
  if (cutoff) params.cutoff = cutoff
  return params
}

function onEnforcementWorkExport() {
  if (!workStart.value || !workEnd.value || workStart.value > workEnd.value) return
  return downloadWorkbook('/bureau/stats/enforcement-work/download', '基金监管部门执法人员办理案件情况统计表.xlsx',
    { start: workStart.value, end: workEnd.value, reconcile: 'true' })
}

function onInterviewExport() {
  const year = interviewYear.value
  if (!year) return
  return downloadWorkbook('/bureau/stats/interviews/download', `${year}年医保部门约谈定点医药机构台账.xlsx`,
    { ...annualReportParams(year, interviewStart.value, interviewCutoff.value), reconcile: 'true' })
}

async function downloadWorkbook(path: string, filename: string, params?: Record<string, string>) {
  if (penaltyExporting.value || !canExportPenalties.value) return
  const token = auth.token
  const stamp = sessionExpiry.capture(token)
  const signal = penaltyDownload.signal
  penaltyExporting.value = true
  try {
    const response = await client.get<Blob>(path, { responseType: 'blob', signal, params, timeout: 120_000 })
    if (signal.aborted || !canExportPenalties.value || !sessionExpiry.isCurrent(stamp, auth.token)
      || sessionExpiry.isExpired(stamp, auth.token)) return
    if (response.headers['content-type'] !== 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet') {
      ElMessage.error('未收到有效的 Excel 报表')
      return
    }
    download(response.data, filename)
  } catch {
    // The shared client displays request and business errors.
  } finally {
    penaltyExporting.value = false
  }
}

function onReportExport() {
  if (!reportResource.ready) return
  const t = report.value.totals || {}
  const header = '月份,立案数,结案数,决定罚款,决定追回,实际收缴'
  const lines = (report.value.monthly as any[]).map((r) =>
    [r.month, r.filed, r.closed, r.fine_decided, r.recoup_decided, r.collected].map(q).join(','))
  lines.push(`合计,${t.filed_total},${t.closed_total},,,`)
  lines.push(`线索${t.clue_total}件；听证${t.hearing_total}次；移送${t.transfer_total}件；检查${t.inspection_total}次；举报奖励发放${t.reward_paid}元`)
  download(new Blob(['﻿' + [`数据范围,${q(report.value.scopeLabel || '当前账号可见范围')}`, header, ...lines].join('\n')], { type: 'text/csv;charset=utf-8' }),
    `医保执法统计上报-${reportYear}.csv`)
}

function onExport() {
  if (!publishedResource.ready) return
  const header = '当事人,案由,决定书文号,罚款,追回基金,决定日期,公开日期'
  const lines = published.value.map((r) =>
    [r.party_name, r.cause, r.decision_no, r.fine_amount, r.recoup_amount, r.decided_at, r.published_at].map(q).join(','))
  download(new Blob(['﻿' + [`数据范围,${q(d.value.scopeLabel || '当前账号可见范围')}`, header, ...lines].join('\n')], { type: 'text/csv;charset=utf-8' }),
    '处罚决定公示.csv')
}

const cards = computed(() => [
  { label: '线索总数', value: d.value.clueTotal ?? 0, color: '#16417c' },
  { label: '核查中线索', value: d.value.cluePending ?? 0, color: '#b7791f' },
  { label: '案件总数', value: d.value.caseTotal ?? 0, color: '#16417c' },
  { label: '决定罚款(元)', value: d.value.fineDecided ?? 0, color: '#c0392b' },
  { label: '决定追回基金(元)', value: d.value.recoupDecided ?? 0, color: '#c0392b' },
  { label: '已收缴/追回(元)', value: Number(d.value.fineCollected ?? 0) + Number(d.value.recoupCollected ?? 0), color: '#1d7a5f' },
])

onMounted(loadStatistics)
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.stat-label { font-size: 12px; color: #999; }
.stat-value { font-size: 26px; font-weight: 600; margin-top: 4px; }
h4 { margin: 0 0 8px; }
</style>
