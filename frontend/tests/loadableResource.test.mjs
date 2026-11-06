import test from 'node:test'
import assert from 'node:assert/strict'
import { resourceState, createResourceLoader } from '../src/api/loadableResource.ts'
test('unloaded, loading and failed reports are never export-ready', async () => {
 const state=resourceState({monthly:[]});assert.equal(state.ready,false)
 let reject;const loader=createResourceLoader(state,()=>new Promise((_,r)=>reject=r))
 const loaded=loader.load();assert.equal(state.loading,true);assert.equal(state.ready,false)
 reject(new Error('offline'));assert.equal(await loaded,false)
 assert.equal(state.failed,true);assert.equal(state.ready,false);assert.equal(state.loading,false)
})
test('independent reports still load when an unrelated statistics endpoint fails', async () => {
 const overview=resourceState({}),report=resourceState({monthly:[]})
 await Promise.allSettled([createResourceLoader(overview,async()=>{throw new Error('offline')}).load(),createResourceLoader(report,async()=>({monthly:[],totals:{filed_total:0},scopeLabel:'当前可见业务统计'})).load()])
 assert.equal(overview.ready,false);assert.equal(report.ready,true);assert.equal(report.data.totals.filed_total,0)
})
test('retry invalidates prior export readiness and rejects late success from an older request', async () => {
 const state=resourceState({}),pending=[],loader=createResourceLoader(state,()=>new Promise(resolve=>pending.push(resolve)))
 const old=loader.load(),latest=loader.load();pending[1]({scopeLabel:'最新'});await latest
 pending[0]({scopeLabel:'过期'});await old;assert.equal(state.data.scopeLabel,'最新')
 const retry=loader.load();assert.equal(state.ready,false);pending[2]({scopeLabel:'刷新后'});await retry;assert.equal(state.ready,true)
})

test('switching parameter history keeps the new key paired with its rows when responses arrive out of order',async()=>{
 const state=resourceState([]),pending=[],loader=createResourceLoader(state,()=>new Promise((resolve,reject)=>pending.push({resolve,reject})))
 const first=loader.load();loader.invalidate();state.data=[];const second=loader.load()
 pending[1].resolve([{key:'B',old_value:'b-old'}]);await second
 pending[0].resolve([{key:'A',old_value:'a-old'}]);await first
 assert.equal(state.data[0].key,'B');assert.equal(state.ready,true)
})

test('closing parameter history prevents late success or failure from reopening readiness or retaining a spinner',async()=>{
 for(const failure of [false,true]) {
  const state=resourceState([]);let resolve,reject
  const loader=createResourceLoader(state,()=>new Promise((yes,no)=>{resolve=yes;reject=no}))
  const reading=loader.load();loader.invalidate();state.data=[]
  failure ? reject(new Error('old error')) : resolve([{key:'old'}])
  assert.equal(await reading,false);assert.deepEqual(state,{data:[],ready:false,loading:false,failed:false})
 }
})
