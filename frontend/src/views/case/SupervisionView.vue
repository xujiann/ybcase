<template>
  <div v-loading="loading">
    <el-alert type="info" :closable="false" class="mb"
              title="督办看板：按法定时限自动预警——线索核查15个工作日、法制审核10个工作日、办案期限90日（含延长与扣除）、送达7个工作日、先行登记保存7个工作日、封存30日；决定作出后继续跟到执行终了——缴款期、催告、法院强制执行申请期（按登记的法定救济、暂缓期限计算）" />
    <el-alert v-if="d.holidayMissing?.length" type="error" :closable="false" class="mb"
              :title="`${d.holidayMissing.join('、')} 年法定节假日表尚未录入：在录入之前，该年度所有按工作日计算的期限只跳周末、不跳节假日，下方工作日口径的预警可能失真。请管理员在「系统管理 → 节假日表」按国办通知批量录入。`" />
    <el-alert v-if="anyTruncated" type="warning" :closable="false" class="mb"
              :title="`部分类别条目较多，每类仅显示最紧急的前 ${d.topN} 条（按期限升序）。完整清单请到对应业务页按条件筛选。`" />
    <el-row :gutter="12">
      <el-col :span="12">
        <el-card class="mb">
          <h4>线索核查超期 <el-tag type="danger" size="small">{{ d.clueOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.clueOverdue" size="small" border>
            <el-table-column prop="clue_no" label="线索号" width="120" />
            <el-table-column prop="suspect_name" label="嫌疑人" />
            <el-table-column prop="deadline_at" label="核查期限" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>法制审核超期 <el-tag type="danger" size="small">{{ d.reviewOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.reviewOverdue" size="small" border>
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="submitted_at" label="提交日期" width="110" />
            <el-table-column prop="deadline_at" label="审核期限" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>先行登记保存超期未处理 <el-tag type="danger" size="small">{{ d.holdOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.holdOverdue" size="small" border>
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="name" label="证据" />
            <el-table-column prop="hold_expire_at" label="处理期限" width="110" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card class="mb">
          <h4>办案期限临期/超期（10日内） <el-tag type="warning" size="small">{{ d.caseNearDeadline?.length || 0 }}</el-tag></h4>
          <el-table :data="d.caseNearDeadline" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="name" label="案件" show-overflow-tooltip />
            <el-table-column prop="effective_deadline" label="有效期限" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>决定书送达超期 <el-tag type="danger" size="small">{{ d.deliveryOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.deliveryOverdue" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="name" label="案件" show-overflow-tooltip />
            <el-table-column prop="decided_at" label="决定日期" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>封存临期/到期（5日内） <el-tag type="warning" size="small">{{ d.sealExpiring?.length || 0 }}</el-tag></h4>
          <el-table :data="d.sealExpiring" size="small" border>
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="name" label="证据" />
            <el-table-column prop="seal_expire_at" label="封存到期" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>执法证 30 日内到期/已过期（第16条） <el-tag type="warning" size="small">{{ d.enforcerCertExpiring?.length || 0 }}</el-tag></h4>
          <el-table :data="d.enforcerCertExpiring" size="small" border @row-click="() => $router.push('/system/enforcers')">
            <el-table-column prop="name" label="姓名" width="100" />
            <el-table-column prop="cert_no" label="执法证号" width="130" />
            <el-table-column label="有效期至" width="170">
              <template #default="{ row }">
                {{ row.cert_expire_at }}
                <el-tag :type="row.expired ? 'danger' : 'warning'" size="small">{{ row.expired ? '已过期' : '临期' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="active_cases" label="名下在办" width="90" align="right" />
          </el-table>
          <div class="hint">证过期后该人员不能再被列为办案人员（立案/加人被 2055 拦截）；有在办案件的请提前换证或安排交接。</div>
        </el-card>
        <el-card class="mb">
          <h4>协查逾期未复函（第34条 15日） <el-tag type="danger" size="small">{{ d.assistOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.assistOverdue" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.case_id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="org" label="协查单位" show-overflow-tooltip />
            <el-table-column prop="due_at" label="复函期限" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>听证意见超期（辽50条 2日） <el-tag type="danger" size="small">{{ d.hearingOpinionOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.hearingOpinionOverdue" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.case_id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="held_at" label="举行日期" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>责令改正逾期未报告 <el-tag type="danger" size="small">{{ d.correctOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.correctOverdue" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.case_id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="title" label="文书" show-overflow-tooltip />
            <el-table-column prop="due_at" label="改正期限" width="110" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 决定作出之后：公开/备案/分期，以及执行终了前的三道法定期限 -->
    <el-row :gutter="12">
      <el-col :span="12">
        <el-card class="mb">
          <h4>处罚决定公开超期（辽56条 7日） <el-tag type="danger" size="small">{{ d.publishOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.publishOverdue" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="name" label="案件" show-overflow-tooltip />
            <el-table-column prop="decided_at" label="决定日期" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>重大处罚未报政府备案（辽54条） <el-tag type="warning" size="small">{{ d.govRecordMissing?.length || 0 }}</el-tag></h4>
          <el-table :data="d.govRecordMissing" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.case_id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="fine_amount" label="罚款" width="110" />
            <el-table-column prop="decided_at" label="决定日期" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>简易程序备案超期（第51条 7个工作日） <el-tag type="danger" size="small">{{ d.summaryRecordOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.summaryRecordOverdue" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="name" label="案件" show-overflow-tooltip />
            <el-table-column prop="decided_at" label="决定日期" width="110" />
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>分期到期未缴（第54条） <el-tag type="danger" size="small">{{ d.installmentOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.installmentOverdue" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.case_id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="seq" label="期次" width="70" />
            <el-table-column prop="amount" label="金额" width="100" />
            <el-table-column prop="due_at" label="应缴日期" width="110" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card class="mb">
          <h4>缴款期届满未缴清（第53条） <el-tag type="danger" size="small">{{ d.paymentOverdue?.length || 0 }}</el-tag></h4>
          <el-table :data="d.paymentOverdue" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="pay_deadline" label="缴款期限" width="110" />
            <el-table-column label="已缴/应缴" width="150">
              <template #default="{ row }">
                {{ row.paid }} / {{ Number(row.fine_amount) + Number(row.recoup_amount) + Number(row.confiscate_amount) }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>
        <el-card class="mb">
          <h4>逾期未缴且尚未催告（行政强制法54条） <el-tag type="warning" size="small">{{ d.urgeLetterMissing?.length || 0 }}</el-tag></h4>
          <el-table :data="d.urgeLetterMissing" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="name" label="案件" show-overflow-tooltip />
            <el-table-column prop="pay_deadline" label="缴款期限" width="110" />
          </el-table>
          <div class="hint">催告是申请法院强制执行的法定前置：须先制作催告书（文书类型 URGE_LETTER）并登记送达，送达满 10 日仍不履行方可申请。</div>
        </el-card>
        <el-card class="mb">
          <h4>执行救济期限待确认 <el-tag type="warning" size="small">{{ d.executionReliefUnconfirmed?.length || 0 }}</el-tag></h4>
          <el-table :data="d.executionReliefUnconfirmed" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="name" label="案件" show-overflow-tooltip />
          </el-table>
          <div class="hint">打开案件详情，登记法定救济期限、程序是否进行中及暂缓期限后，系统计算申请期限。</div>
        </el-card>
        <el-card class="mb">
          <h4>强制执行申请期将满/已过（行政强制法53条）
            <el-tag type="danger" size="small">{{ d.courtEnforceExpiring?.length || 0 }}</el-tag></h4>
          <el-table :data="d.courtEnforceExpiring" size="small" border @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
            <el-table-column prop="case_no" label="案号" width="160" />
            <el-table-column prop="urged_at" label="催告日期" width="110" />
            <el-table-column label="申请期限" width="180">
              <template #default="{ row }">
                {{ String(row.apply_deadline).slice(0, 10) }}
                <el-tag :type="row.days_left < 0 ? 'danger' : 'warning'" size="small">
                  {{ row.days_left < 0 ? `已逾期 ${-row.days_left} 天` : `剩 ${row.days_left} 天` }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <div class="hint">申请期限按已登记的法定救济期限与暂缓期限计算；救济程序进行中不得申请，事实未确认的案件请先补录。</div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import client from '../../api/client'

const d = ref<any>({})
// 服务端每类只返回最紧急的前 topN 条（此前无上界，真实数据量下一次要渲染六千余行）
const anyTruncated = computed(() => Object.keys(d.value?.truncated || {}).length > 0)
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    const resp = await client.get('/bureau/stats/supervision')
    d.value = resp.data.data
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.mb { margin-bottom: 12px; }
h4 { margin: 0 0 8px; }
.hint { color: #909399; font-size: 12px; margin-top: 6px; line-height: 1.5; }
</style>
