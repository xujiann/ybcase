-- 第三轮审阅整改（第三批）：子表 case_id 索引
-- PostgreSQL 外键不自动建索引，而案件详情页每次要按 case_id 扫十余张子表（最高频路径）

create index if not exists idx_officer_case      on case_officer (case_id);
create index if not exists idx_evidence_case     on case_evidence (case_id);
create index if not exists idx_document_case     on case_document (case_id);
create index if not exists idx_exclusion_case    on case_period_exclusion (case_id);
create index if not exists idx_notice_case       on case_notice (case_id);
create index if not exists idx_review_case       on case_review (case_id);
create index if not exists idx_meeting_case      on case_meeting (case_id);
create index if not exists idx_delivery_case     on case_delivery (case_id);
create index if not exists idx_execution_case    on case_execution (case_id);
create index if not exists idx_attachment_case   on case_attachment (case_id);
create index if not exists idx_signature_case    on doc_signature (case_id);
create index if not exists idx_signature_doc     on doc_signature (document_id);
create index if not exists idx_approval_case     on biz_approval (case_id);
create index if not exists idx_installment_case  on case_installment (case_id);
create index if not exists idx_expert_case       on expert_review (case_id);
create index if not exists idx_hearing_case      on case_hearing (case_id);
create index if not exists idx_assist_case       on case_assist (case_id);
create index if not exists idx_agreement_case    on agreement_action (case_id);

-- 数据范围过滤走 owner_user；反馈按提交人查"我的反馈"
create index if not exists idx_case_owner        on case_file (owner_user);
create index if not exists idx_feedback_user     on feedback (username, id desc);
