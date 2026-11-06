import test from 'node:test'
import assert from 'node:assert/strict'
import { historyPageState, createHistoryPagination } from '../src/api/historyPagination.ts'
function fixture() {
  const state=historyPageState(), pending=[]
  const paging=createHistoryPagination(state, params=>new Promise((resolve,reject)=>pending.push({params,resolve,reject})))
  return {state,paging,pending}
}
function response(page=1,size=20,total=237,rows=[{id:page}]) {return {page,size,total,rows}}
test('pagination sends page and size, uses server total beyond the legacy 200 cap and preserves redacted rows', async () => {
  const f=fixture(), promise=f.paging.load(), rows=[{id:1,reporter_name:'已脱敏',reporter_contact:'***'}]
  assert.deepEqual(f.pending[0].params,{page:1,size:20})
  f.pending[0].resolve(response(1,20,237,rows));await promise
  assert.equal(f.state.total,237)
  assert.equal(f.state.rows,rows)
  const next=f.paging.changePage(11)
  assert.deepEqual(f.pending[1].params,{page:11,size:20})
  f.pending[1].resolve(response(11));await next
  assert.equal(f.state.page,11)
})
test('search and page-size changes reset to page one, trim the query and accept 20/50/100', async () => {
  const f=fixture();f.state.page=7;f.state.q='  对象  '
  const searched=f.paging.search();assert.deepEqual(f.pending[0].params,{page:1,size:20,q:'对象'})
  f.pending[0].resolve(response());await searched
  for (const size of [50,100,20]) {
    f.state.page=3
    const changed=f.paging.changeSize(size), request=f.pending.at(-1)
    assert.deepEqual(request.params,{page:1,size,q:'对象'})
    request.resolve(response(1,size));await changed
  }
  f.state.q=' '
  const cleared=f.paging.search();assert.deepEqual(f.pending.at(-1).params,{page:1,size:20})
  f.pending.at(-1).resolve(response());await cleared
})
test('late responses and failures cannot replace a newer page or clear its spinner', async () => {
  const f=fixture(), old=f.paging.load(), current=f.paging.changePage(2)
  f.pending[0].resolve(response(1));await old
  assert.equal(f.state.loading,true)
  assert.deepEqual(f.state.rows,[])
  f.pending[1].resolve(response(2));await current
  assert.equal(f.state.rows[0].id,2)
  const rejected=f.paging.load(), newer=f.paging.changePage(3)
  f.pending[3].resolve(response(3));await newer
  f.pending[2].reject(new Error('old failure'));await rejected
  assert.equal(f.state.rows[0].id,3)
  assert.equal(f.state.failed,false)
})
test('a removed last page reloads the new final page exactly once using the server count', async () => {
  const f=fixture();f.state.page=9
  const promise=f.paging.load()
  f.pending[0].resolve(response(9,20,43,[]))
  await Promise.resolve()
  assert.deepEqual(f.pending[1].params,{page:3,size:20})
  f.pending[1].resolve(response(3,20,43,[{id:43}]))
  await promise
  assert.equal(f.state.page,3)
  assert.equal(f.state.total,43)
  assert.equal(f.state.rows[0].id,43)
  assert.equal(f.pending.length,2)
})
test('a fallback response cannot overwrite a subsequent search', async () => {
  const f=fixture();f.state.page=9
  const old=f.paging.load();f.pending[0].resolve(response(9,20,43,[]));await Promise.resolve()
  f.state.q='新查询'
  const searched=f.paging.search()
  f.pending[2].resolve(response(1,20,1,[{id:'new'}]));await searched
  f.pending[1].resolve(response(3,20,43,[{id:'stale'}]));await old
  assert.equal(f.state.page,1)
  assert.equal(f.state.total,1)
  assert.equal(f.state.rows[0].id,'new')
})
test('refresh preserves the current page while creation explicitly reloads page one', async () => {
  const f=fixture();f.state.page=4
  const refresh=f.paging.load();assert.equal(f.pending[0].params.page,4)
  f.pending[0].resolve(response(4));await refresh
  const created=f.paging.firstPage();assert.equal(f.pending[1].params.page,1)
  f.pending[1].resolve(response(1));await created
})
test('empty searches normalize to page one; duplicate page events do not repeat requests', async () => {
  const f=fixture();f.state.page=2
  const loaded=f.paging.load();f.pending[0].resolve(response(2,20,0,[]));await loaded
  assert.equal(f.state.page,1)
  assert.equal(f.pending.length,1)
  await f.paging.changePage(1);await f.paging.changeSize(20);await f.paging.changeSize(500)
  assert.equal(f.pending.length,1)
})
test('current failures clear stale records and support retry; disposed requests cannot repopulate state', async () => {
  const f=fixture();f.state.rows=[{id:'old'}]
  const failed=f.paging.load();f.pending[0].reject(new Error('offline'));assert.equal(await failed,false)
  assert.deepEqual(f.state.rows,[]);assert.equal(f.state.failed,true);assert.equal(f.state.loading,false)
  const retry=f.paging.load();f.pending[1].resolve(response());await retry;assert.equal(f.state.failed,false)
  const disposed=f.paging.load();f.paging.dispose();f.pending[2].resolve(response(2));await disposed
  assert.equal(f.state.rows[0].id,1)
})
