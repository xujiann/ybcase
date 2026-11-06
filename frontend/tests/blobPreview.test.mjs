import test from 'node:test'
import assert from 'node:assert/strict'
import { createBlobPreview } from '../src/api/blobPreview.ts'
function fixture() {
 const state={url:'',loading:false,failed:false},created=[],revoked=[]
 const preview=createBlobPreview(state,{createObjectURL:b=>{created.push(b);return `blob:${created.length}`},revokeObjectURL:u=>revoked.push(u)})
 return {state,created,revoked,preview}
}
test('a delayed old feedback screenshot cannot replace the selected newer screenshot', async () => {
 const f=fixture();let finishA,finishB
 const a=f.preview.show(()=>new Promise(resolve=>finishA=resolve)),b=f.preview.show(()=>new Promise(resolve=>finishB=resolve))
 const blobB=new Blob(['B']);finishB(blobB);await b
 finishA(new Blob(['A']));await a
 assert.equal(f.state.url,'blob:1');assert.deepEqual(f.created,[blobB]);assert.equal(f.state.loading,false)
})
test('switching contexts releases old object URLs and no-screenshot context clears its image', async () => {
 const f=fixture();await f.preview.show(async()=>new Blob(['A']))
 await f.preview.show(async()=>new Blob(['B']));assert.deepEqual(f.revoked,['blob:1'])
 await f.preview.show();assert.deepEqual(f.revoked,['blob:1','blob:2']);assert.equal(f.state.url,'')
})
test('closing a pending screenshot or a failed request cannot resurrect an image', async () => {
 const f=fixture();let finish
 const pending=f.preview.show(()=>new Promise(resolve=>finish=resolve));f.preview.clear();finish(new Blob(['late']));await pending
 assert.equal(f.created.length,0);assert.equal(f.state.url,'')
 await f.preview.show(async()=>{throw new Error('offline')})
 assert.equal(f.state.failed,true);assert.equal(f.state.loading,false);assert.equal(f.state.url,'')
})
