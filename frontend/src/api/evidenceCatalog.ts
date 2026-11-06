export interface EvidenceCatalogItem {
  number: string; kind: string; id: number; title?: string; filename?: string; category?: string
  evidenceId?: number; documentId?: number; sourceClueId?: number; sourceInspectionId?: number
  documentTitle?: string; evidenceTitle?: string; downloadUrl?: string
  sizeBytes?: number; uploadedBy?: string; uploadedAt?: string; voided?: boolean; voidReason?: string
}
export function catalogAssociation(item: EvidenceCatalogItem): string {
  return [item.evidenceId ? `证据#${item.evidenceId}${item.evidenceTitle ? `（${item.evidenceTitle}）` : ''}` : '', item.documentId ? `文书#${item.documentId}${item.documentTitle ? `（${item.documentTitle}）` : ''}` : '',
    item.sourceClueId ? `线索#${item.sourceClueId}` : '', item.sourceInspectionId ? `检查#${item.sourceInspectionId}` : '']
    .filter(Boolean).join('；') || '案件材料'
}
function escapeHtml(value: unknown): string {
  return String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]!))
}
/** Text index only: never embed binary attachment content or invent page numbers. */
export function evidenceCatalogHtml(items: EvidenceCatalogItem[], includeVoided = false): string {
  const rows = items.map(item => `<tr><td>${escapeHtml(item.number)}</td><td>${item.kind === 'EVIDENCE' ? '证据记录' : '附件'}</td><td>${escapeHtml(item.title || item.filename)}</td><td>${escapeHtml(catalogAssociation(item))}</td><td>${escapeHtml(item.voided ? `已作废：${item.voidReason || ''}` : '有效')}</td></tr>`).join('')
  return `<section class="page"><h2>${includeVoided || items.some(item => item.voided) ? '证据材料追溯索引（含作废记录，非有效卷）' : '证据卷文字索引'}</h2><p>本索引仅列材料编号及附件映射，不包含附件正文；二进制材料请按编号另行调阅。</p><table><tr><th>编号</th><th>类型</th><th>材料名称</th><th>关联与来源</th><th>状态</th></tr>${rows}</table></section>`
}
