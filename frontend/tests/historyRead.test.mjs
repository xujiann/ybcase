import test from 'node:test'
import assert from 'node:assert/strict'
import { isManagedHistoryRead, isTransientHistoryReadFailure, historyReadErrorMessage, historyReadDiagnostic, isHistoryPagePayload } from '../src/api/historyRead.ts'
import { historyPageState, createHistoryPagination } from '../src/api/historyPagination.ts'

const network = (code='ERR_NETWORK') => ({isAxiosError:true,code})
const http = status => ({isAxiosError:true,code:'ERR_BAD_RESPONSE',response:{status,headers:{'x-request-id':'request-123'}}})
const page = rows => ({rows,total:rows.length,page:1,size:20})

test('malformed 200 pages cannot masquerade as successful empty history',()=>{
  for (const value of [null,'<html>proxy</html>',{},[],{rows:null,total:0,page:1,size:20},
    {rows:[],total:-1,page:1,size:20},{rows:[],total:NaN,page:1,size:20},
    {rows:[],total:0,page:0,size:20},{rows:[],total:0,page:1,size:101},
    {rows:[{}],total:0,page:1,size:20},{rows:[{},{}],total:2,page:1,size:1},
    {rows:[null],total:1,page:1,size:20}])
    assert.equal(isHistoryPagePayload(value),false)
  for (const value of [page([]),page([{id:1}]),{rows:[],total:200,page:11,size:20}])
    assert.equal(isHistoryPagePayload(value),true)
})

test('history diagnostics retain correlation without query text, tokens or error bodies',()=>{
  const config={method:'get',url:'/bureau/rewards',historyRead:true,params:{q:'PRIVATE_QUERY'},headers:{Authorization:'PRIVATE_TOKEN'}}
  const failure={...http(502),message:'PRIVATE_MESSAGE',response:{status:502,headers:{'x-request-id':'app-123','x-trace-id':'edge-456','set-cookie':'PRIVATE_COOKIE'},data:'PRIVATE_BODY'}}
  const detail=historyReadDiagnostic(config,failure,127.6)
  assert.deepEqual(detail,{endpoint:'/bureau/rewards',code:'ERR_BAD_RESPONSE',httpStatus:502,bizCode:null,requestId:'app-123',traceId:'edge-456',elapsedMs:128})
  assert.doesNotMatch(JSON.stringify(detail),/PRIVATE/)
  assert.equal(historyReadDiagnostic(config,{code:'ERR_CANCELED'},1),null)
  assert.equal(historyReadDiagnostic({...config,method:'post'},failure,1),null)
  assert.equal(historyReadDiagnostic({...config,url:'/bureau/rewards?q=PRIVATE'},failure,1),null)
  assert.equal(historyReadDiagnostic(config,{code:'PRIVATE',traceId:'bad\nPRIVATE'},NaN).traceId,null)
})

test('gateway failures without an application response still expose a safe edge ID',()=>{
  const error={isAxiosError:true,response:{status:502,headers:{'x-trace-id':'edge-only'}}}
  assert.equal(historyReadErrorMessage(error),'列表加载失败，请刷新重试。（HTTP 502；链路编号 edge-only）')
  assert.match(historyReadErrorMessage({code:'ERR_HISTORY_RESPONSE',httpStatus:200,traceId:'edge-format'}),/响应格式异常.*HTTP 200.*edge-format/)
  assert.equal(isTransientHistoryReadFailure({code:'ERR_HISTORY_RESPONSE'}),false)
})

test('business failures and transport failures remain distinguishable without a server response',()=>{
  const config={method:'get',url:'/bureau/rewards',historyRead:true}
  assert.equal(historyReadDiagnostic(config,{httpStatus:200,bizCode:403},5).bizCode,403)
  assert.deepEqual(historyReadDiagnostic(config,network(),15000),{endpoint:'/bureau/rewards',code:'ERR_NETWORK',httpStatus:null,bizCode:null,requestId:null,traceId:null,elapsedMs:15000})
})

test('invalid history response stops without retry, preserves correlation and permits manual recovery',async()=>{
  const f=fixture(),loaded=f.paging.load()
  f.requests[0].reject({code:'ERR_HISTORY_RESPONSE',httpStatus:200,requestId:'app-format',traceId:'edge-format'})
  assert.equal(await loaded,false);assert.equal(f.requests.length,1);assert.equal(f.state.failed,true)
  assert.match(f.state.error,/响应格式异常.*app-format.*edge-format/)
  const refresh=f.paging.load();f.requests[1].resolve(page([]));assert.equal(await refresh,true)
  assert.equal(f.state.failed,false);assert.equal(f.state.error,'')
})
function fixture(delay=0) {
  const state=historyPageState(),requests=[],waiters=new Map()
  const paging=createHistoryPagination(state,(params,{signal})=>new Promise((resolve,reject)=>{
    const index=requests.length,request={params,signal,resolve,reject}
    requests.push(request);waiters.get(index)?.(request)
  }),delay)
  function next(index) {return requests[index] ? Promise.resolve(requests[index]) : new Promise(resolve=>waiters.set(index,resolve))}
  return {state,paging,requests,next}
}

