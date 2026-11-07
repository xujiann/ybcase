<template>
  <p>填写已经开展的约谈。机构名称取本事项关联的案件，信息将汇入部门约谈台账。</p>
  <el-form-item label="机构编码">
    <el-input :model-value="modelValue.institutionCode" maxlength="64" placeholder="留空使用案件已登记机构编码"
              @update:model-value="update('institutionCode', $event)" />
  </el-form-item>
  <el-form-item label="约谈类型" required>
    <el-input :model-value="modelValue.interviewType" maxlength="64" @update:model-value="update('interviewType', $event)" />
  </el-form-item>
  <el-form-item label="约谈事由" required>
    <el-input :model-value="modelValue.reason" type="textarea" :rows="2" maxlength="1000"
              @update:model-value="update('reason', $event)" />
  </el-form-item>
  <el-form-item label="约谈内容" required>
    <el-input :model-value="modelValue.content" type="textarea" :rows="3" maxlength="12000"
              @update:model-value="update('content', $event)" />
  </el-form-item>
  <el-form-item label="约谈人员" required>
    <el-input :model-value="modelValue.participants" maxlength="512" @update:model-value="update('participants', $event)" />
  </el-form-item>
  <el-form-item label="约谈日期" required>
    <el-date-picker :model-value="modelValue.interviewDate" value-format="YYYY-MM-DD" :disabled-date="disabledDate"
                    @update:model-value="update('interviewDate', $event)" />
  </el-form-item>
</template>

<script setup lang="ts">
import { type ActualInterview } from '../api/actualInterview'
import { localDate } from '../api/dates'

const props = defineProps<{ modelValue: ActualInterview; sentAt: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: ActualInterview] }>()
function update(key: keyof ActualInterview, value: string | null) {
  emit('update:modelValue', { ...props.modelValue, [key]: value || '' })
}
function disabledDate(date: Date) {
  const day = localDate(date)
  return day < '1900-01-01' || day < props.sentAt || day > localDate()
}
</script>
