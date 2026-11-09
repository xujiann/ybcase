<template>
  <el-card v-if="permitted" shadow="never" class="fact-history">
    <template #header><div class="heading"><strong>调查补查与当前有效事实</strong><el-button :disabled="loading || saving" @click="load">刷新</el-button></div></template>
    <el-alert title="补查须依据最新法制审核意见，保留原报告及告知。决定后的事实变更通过执行续办中的独立事实更正办理，不覆盖原卷。" type="info" :closable="false" />
    <el-alert v-if="failed" title="事实版本读取失败，请核对权限或刷新重试" type="warning" :closable="false" />
    <template v-if="state">
      <div class="actions"><el-button v-if="state.canReturnInvestigation" type="warning" :disabled="saving || loading" @click="open('return')">按法审意见退回补查</el-button><el-button v-if="state.canCorrectInstitution" :disabled="saving || loading" @click="open('institution')">更正已填机构资料</el-button></div>
      <el-descriptions :column="2" border style="margin-top:14px">
        <el-descriptions-item label="有效机构编码">{{ state.effectiveFacts.institution_code || '—' }}</el-descriptions-item>
        <el-descriptions-item label="有效机构性质">{{ ({PUBLIC:'公立',SOCIAL:'社会办'} as Record<string,string>)[state.effectiveFacts.institution_ownership] || '—' }}</el-descriptions-item>
        <el-descriptions-item label="有效机构等级">{{ (grades as Record<string,string>)[state.effectiveFacts.institution_grade] || '—' }}</el-descriptions-item>
        <el-descriptions-item label="有效机构类型">{{ ({MEDICAL:'医疗机构',SINGLE_PHARMACY:'单体药店',CHAIN_PHARMACY:'连锁药店'} as Record<string,string>)[state.effectiveFacts.institution_kind] || '—' }}</el-descriptions-item>
        <el-descriptions-item label="有效认定违法期间">{{ state.effectiveFacts.confirmed_violation_start || '—' }} 至 {{ state.effectiveFacts.confirmed_violation_end || '—' }}</el-descriptions-item>
        <el-descriptions-item label="有效认定违法金额">{{ state.effectiveFacts.confirmed_violation_amount ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="有效认定违法事实" :span="2">{{ state.effectiveFacts.confirmed_violation_facts || '—' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="state.history" size="small">
        <el-table-column type="expand"><template #default="{ row }"><p>材料出处：{{ row.source_reference }}</p><p>更正理由：{{ row.reason }}</p><h5>此前事实</h5><pre>{{ factsText(row.previous_facts) }}</pre><h5>本次事实</h5><pre>{{ factsText(row.facts) }}</pre></template></el-table-column>
        <el-table-column prop="revision_type" label="事项" :formatter="(_r: any, _c: any, value: string) => types[value] || value" />
        <el-table-column prop="effective_at" label="生效日期" /><el-table-column prop="actor_username" label="登记／复核账号" /><el-table-column prop="recorded_at" label="留痕时间" />
      </el-table>
    </template>
    <el-dialog v-model="visible" :title="mode === 'return' ? '依据法审意见退回补查' : '更正已填机构资料'" width="min(680px, 95vw)" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" :before-close="beforeClose">
      <el-form label-width="130px" :disabled="saving">
        <template v-if="mode === 'institution'">
          <el-form-item label="机构编码" required><el-input v-model="institution.code" maxlength="64" /></el-form-item>
          <el-form-item label="机构性质" required><el-select v-model="institution.ownership"><el-option label="公立" value="PUBLIC" /><el-option label="社会办" value="SOCIAL" /></el-select></el-form-item>
          <el-form-item label="机构等级" required><el-select v-model="institution.grade"><el-option v-for="(label,key) in grades" :key="key" :label="label" :value="key" /></el-select></el-form-item>
          <el-form-item label="机构类型" required><el-select v-model="institution.kind"><el-option label="医疗机构" value="MEDICAL" /><el-option label="单体药店" value="SINGLE_PHARMACY" /><el-option label="连锁药店" value="CHAIN_PHARMACY" /></el-select></el-form-item>
        </template>
        <el-form-item label="办理理由" required><el-input v-model="reason" type="textarea" :rows="3" maxlength="2000" /></el-form-item>
        <el-form-item label="原始材料出处" required><el-input v-model="sourceReference" type="textarea" maxlength="1000" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">{{ mode==='return' ? '退回补查并保留原报告' : '保存更正并留痕' }}</el-button></template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'
const props=defineProps<{caseId:number}>(), emit=defineEmits<{changed:[]}>(), auth=useAuthStore()
const identity=computed(()=>JSON.stringify([auth.token,auth.user?.id,auth.user?.roles,auth.user?.accountCategory]))
const permitted=computed(()=>!!auth.token && !!auth.user && auth.user.accountCategory!=='AGENCY' && auth.user.roles.some(role=>['HANDLER','LEGAL','LEADER','ADMIN'].includes(role)))
const state=ref<any>(), loading=ref(false), saving=ref(false), failed=ref(false),visible=ref(false),mode=ref('return'),reason=ref(''),sourceReference=ref('')
const institution=reactive({code:'',ownership:'',grade:'',kind:''})
const grades={TERTIARY:'三级',SECONDARY:'二级',PRIMARY:'一级',UNGRADED:'无等级'}
const types:Record<string,string>={INVESTIGATION_RETURN:'法审退回补查',INVESTIGATION_REPORT:'调查终结确认',INSTITUTION_CORRECTION:'机构要素更正',APPROVED_RESULT:'救济结果有效事实',APPROVED_FACT_CORRECTION:'独立事实更正'}
const labels:Record<string,string>={institutionCode:'机构编码',institutionOwnership:'机构性质',institutionGrade:'机构等级',institutionKind:'机构类型',confirmedViolationStart:'违法开始日期',confirmedViolationEnd:'违法结束日期',confirmedViolationFacts:'认定违法事实',confirmedViolationAmount:'认定违法金额',reportContent:'调查终结报告'}
function factsText(facts:Record<string,unknown>) { return Object.entries(facts||{}).map(([key,value])=>`${labels[key]||key}：${value??'—'}`).join('\n') }
let seq=0, generation=0,disposed=false,controller:AbortController|undefined
function context(){return {caseId:props.caseId,identity:identity.value,generation,session:sessionExpiry.capture(auth.token)}}
function current(value:ReturnType<typeof context>){return !disposed && permitted.value && value.caseId===props.caseId && value.identity===identity.value && value.generation===generation && sessionExpiry.isCurrent(value.session,auth.token) && !sessionExpiry.isExpired(value.session,auth.token)}
function clear(){generation++;seq++;controller?.abort();state.value=undefined;loading.value=false;failed.value=false;visible.value=false;reason.value='';sourceReference.value=''}
async function load(){if(!permitted.value||disposed)return;const own=++seq,ctx=context();controller?.abort();controller=new AbortController();loading.value=true;failed.value=false;try{const response=await client.get(`/bureau/cases/${ctx.caseId}/fact-versions`,{signal:controller.signal});if(current(ctx)&&own===seq)state.value=response.data.data}catch{if(current(ctx)&&own===seq){state.value=undefined;failed.value=true}}finally{if(current(ctx)&&own===seq)loading.value=false}}
function open(value:string){if(!state.value||loading.value||saving.value||!(value==='return'?state.value.canReturnInvestigation:state.value.canCorrectInstitution))return;mode.value=value;reason.value='';sourceReference.value='';const f=state.value.originalFacts;Object.assign(institution,{code:f.institutionCode||'',ownership:f.institutionOwnership||'',grade:f.institutionGrade||'',kind:f.institutionKind||''});visible.value=true}
async function submit(){if(!visible.value||saving.value||loading.value||!state.value)return;if(!reason.value.trim()||!sourceReference.value.trim()){ElMessage.warning('请填写理由和材料出处');return}if(mode.value==='institution'&&(!institution.code.trim()||!institution.ownership||!institution.grade||!institution.kind)){ElMessage.warning('请填写完整机构资料');return}const ctx=context(),returning=mode.value==='return';saving.value=true;try{const body={reason:reason.value.trim(),sourceReference:sourceReference.value.trim()};if(returning)await client.post(`/bureau/cases/${ctx.caseId}/fact-versions/return-investigation`,body);else await client.put(`/bureau/cases/${ctx.caseId}/party-info`,{...body,institution:{...institution,code:institution.code.trim()}});if(!current(ctx))return;visible.value=false;ElMessage.success(returning?'已退回补查，原报告和告知保留；新报告后须重新告知并送审':'机构更正已留痕');emit('changed');await load()}catch{/* Preserve inputs for an explicit retry. */}finally{saving.value=false}}
function beforeClose(done:()=>void){if(!saving.value)done()}
function expire(){clear()}
watch(()=>[props.caseId,identity.value],()=>{clear();void load()},{flush:'sync'})
onMounted(()=>{window.addEventListener(SESSION_EXPIRED_EVENT,expire);void load()})
onBeforeUnmount(()=>{disposed=true;clear();window.removeEventListener(SESSION_EXPIRED_EVENT,expire)})
</script>
<style scoped>.fact-history{margin-top:16px}.heading,.actions{display:flex;gap:12px;align-items:center;justify-content:space-between}.actions{justify-content:flex-start;margin-top:14px}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style>
