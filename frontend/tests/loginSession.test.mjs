import test from 'node:test'
import assert from 'node:assert/strict'
import { loginSession } from '../src/api/loginSession.ts'

function fixture(tokenRequest, userRequest) {
  const state={token:'old-token',stored:'old-token',user:{roles:['ADMIN']}}
  const actions={
    clear:()=>{state.token='';state.stored='';state.user=null},
    requestToken:tokenRequest,
    setToken:token=>{state.token=token;state.stored=token},
    fetchUser:async()=>{state.user=await userRequest(state)},
  }
  return {state,actions}
}
test('failed identity read rolls back token, persisted token and old account privileges',async()=>{
  const error=new Error('me unavailable'),f=fixture(async()=>'new-token',async state=>{
    assert.equal(state.token,'new-token');assert.equal(state.user,null);throw error
  })
  await assert.rejects(loginSession(f.actions),e=>e===error)
  assert.deepEqual(f.state,{token:'',stored:'',user:null})
})
test('a rejected credential request clears any previous account and never fetches identity',async()=>{
  let reads=0;const f=fixture(async()=>{throw new Error('invalid credentials')},async()=>{reads++;return {}})
  await assert.rejects(loginSession(f.actions));assert.equal(reads,0)
  assert.deepEqual(f.state,{token:'',stored:'',user:null})
})
test('malformed token cannot redirect into a broken authenticated session',async()=>{
  for(const token of [null,undefined,'',' ',123,{}]) {
    let reads=0;const f=fixture(async()=>token,async()=>{reads++;return {}})
    await assert.rejects(loginSession(f.actions),/有效令牌/);assert.equal(reads,0)
    assert.deepEqual(f.state,{token:'',stored:'',user:null})
  }
})
test('manual retry after an identity failure establishes only the new account',async()=>{
  let attempts=0;const f=fixture(async()=>'new-token',async()=>{
    if(++attempts===1)throw new Error('temporary failure')
    return {roles:['HANDLER'],username:'new-account'}
  })
  await assert.rejects(loginSession(f.actions));await loginSession(f.actions)
  assert.deepEqual(f.state,{token:'new-token',stored:'new-token',user:{roles:['HANDLER'],username:'new-account'}})
})
