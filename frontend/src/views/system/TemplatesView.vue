<template>
  <el-card>
    <div class="toolbar">
      <h3>文书模板<span class="hint">（要素+模板分离，改模板不改代码；每次修改留历史可回看）</span></h3>
    </div>
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="doc_type" label="类型" width="150" />
      <el-table-column prop="title_tpl" label="标题模板" show-overflow-tooltip />
      <el-table-column prop="updated_at" label="更新时间" width="170" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" text @click="openHistory(row)">历史</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editVisible" :title="`编辑模板：${form.docType}`" width="700px">
      <el-form label-width="90px">
        <el-form-item label="标题模板"><el-input v-model="form.titleTpl" /></el-form-item>
        <el-form-item label="正文模板">
          <el-input v-model="form.contentTpl" type="textarea" :rows="16" style="font-family: monospace" />
        </el-form-item>
      </el-form>
      <p class="hint">可用占位符：caseNo caseName partyName partyAddress partyCreditNo partyLegalRep partyTypeText summary amountInvolved causeText basisText orgName today statementDays hearingDays proposedFine proposedRecoup decisionNo decisionContent</p>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存（旧版自动入历史）</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="historyVisible" :title="`模板历史：${historyType}`" width="700px">
      <el-collapse>
        <el-collapse-item v-for="h in history" :key="h.id" :title="`${h.saved_at}　${h.saved_by || ''}`">
          <b>{{ h.title_tpl }}</b>
          <pre class="tpl">{{ h.content_tpl }}</pre>
        </el-collapse-item>
      </el-collapse>
      <p v-if="!history.length" class="hint">暂无修改历史</p>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const rows = ref<any[]>([])
const history = ref<any[]>([])
const loading = ref(false)
const editVisible = ref(false)
const historyVisible = ref(false)
const historyType = ref('')
const form = reactive({ docType: '', titleTpl: '', contentTpl: '' })

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/bureau/doc-templates')).data.data
  } finally {
    loading.value = false
  }
}

function openEdit(row: any) {
  form.docType = row.doc_type
  form.titleTpl = row.title_tpl
  form.contentTpl = row.content_tpl
  editVisible.value = true
}

async function onSave() {
  await client.put(`/bureau/doc-templates/${form.docType}`, { titleTpl: form.titleTpl, contentTpl: form.contentTpl })
  ElMessage.success('已保存并归档旧版')
  editVisible.value = false
  load()
}

async function openHistory(row: any) {
  historyType.value = row.doc_type
  history.value = (await client.get(`/bureau/doc-templates/${row.doc_type}/history`)).data.data
  historyVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
.tpl { white-space: pre-wrap; background: #f8f9fb; padding: 8px; font-size: 12px; }
</style>