test('managed notifications are restricted to explicit GETs on the three history endpoints',()=>{
  for(const url of ['/bureau/rewards','/bureau/inspections','/bureau/transfers']) {
    assert.equal(isManagedHistoryRead({method:'get',url,historyRead:true}),true)
    for(const method of ['post','put','delete','patch']) assert.equal(isManagedHistoryRead({method,url,historyRead:true}),false)
    assert.equal(isManagedHistoryRead({method:'get',url}),false)
  }
  for(const url of ['/bureau/rewards/1/pay','/auth/login','https://example.com/bureau/rewards'])
    assert.equal(isManagedHistoryRead({method:'get',url,historyRead:true}),false)
})
test('only transport failures and 502/503/504 are eligible for one retry',()=>{
  for(const code of ['ERR_NETWORK','ECONNABORTED','ETIMEDOUT','ECONNRESET']) assert.equal(isTransientHistoryReadFailure(network(code)),true)
  for(const status of [502,503,504]) assert.equal(isTransientHistoryReadFailure(http(status)),true)
  for(const status of [400,401,403,404,409,422,429,500]) assert.equal(isTransientHistoryReadFailure(http(status)),false)
  for(const error of [network('ERR_CANCELED'),new Error('offline'),{bizCode:500,httpStatus:200},null])
    assert.equal(isTransientHistoryReadFailure(error),false)
})
test('final failure details expose a safe status and request id without arbitrary exception text',()=>{
  assert.equal(historyReadErrorMessage(http(503)),'列表加载失败，请刷新重试。（HTTP 503；请求编号 request-123）')
  assert.equal(historyReadErrorMessage({httpStatus:200,requestId:'biz-1'}),'列表加载失败，请刷新重试。（HTTP 200；请求编号 biz-1）')
  assert.equal(historyReadErrorMessage({message:'private payload',requestId:'<script>secret</script>'}),'列表加载失败，请刷新重试。')
})
test('a transient read recovers once without flashing failure or changing the query snapshot',{timeout:2000},async()=>{
  const f=fixture();f.state.q='  原查询  '
  const loaded=f.paging.load();f.requests[0].reject(http(502))
  const retry=await f.next(1)
  assert.deepEqual(retry.params,{page:1,size:20,q:'原查询'})
  assert.equal(f.state.loading,true);assert.equal(f.state.failed,false);assert.equal(f.state.error,'')
  const rows=[{id:7,reporter_name:'赵**',reporter_contact:'***'}]
  retry.resolve(page(rows));assert.equal(await loaded,true)
  assert.equal(f.state.rows,rows);assert.equal(f.state.failed,false);assert.equal(f.state.loading,false)
  assert.equal(f.requests.length,2)
})
test('a second transport failure stops at two attempts and clears stale counts',{timeout:2000},async()=>{
  const f=fixture();f.state.rows=[{id:'old'}];f.state.total=237;f.state.page=9
  const loaded=f.paging.load();f.requests[0].reject(network('ECONNABORTED'))
  const retry=await f.next(1);retry.reject(http(504))
  assert.equal(await loaded,false);assert.equal(f.requests.length,2)
  assert.deepEqual(f.state.rows,[]);assert.equal(f.state.total,0);assert.equal(f.state.page,1);assert.equal(f.state.failed,true)
  await f.paging.changePage(1);assert.equal(f.requests.length,2,'widget normalization must not start a new retry cycle')
  assert.match(f.state.error,/HTTP 504.*request-123/);assert.equal(f.state.loading,false)
  const refreshed=f.paging.load();f.requests[2].resolve(page([]));assert.equal(await refreshed,true)
  assert.equal(f.state.failed,false);assert.equal(f.state.error,'')
})
test('authorization, validation and business errors are never retried',async()=>{
  for(const error of [http(401),http(403),http(400),http(429),http(500),{bizCode:2080,httpStatus:200,requestId:'scope-1'}]) {
    const f=fixture(),loaded=f.paging.load();f.requests[0].reject(error)
    assert.equal(await loaded,false);assert.equal(f.requests.length,1);assert.equal(f.state.failed,true)
  }
})
test('superseding a request aborts it and prevents a late failure from retrying or replacing current rows',async()=>{
  const f=fixture(),old=f.paging.load();f.state.q='新查询';const current=f.paging.search()
  assert.equal(f.requests[0].signal.aborted,true)
  f.requests[1].resolve(page([{id:'current'}]));await current
  f.requests[0].reject(network());assert.equal(await old,false)
  assert.equal(f.requests.length,2);assert.equal(f.state.rows[0].id,'current');assert.equal(f.state.failed,false)
})
test('new search during retry backoff cancels the old retry and preserves the newer spinner',async()=>{
  const f=fixture(1000),old=f.paging.load();f.requests[0].reject(network())
  await new Promise(setImmediate)
  f.state.q='new';const current=f.paging.search()
  assert.equal(await old,false);assert.equal(f.requests.length,2);assert.equal(f.state.loading,true)
  f.requests[1].resolve(page([{id:2}]));await current
  assert.equal(f.state.rows[0].id,2)
})
test('unmount cancels backoff and disposed pages cannot issue or apply further reads',async()=>{
  const f=fixture(1000),old=f.paging.load();f.requests[0].reject(network());await new Promise(setImmediate)
  f.paging.dispose();assert.equal(await old,false)
  assert.equal(f.requests[0].signal.aborted,true);assert.equal(f.requests.length,1);assert.equal(f.state.loading,false)
  assert.equal(await f.paging.load(),false);assert.equal(f.requests.length,1)
})
