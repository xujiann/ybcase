<template>
  <el-card v-if="allowed" class="readiness">
    <div class="toolbar"><h4>我的办理条件</h4><el-button :loading="loading" @click="load">核对当前账号</el-button></div>
    <el-alert v-if="failed" type="warning" :closable="false" title="账号条件未能读取，请刷新核对；原有业务权限仍由服务端检查。" />
    <template v-if="state">
      <p>所属机构：{{ state.department }}　账号类别：{{ categories[state.accountCategory] || state.accountCategory }}</p>
      <el-alert v-if="!state.issues.length" type="success" :closable="false" title="当前账号配置检查通过。办理具体业务时仍需满足材料、岗位、回避及来源范围条件。" />
      <div v-for="issue in state.issues" :key="issue.code" class="issue">
        <el-alert type="warning" :closable="false" :title="issue.message" />
        <el-button v-if="state.administrator && adminPath(issue.adminPath)" link type="primary" @click="navigate(issue.adminPath)">进入管理员核对</el-button>
        <span v-else>请联系管理员按正式资料核实。</span>
      </div>
      <p v-if="state.issues.length">配置缺项须核实后处理，反复刷新不会完成机构归类或执法资格确认。</p>
    </template>
  </el-card>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'
const auth=useAuthStore(),router=useRouter()
interface Readiness {department:string;accountCategory:string;administrator:boolean;issues:{code:string;message:string;adminPath:string}[];capabilities:Record<string,boolean>}
const state=ref<Readiness>(),loading=ref(false),failed=ref(false)
const categories:Record<string,string>={DISTRICT:'区市县',BUREAU:'局机关',AGENCY:'医保中心'}
const allowed=computed(()=>!!auth.user?.roles.some(r=>['HANDLER','LEGAL','LEADER','ADMIN'].includes(r))&&!auth.user.roles.some(r=>['AGENCY','INTEGRATION'].includes(r)))
const identity=computed(()=>JSON.stringify([auth.token,auth.user?.id,auth.user?.roles,auth.user?.accountCategory]))
let disposed=false,sequence=0
function context(){return {identity:identity.value,stamp:sessionExpiry.capture(auth.token)}}
function current(c:ReturnType<typeof context>){return !disposed&&allowed.value&&c.identity===identity.value&&sessionExpiry.isCurrent(c.stamp,auth.token)&&!sessionExpiry.isExpired(c.stamp,auth.token)}
function clear(){sequence++;state.value=undefined;loading.value=false;failed.value=false}
async function load(){
 if(!allowed.value||disposed||!auth.token)return
 const run=++sequence,c=context();state.value=undefined;loading.value=true;failed.value=false
 try{const {data}=await client.get('/bureau/account-readiness');if(run===sequence&&current(c)){if(!Array.isArray(data.data?.issues))throw new Error('Invalid readiness');state.value=data.data}}
 catch{if(run===sequence&&current(c))failed.value=true}
 finally{if(run===sequence)loading.value=false}
}
function adminPath(path:string){return ['/system/users','/system/depts','/system/enforcers'].includes(path)}
function navigate(path:string){if(!disposed&&allowed.value&&state.value?.administrator&&adminPath(path))void router.push(path)}
watch(identity,()=>{clear();void load()},{immediate:true,flush:'sync'})
onMounted(()=>window.addEventListener(SESSION_EXPIRED_EVENT,clear))
onBeforeUnmount(()=>{disposed=true;clear();window.removeEventListener(SESSION_EXPIRED_EVENT,clear)})
</script>
<style scoped>.readiness{margin-bottom:12px}.toolbar{display:flex;align-items:center;justify-content:space-between}.toolbar h4{margin:0}.issue{margin-top:8px}.issue span{font-size:12px;color:#666}</style>
