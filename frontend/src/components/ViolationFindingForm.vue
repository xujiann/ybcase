<template>
  <el-form-item label="违法发生开始日" required>
    <el-date-picker :model-value="model.start" value-format="YYYY-MM-DD" @update:model-value="update('start', $event)" />
  </el-form-item>
  <el-form-item label="违法发生结束日" required>
    <el-date-picker :model-value="model.end" value-format="YYYY-MM-DD" @update:model-value="update('end', $event)" />
  </el-form-item>
  <el-form-item label="认定违法事实" required>
    <el-input :model-value="model.facts" type="textarea" :rows="3" maxlength="12000" @update:model-value="update('facts', $event)" />
  </el-form-item>
  <el-form-item label="认定违法金额（元）" required>
    <el-input-number :model-value="model.amount ?? undefined" :min="0" :max="999999999999.99"
                     :precision="2" @update:model-value="update('amount', $event ?? null)" />
  </el-form-item>
</template>

<script setup lang="ts">
import type { ViolationFinding } from '../api/violationFinding'

const model = defineModel<ViolationFinding>({ required: true })
function update<K extends keyof ViolationFinding>(key: K, value: ViolationFinding[K]) {
  model.value = { ...model.value, [key]: value }
}
</script>
