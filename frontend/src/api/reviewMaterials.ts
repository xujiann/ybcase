export interface MaterialReview {
  id: number | string
  reviewedAt?: string | null
  materialFingerprint?: string | null
  materialSnapshot?: unknown
}
export interface ReviewMaterialStatus {
  reviewId?: number | string | null
  fingerprint?: string | null
  current?: boolean
  bound?: boolean
  reason?: string
}
export function reviewVersion(row: MaterialReview, status?: ReviewMaterialStatus) {
  if (status?.reviewId == null || String(row.id) !== String(status.reviewId)) return { label: '历史申请版本', type: 'info' as const, actionable: false, reason: '后续已有新审核申请，旧申请只供查阅' }
  if (!status.bound) return { label: '历史未绑定材料', type: 'warning' as const, actionable: false, reason: status.reason || '历史审核未保存材料快照，请重新送审' }
  if (!status.current) return { label: '材料已变化', type: 'warning' as const, actionable: false, reason: status.reason || '申请快照与当前材料不一致，请重新送审' }
  return { label: '当前材料版本', type: 'success' as const, actionable: !row.reviewedAt, reason: status.reason || '本次申请快照与当前材料一致' }
}
const SNAPSHOT_FIELDS: Record<string, string> = {
  id: '记录ID', case_id: '案件ID', case_no: '案号', party_name: '当事人', party_type: '当事人类别',
  party_credit_no: '证照/证件号', party_address: '当事人地址', party_legal_rep: '法定代表人', party_contact: '联系方式',
  cause_id: '案由ID', cause_category: '案由类别', cause_description: '案由说明', cause_item_no: '案由条目',
  summary: '案情摘要', amount_involved: '涉案金额', procedure_type: '程序类型', enforce_item_id: '执法事项ID',
  violation_end_date: '行为终了日', health_harm: '涉及生命健康危害',
  jurisdiction_type: '管辖记录类型', jurisdiction_basis: '管辖依据', violation_place: '违法发生地',
  treatment_place: '就医地', insured_place: '参保地', designated_no: '指定管辖文号', jurisdiction_dispute_note: '争议/协商记录',
  title: '文书标题', content: '正文', doc_type: '文书类型', made_at: '制作日期', maker: '制作人', signed: '签署登记',
  type: '材料类型', name: '名称', source: '来源', note: '备注说明', obtained_at: '取得日期', keeper: '保管人',
  cross_exam_at: '质证日期', cross_exam_opinion: '质证意见', cross_exam_result: '质证结果',
  filename: '附件文件名', content_type: '文件格式', size_bytes: '文件大小（字节）', category: '附件分类',
  document_id: '关联文书ID', evidence_id: '关联证据ID', source_clue_id: '来源线索ID', source_inspection_id: '来源检查ID',
  uploaded_by: '上传账号', uploaded_at: '上传时间',
  proposed_fine: '拟罚款金额', proposed_recoup: '拟追回基金', proposed_confiscate: '拟没收金额',
  statement: '陈述申辩', statement_review: '申辩复核意见', change_reason: '再告知变更理由',
  notice_id: '告知版本', notified_at: '告知登记日', statement_deadline: '陈述申辩截止日',
  statement_received_at: '申辩收到日', statement_waived: '是否明确放弃申辩',
  hearing_entitled: '是否告知听证权利', hearing_requested: '是否申请听证',
  hearing_requested_at: '听证申请收到日', hearing_request_deadline: '听证申请截止日',
  delivery_id: '回证记录编号', method: '送达方式', delivered_at: '实际送达日', receipt_no: '回证编号',
  receipt_signed_at: '回证签收日', receiver: '受送达人', notice_sent_at: '听证通知送达日',
  scheduled_at: '听证计划日', held_at: '实际举行日', status: '状态',
  event_type: '事件类型', reference: '凭据', recorded_by: '登记账号', recorded_at: '登记时间',
  host: '听证主持人', host_dept: '主持人部门', recorder: '记录人', record: '笔录内容',
  opinion: '意见', experts: '评审专家', org: '协查单位', result: '答复结果', reply: '协查回复', reply_content: '回复内容',
  refused: '是否拒绝协查', refuse_reason: '拒绝理由', conclusion: '结论',
}
function snapshotText(value: unknown): string {
  if (value == null || value === '') return '—'
  if (Array.isArray(value)) {
    return value.length ? value.map((item, i) => `—— 记录 ${i + 1} ——\n${snapshotText(item)}`).join('\n\n') : '无材料'
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value)
    return entries.length ? entries.map(([key, item]) => {
      const label = Object.hasOwn(SNAPSHOT_FIELDS, key) ? SNAPSHOT_FIELDS[key] : key
      return `${label}：${snapshotText(item)}`
    }).join('\n') : '无材料'
  }
  return typeof value === 'boolean' ? value ? '是' : '否' : String(value)
}
export function snapshotSections(snapshot: unknown): { key: string; label: string; content: string }[] {
  let parsed = snapshot
  if (typeof parsed === 'string') {
    try { parsed = JSON.parse(parsed) } catch { return [{ key: 'raw', label: '申请材料快照（原始文本）', content: parsed as string }] }
  }
  if (parsed == null) return []
  const labels: Record<string, string> = {
    schemaVersion: '快照格式版本', version: '快照格式版本', caseFile: '案件事实', facts: '案件事实', caseFacts: '案件事实',
    finalReport: '调查终结报告', reports: '调查终结报告', report: '调查终结报告', evidences: '证据材料', evidence: '证据材料',
    attachments: '附件材料', documents: '调查报告与实质文书', notices: '处罚告知与陈述申辩', notice: '处罚告知与陈述申辩',
    noticeProcedures: '各告知版本的送达与期限', noticeProcedureEvents: '放弃申辩和撤回听证凭据', noticeHearingLinks: '听证与告知版本关联',
    hearings: '听证材料', hearing: '听证材料', expertReviews: '已结束专家评审意见', assists: '协查实质回复', meetings: '集体讨论', officers: '办案人员', jurisdiction: '管辖事实',
  }
  const entries: [string, unknown][] = typeof parsed === 'object' && !Array.isArray(parsed) ? Object.entries(parsed) : [['snapshot', parsed]]
  return entries.map(([key, value]) => ({ key, label: Object.hasOwn(labels, key) ? labels[key] : key, content: snapshotText(value) }))
}
/** Clone at selection time: the displayed application must never become live case data. */
export function cloneReview(row: MaterialReview): MaterialReview {
  return JSON.parse(JSON.stringify(row))
}
