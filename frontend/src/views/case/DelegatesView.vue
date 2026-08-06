<template>
  <el-card>
    <div class="toolbar">
      <h3>委托执法档案<span class="hint">（局令第8条：委托书载明事项/权限/期限并向社会公布，不得转委托）</span></h3>
      <el-button type="primary" @click="dlg = true">登记委托</el-button>
    </div>
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="name" label="受托组织" width="200" show-overflow-tooltip />
      <el-table-column prop="agreement_no" label="委托书文号" width="150" />
      <el-table-column prop="scope" label="委托事项与权限" show-overflow-tooltip />
      <el-table-column prop="start_at" label="起" width="105" />
      <el-table-column prop="end_at" label="止" width="105" />
      <el-table-column label="公布" width="120">
        <template #default="{ row }">
          <span v-if="row.published_at">{{ row.published_at }}</span>
          <el-tag v-else type="danger" size="small">未公布</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlg" title="登记委托执法" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="受托组织"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="委托书文号"><el-input v-model="form.agreementNo" /></el-form-item>
        <el-form-item label="事项与权限"><el-input v-model="form.scope" type="textarea" :rows="2" placeholder="行政强制措施权不得委托" /></el-form-item>
        <el-form-item label="委托期限">
          <el-date-picker v-model="form.startAt" type="date" value-format="YYYY-MM-DD" placeholder="起" style="width: 46%" />
          <el-date-picker v-model="form.endAt" type="date" value-format="YYYY-MM-DD" placeholder="止" style="width: 46%; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="公布日期">
          <el-date-picker v-model="form.publishedAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="onCreate">登记</el-button>
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
const dlg = ref(false)
const form = reactive<any>({ name: '', agreementNo: '', scope: '', startAt: '', endAt: '', publishedAt: null })

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/bureau/delegates')).data.data
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  if (!form.name || !form.agreementNo || !form.startAt || !form.endAt) {
    ElMessage.warning('受托组织/文号/期限必填')
    return
  }
  await client.post('/bureau/delegates', form)
  ElMessage.success('已登记')
  dlg.value = false
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
