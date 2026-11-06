<template>
  <el-alert type="info" :closable="false" title="人工登记管辖事实与材料，不自动认定法律管辖；暂缺材料可选择待补录。" style="margin-bottom: 12px" />
  <el-form-item label="管辖记录类型">
    <el-select :model-value="modelValue.type" style="width: 100%" @update:model-value="update('type', $event)">
      <el-option v-for="(label, value) in JURISDICTION_TYPES" :key="value" :label="label" :value="value" />
    </el-select>
  </el-form-item>
  <el-form-item label="管辖依据" :required="modelValue.type !== 'PENDING'"><el-input :model-value="modelValue.basis" type="textarea" :rows="3" :maxlength="2000" placeholder="填写管辖所依据的事实、文书或法律规定" @update:model-value="update('basis', $event)" /></el-form-item>
  <el-form-item label="违法发生地"><el-input :model-value="modelValue.violationPlace" :maxlength="255" @update:model-value="update('violationPlace', $event)" /></el-form-item>
  <el-form-item label="就医地"><el-input :model-value="modelValue.treatmentPlace" :maxlength="255" @update:model-value="update('treatmentPlace', $event)" /></el-form-item>
  <el-form-item label="参保地"><el-input :model-value="modelValue.insuredPlace" :maxlength="255" @update:model-value="update('insuredPlace', $event)" /></el-form-item>
  <el-form-item label="指定管辖文号" :required="modelValue.type === 'DESIGNATED'"><el-input :model-value="modelValue.designatedNo" :maxlength="128" @update:model-value="update('designatedNo', $event)" /></el-form-item>
  <el-form-item label="争议/协商记录"><el-input :model-value="modelValue.disputeNote" type="textarea" :rows="2" :maxlength="2000" @update:model-value="update('disputeNote', $event)" /></el-form-item>
</template>
<script setup lang="ts">
import { JURISDICTION_TYPES, type Jurisdiction } from '../api/jurisdiction'
const props = defineProps<{ modelValue: Jurisdiction }>()
const emit = defineEmits<{ 'update:modelValue': [value: Jurisdiction] }>()
function update(key: keyof Jurisdiction, value: string) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
</script>
