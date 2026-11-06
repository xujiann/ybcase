import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-dom'
import * as Vue from 'vue'
import { renderToString } from '@vue/server-renderer'
import { reviewVersion, snapshotSections, cloneReview } from '../src/api/reviewMaterials.ts'
test('only the latest pending review bound to current materials can be processed', () => {
  const row={id:2,materialSnapshot:{facts:{name:'old'}},materialFingerprint:'abc'}
  const status={reviewId:2,bound:true,current:true}
  assert.equal(reviewVersion(row,status).actionable,true)
  assert.equal(reviewVersion({...row,reviewedAt:'2026-10-21'},status).actionable,false)
  assert.equal(reviewVersion({...row,id:1},status).label,'历史申请版本')
  assert.equal(reviewVersion({...row,id:1},status).actionable,false)
  assert.equal(reviewVersion(row,{...status,current:false}).label,'材料已变化')
  assert.equal(reviewVersion(row,{...status,bound:false}).label,'历史未绑定材料')
})
test('snapshot viewing clones the selected application, never current case facts', () => {
  const row={id:1,materialSnapshot:{caseFile:{partyName:'送审时'},evidences:[{name:'原证据'}]},materialFingerprint:'fixed'}
  const selected=cloneReview(row)
  row.materialSnapshot.caseFile.partyName='当前修改'
  row.materialSnapshot.evidences.push({name:'新增证据'})
  assert.equal(selected.materialSnapshot.caseFile.partyName,'送审时')
  assert.equal(selected.materialSnapshot.evidences.length,1)
})
test('snapshot sections parse JSON with useful categories and preserve user content as text', () => {
  const sections=snapshotSections(JSON.stringify({caseFile:{name:'<script>alert(1)</script>'},reports:[],evidences:[],attachments:[],notices:[],hearings:[]}))
  assert.deepEqual(sections.map(s=>s.label),['案件事实','调查终结报告','证据材料','附件材料','处罚告知与陈述申辩','听证材料'])
  assert.ok(sections[0].content.includes('<script>')) // Text interpolation in component, never v-html.
  assert.equal(snapshotSections(null).length,0)
  assert.equal(snapshotSections('{broken')[0].content,'{broken')
})

test('real snapshot sections identify expert opinions and answered assistance material', () => {
  assert.deepEqual(snapshotSections({schemaVersion:1,caseFacts:{},documents:[],evidences:[],attachments:[],notices:[],hearings:[],expertReviews:[],assists:[]}).map(s=>s.label),['快照格式版本','案件事实','调查报告与实质文书','证据材料','附件材料','处罚告知与陈述申辩','听证材料','已结束专家评审意见','协查实质回复'])
})

test('snapshot text labels preserve facts, record boundaries, unknown fields and empty groups', () => {
  const snapshot={caseFacts:{party_name:'某医院',summary:'原案情',amount_involved:123,unknown_future_field:'未来字段'},documents:[{id:1,title:'调查报告',content:'原正文'},{id:2,title:'其他文书',doc_type:'OTHER'}],evidences:[{name:'原证据',source:'举报人',cross_exam_opinion:'质证原意见'}],attachments:[{id:3,evidence_id:4,document_id:5,source_clue_id:6}],notices:[{proposed_fine:100,statement:'原申辩',statement_review:'原复核'}],hearings:[]}
  const before=structuredClone(snapshot),sections=snapshotSections(snapshot),content=sections.map(s=>s.content).join('\n')
  for (const text of ['当事人：某医院','案情摘要：原案情','涉案金额：123','unknown_future_field：未来字段','—— 记录 2 ——','文书标题：调查报告','正文：原正文','来源：举报人','质证意见：质证原意见','关联证据ID：4','关联文书ID：5','来源线索ID：6','拟罚款金额：100','陈述申辩：原申辩','申辩复核意见：原复核']) assert.ok(content.includes(text),text)
  assert.equal(sections.find(s=>s.key==='hearings').content,'无材料')
  assert.deepEqual(snapshot,before)
})
test('actual snapshot component template renders hostile field text as escaped text', async () => {
  const source=readFileSync(new URL('../src/components/ReviewMaterialSnapshot.vue',import.meta.url),'utf8')
  const template=parse(source).descriptor.template.content
  const render=new Function('Vue',compile(template,{mode:'function'}).code)(Vue)
  const review={id:1,materialFingerprint:'fixed',materialSnapshot:{caseFacts:{party_name:'<img src=x onerror=alert(1)>'},documents:[{content:'<script>alert(1)</script>'}]}}
  const app=Vue.createSSRApp({render,setup:()=>({review,sections:snapshotSections(review.materialSnapshot)})})
  for (const name of ['el-alert','el-collapse','el-collapse-item']) app.component(name,{setup:(_, {slots})=>()=>Vue.h('section',slots.default?.())})
  app.config.warnHandler=()=>{}
  const html=await renderToString(app)
  assert.ok(html.includes('当事人：&lt;img'))
  assert.ok(html.includes('正文：&lt;script&gt;'))
  assert.equal(html.includes('<img'),false)
  assert.equal(html.includes('<script>'),false)
})
