<template>
  <el-card>
    <div class="toolbar">
      <h3>法定节假日/调休表<span class="hint">（期限计算依据，第58条；每年按国办通知维护）</span></h3>
      <div>
        <el-select v-model="year" style="width: 110px; margin-right: 8px" @change="load">
          <el-option v-for="y in yearOptions" :key="y" :label="`${y} 年`" :value="y" />
        </el-select>
        <el-date-picker v-model="form.day" type="date" value-format="YYYY-MM-DD" placeholder="日期" style="width: 150px; margin-right: 8px" />
        <el-select v-model="form.kind" style="width: 130px; margin-right: 8px">
          <el-option label="放假" value="HOLIDAY" />
          <el-option label="调休上班" value="SHIFT_WORK" />
        </el-select>
        <el-input v-model="form.name" placeholder="名称" style="width: 120px; margin-right: 8px" />
        <el-button type="primary" @click="onAdd">添加/更新</el-button>
        <el-button @click="batchVisible = true">按年批量录入</el-button>
      </div>
    </div>
    <!-- 覆盖度红条：缺年时所有工作日口径的法定期限静默退化为只跳周末，必须在这里就能看见 -->
    <el-alert v-if="missingYears.length" type="error" :closable="false" class="mb"
              :title="`${missingYears.join('、')} 年尚未录入放假记录：该年度线索核查/法制审核/先行登记保存/送达/简易备案等工作日期限只跳周末、不跳节假日，会算短并导致督办误报。请按国办放假通知批量录入。`" />
    <el-alert v-else type="success" :closable="false" class="mb"
              title="当年节假日表已录入（次年从 11 月起提示）。录入时请同时录入调休上班日（SHIFT_WORK），漏录调休会把期限往后推、漏报超期。" />
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="day" label="日期" width="140" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.kind === 'HOLIDAY' ? 'danger' : 'success'">
            {{ row.kind === 'HOLIDAY' ? '放假' : '调休上班' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button size="small" text type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="batchVisible" title="按年批量录入（每行：日期,类型,名称）" width="560px">
      <el-alert type="info" :closable="false" class="mb"
                title="类型填 放假 或 调休上班（也可填 HOLIDAY / SHIFT_WORK）。落在周末的放假日可不录；调休上班的周六/周日必须录，否则期限会算长。已存在的日期按新值覆盖。" />
      <el-input v-model="batchText" type="textarea" :rows="12"
                placeholder="2027-01-01,放假,元旦
2027-01-02,放假,元旦
2027-01-03,放假,元旦
2027-02-06,调休上班,春节调休" />
      <div v-if="batchError" class="err">{{ batchError }}</div>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSubmitting" @click="onBatch">导入</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const rows = ref<any[]>([])
const loading = ref(false)
const form = reactive({ day: '', kind: 'HOLIDAY', name: '' })
const thisYear = new Date().getFullYear()
const year = ref(thisYear)
const yearOptions = [thisYear - 1, thisYear, thisYear + 1, thisYear + 2]
const missingYears = ref<number[]>([])
const batchVisible = ref(false)
const batchText = ref('')
const batchError = ref('')
const batchSubmitting = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/bureau/holidays', { params: { year: year.value } })).data.data
    missingYears.value = (await client.get('/bureau/holidays/coverage')).data.data.missingYears || []
  } finally {
    loading.value = false
  }
}

async function onAdd() {
  if (!form.day) {
    ElMessage.warning('请选择日期')
    return
  }
  await client.post('/bureau/holidays', form)
  ElMessage.success('已保存')
  year.value = Number(form.day.slice(0, 4))
  load()
}

const KIND_ALIAS: Record<string, string> = {
  '放假': 'HOLIDAY', 'HOLIDAY': 'HOLIDAY', '调休上班': 'SHIFT_WORK', '调休': 'SHIFT_WORK',
  '上班': 'SHIFT_WORK', 'SHIFT_WORK': 'SHIFT_WORK',
}

/** 文本解析在客户端做完整校验，整批合法才提交（服务端同样整批校验，一行错整批拒） */
function parseBatch(text: string): { rows: { day: string; kind: string; name: string }[]; error: string } {
  const out: { day: string; kind: string; name: string }[] = []
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l)
  for (let i = 0; i < lines.length; i++) {
    // 允许中英文逗号、制表符、空格分隔
    const parts = lines[i].split(/[,，\t]+|\s+/).map((p) => p.trim()).filter((p) => p)
    const day = (parts[0] || '').replace(/[./]/g, '-')
    if (!/^\d{4}-\d{1,2}-\d{1,2}$/.test(day)) return { rows: [], error: `第 ${i + 1} 行日期无效：${lines[i]}` }
    const kind = KIND_ALIAS[(parts[1] || '放假').toUpperCase()] || KIND_ALIAS[parts[1] || '放假']
    if (!kind) return { rows: [], error: `第 ${i + 1} 行类型无效（填 放假 / 调休上班）：${lines[i]}` }
    const [y, m, d] = day.split('-')
    out.push({ day: `${y}-${m.padStart(2, '0')}-${d.padStart(2, '0')}`, kind, name: parts.slice(2).join(' ') || '' })
  }
  if (!out.length) return { rows: [], error: '没有可导入的行' }
  return { rows: out, error: '' }
}

async function onBatch() {
  const { rows: parsed, error } = parseBatch(batchText.value)
  batchError.value = error
  if (error) return
  batchSubmitting.value = true
  try {
    const r = (await client.post('/bureau/holidays/batch', parsed)).data.data
    ElMessage.success(`已导入 ${r.count} 条`)
    batchVisible.value = false
    batchText.value = ''
    year.value = Number(parsed[0].day.slice(0, 4))
    load()
  } finally {
    batchSubmitting.value = false
  }
}

async function onDelete(row: any) {
  await client.delete(`/bureau/holidays/${row.day}`)
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; flex-wrap: wrap; gap: 8px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
.mb { margin-bottom: 12px; }
.err { color: #f56c6c; font-size: 12px; margin-top: 6px; }
</style>
