<template>
  <el-alert v-if="j.type === 'PENDING'" type="warning" :closable="false" title="管辖事实待补录，请核实材料并补录；系统未作自动管辖认定。" style="margin: 8px 0" />
  <el-descriptions :column="2" border size="small">
    <el-descriptions-item label="记录类型">{{ JURISDICTION_TYPES[j.type] || j.type }}</el-descriptions-item>
    <el-descriptions-item label="指定管辖文号">{{ j.designatedNo || '—' }}</el-descriptions-item>
    <el-descriptions-item label="管辖依据" :span="2">{{ j.basis || '—' }}</el-descriptions-item>
    <el-descriptions-item label="违法发生地">{{ j.violationPlace || '—' }}</el-descriptions-item>
    <el-descriptions-item label="就医地">{{ j.treatmentPlace || '—' }}</el-descriptions-item>
    <el-descriptions-item label="参保地">{{ j.insuredPlace || '—' }}</el-descriptions-item>
    <el-descriptions-item label="争议/协商记录" :span="2">{{ j.disputeNote || '—' }}</el-descriptions-item>
  </el-descriptions>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { serializeJurisdiction, JURISDICTION_TYPES, type Jurisdiction } from '../api/jurisdiction'
const props = defineProps<{ value?: Partial<Jurisdiction> | null }>()
const j = computed(() => serializeJurisdiction(props.value))
</script>
