-- v1.1：文书正式化/电子签章/审批留痕单据化/送达泛化/附件外置与音像分类/协议处理台账/行刑衔接

-- 文书模板版本历史（实施改模板可追溯回滚）
create table doc_template_history (
    id          bigserial primary key,
    doc_type    varchar(32)  not null,
    title_tpl   varchar(255) not null,
    content_tpl text         not null,
    saved_by    varchar(64),
    saved_at    timestamptz  not null default now()
);

-- 电子签章记录（SPI：Mock 实现为 SHA-256 摘要签章，实施期替换 CA 提供方）
create table doc_signature (
    id           bigserial primary key,
    case_id      bigint      not null references case_file (id),
    document_id  bigint      not null references case_document (id),
    signer       varchar(64) not null,
    signer_role  varchar(32),            -- OFFICER 办案人员/PARTY 当事人/LEADER 负责人/ORG 机关签章
    content_hash varchar(128) not null,  -- 签章时文书内容摘要（防篡改校验基准）
    signature    varchar(512) not null,  -- 签章值（Mock=摘要；CA=P7/PDF签名引用）
    provider     varchar(32)  not null,  -- MOCK / 实施期CA提供方标识
    signed_at    timestamptz  not null default now()
);

-- 审批留痕单据（第17条立案审批表；延期/中止/终止/暂缓的"申请→负责人批准"两步留痕）
create table biz_approval (
    id         bigserial primary key,
    kind       varchar(16)  not null,  -- FILE_CASE 立案/EXTEND 延期/SUSPEND 中止/TERMINATE 终止/DEFER 暂缓分期
    clue_id    bigint references case_clue (id),
    case_id    bigint references case_file (id),
    payload    text,                   -- 申请参数（JSON：立案请求全文/延期天数等）
    reason     varchar(512) not null,  -- 申请理由
    applicant  varchar(64)  not null,
    applied_at timestamptz  not null default now(),
    status     varchar(16)  not null default 'PENDING',  -- PENDING/APPROVED/REJECTED
    approver   varchar(64),
    decided_at timestamptz,
    opinion    varchar(512)
);
create index idx_approval_status on biz_approval (status, id desc);

-- 送达泛化：每份对外文书均可登记送达与回证（决定书送达仍驱动案件状态）
alter table case_delivery add column doc_kind varchar(24) not null default 'DECISION';
alter table case_delivery add column document_id bigint references case_document (id);

-- 附件：音像/扫描分类 + 外置文件存储（bytea 可空，二选一）
alter table case_attachment add column category varchar(16) not null default 'DOC_SCAN';  -- DOC_SCAN 扫描件/AV_RECORD 执法音像/OTHER
alter table case_attachment add column file_path varchar(512);
alter table case_attachment alter column data drop not null;

-- 协议处理台账（行政处罚 ↔ 经办协议处理联动：约谈/暂停协议/解除协议/拒付/追回）
create table agreement_action (
    id         bigserial primary key,
    case_id    bigint       not null references case_file (id),
    action     varchar(24)  not null,  -- INTERVIEW/SUSPEND_AGREEMENT/TERMINATE_AGREEMENT/REFUSE_PAY/RECOVER
    org        varchar(128) not null,  -- 移交的经办机构
    sent_at    date         not null,
    replied_at date,
    result     text,
    note       varchar(255)
);

-- 行刑衔接：移送回执
alter table case_transfer add column receipt_no varchar(64);   -- 受案回执文号
alter table case_transfer add column receipt_at date;

-- 参数
insert into sys_config (cfg_key, cfg_value, remark) values
    ('attachment_storage', 'DB',   '附件存储 DB 库内(bytea) / FILE 外置文件目录（大音像件建议 FILE）'),
    ('attachment_dir', './data/attachments', 'FILE 模式存储目录（生产挂独立盘/对象存储网关）'),
    ('monitor_feed_enabled', 'false', '智能监控疑点定时拉取开关（MonitorFeedAdapter SPI，Mock 返回空）');

-- 菜单：待办审批（负责人）+ 文书模板（管理员）
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (21, 10, '待办审批', 'MENU', '/case/approvals', 'case:approval:list', 'Stamp', 11),
    (8,  1,  '文书模板', 'MENU', '/system/templates', 'sys:template:list', 'Document', 7);
insert into sys_role_menu (role_id, menu_id) select r.id, 21 from sys_role r where r.code in ('ADMIN','LEADER','HANDLER');
insert into sys_role_menu (role_id, menu_id) select r.id, 8 from sys_role r where r.code = 'ADMIN';

-- 行刑衔接文书模板
insert into doc_template (doc_type, title_tpl, content_tpl) values
('TRANSFER_LETTER', '涉嫌犯罪案件移送书（{{caseNo}}）',
'（受移送机关）：

依据《行政执法机关移送涉嫌犯罪案件的规定》，现将{{caseName}}（案号{{caseNo}}）移送你局审查。当事人：{{partyName}}，{{partyTypeText}}。

移送材料：案件调查终结报告、涉案证据材料清单、涉案金额认定材料等（详见附件清单）。

请依法审查并将受理情况回复我局。

{{orgName}}
{{today}}'),
('ACCEPT_RECEIPT', '移送案件受理回执登记（{{caseNo}}）',
'受移送机关受案回执登记：
案件：{{caseName}}（{{caseNo}}）
回执文号：（收到后填写）
受理日期：（收到后填写）
经办人与联系方式：（收到后填写）');
