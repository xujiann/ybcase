<template>
  <div v-loading="loading">
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
            <el-button size="small" style="float: right" @click="onExport">导出CSV</el-button>
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
            <el-button size="small" style="float: right" @click="onReportExport">导出CSV</el-button>
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
import { computed, onMounted, ref } from 'vue'
import client from '../../api/client'
import { CASE_STATUS, PARTY_TYPE } from './labels'

const d = ref<any>({})
const published = ref<any[]>([])
const report = ref<any>({ monthly: [] })
const reportYear = new Date().getFullYear()
const loading = ref(false)

/** CSV 字段转义：当事人/案由含逗号或引号会错列 */
function q(v: any) {
  const t = String(v ?? '')
  return /[",\n]/.test(t) ? '"' + t.replace(/"/g, '""') + '"' : t
}

function download(blob: Blob, name: string) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  URL.revokeObjectURL(a.href)
}

function onReportExport() {
  const t = report.value.totals || {}
  const header = '月份,立案数,结案数,决定罚款,决定追回,实际收缴'
  const lines = (report.value.monthly as any[]).map((r) =>
    [r.month, r.filed, r.closed, r.fine_decided, r.recoup_decided, r.collected].map(q).join(','))
  lines.push(`合计,${t.filed_total},${t.closed_total},,,`)
  lines.push(`线索${t.clue_total}件；听证${t.hearing_total}次；移送${t.transfer_total}件；检查${t.inspection_total}次；举报奖励发放${t.reward_paid}元`)
  const blob = new Blob(['﻿' + [header, ...lines].join('\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `医保执法统计上报-${reportYear}.csv`
  a.click()
}

function onExport() {
  const header = '当事人,案由,决定书文号,罚款,追回基金,决定日期,公开日期'
  const lines = published.value.map((r) =>
    [r.party_name, r.cause, r.decision_no, r.fine_amount, r.recoup_amount, r.decided_at, r.published_at].map(q).join(','))
  const blob = new Blob(['﻿' + [header, ...lines].join('\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = '处罚决定公示.csv'
  a.click()
}

const cards = computed(() => [
  { label: '线索总数', value: d.value.clueTotal ?? 0, color: '#16417c' },
  { label: '核查中线索', value: d.value.cluePending ?? 0, color: '#b7791f' },
  { label: '案件总数', value: d.value.caseTotal ?? 0, color: '#16417c' },
  { label: '决定罚款(元)', value: d.value.fineDecided ?? 0, color: '#c0392b' },
  { label: '决定追回基金(元)', value: d.value.recoupDecided ?? 0, color: '#c0392b' },
  { label: '已收缴/追回(元)', value: Number(d.value.fineCollected ?? 0) + Number(d.value.recoupCollected ?? 0), color: '#1d7a5f' },
])

onMounted(async () => {
  loading.value = true
  try {
    const resp = await client.get('/bureau/stats/overview')
    d.value = resp.data.data
    published.value = (await client.get('/bureau/decisions/publish-export')).data.data
    report.value = (await client.get('/bureau/stats/report', { params: { year: reportYear } })).data.data
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.stat-label { font-size: 12px; color: #999; }
.stat-value { font-size: 26px; font-weight: 600; margin-top: 4px; }
h4 { margin: 0 0 8px; }
</style>
