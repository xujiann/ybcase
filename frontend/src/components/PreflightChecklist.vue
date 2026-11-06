<template>
  <div class="preflight">
    <el-alert v-if="result" :type="result.ready ? 'success' : 'warning'" :closable="false"
              :title="result.ready ? '当前办理条件检查通过，提交时将再次核验' : '尚有办理条件未满足，请按下列提示完善'" />
    <p v-else class="hint">填写表单后检查办理条件；修改表单将清空旧结果。正式提交时仍会再次核验。</p>
    <el-table v-if="result" :data="prioritizePreflightItems(result.items)" :max-height="340" border size="small" style="margin-top: 8px">
      <el-table-column label="结果" width="85"><template #default="{ row }"><el-tag :type="row.passed ? 'success' : 'warning'" size="small">{{ row.passed ? '通过' : '未满足' }}</el-tag></template></el-table-column>
      <el-table-column prop="label" label="办理条件" width="140" />
      <el-table-column prop="message" label="核验说明" />
      <el-table-column label="办理岗位" width="110"><template #default="{ row }">{{ roleLabel(row.role) }}</template></el-table-column>
    </el-table>
  </div>
</template>
<script setup lang="ts">
import { prioritizePreflightItems, type PreflightResult } from '../api/preflight'
defineProps<{ result: PreflightResult | null }>()
function roleLabel(role?: string) {
  const labels: Record<string, string> = { ADMIN: '管理员', LEADER: '负责人', LEGAL: '法制审核岗', HANDLER: '办案人员' }
  return role ? role.split(/[|,/]/).map(r => labels[r.trim()] || r.trim()).join(' / ') : '—'
}
</script>
<style scoped>
.preflight { margin-top: 12px; }
.hint { color: #666; font-size: 12px; }
</style>
