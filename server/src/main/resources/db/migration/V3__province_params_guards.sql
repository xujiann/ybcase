-- 二期A：省域参数化与新守卫（辽宁两文件对照 21 项差异，见 系统开发计划.md 第七章）
-- 原则：默认值=新《行政处罚法》+国家局令第4号；辽宁值可切换（参数留痕，法律口径由局方确认）

-- 期限/限额/阈值全量参数化
insert into sys_config (cfg_key, cfg_value, remark) values
    ('clue_verify_days',        '15',       '线索核查期限天数（国家15工作日/辽宁15自然日）'),
    ('clue_verify_day_unit',    'WORKDAY',  '线索核查期限单位 WORKDAY 工作日 / NATURAL 自然日'),
    ('clue_extend_days',        '15',       '线索核查延期天数（限一次）'),
    ('legal_review_mode',       'THRESHOLD','法制审核范围 THRESHOLD 达标才审（国家局令37条）/ ALL 全案必审（辽40条）'),
    ('legal_review_days',       '10',       '法制审核期限天数（国家10工作日/辽宁7日）'),
    ('legal_review_day_unit',   'WORKDAY',  '法制审核期限单位 WORKDAY / NATURAL'),
    ('statement_deadline_days', '3',        '陈述申辩期限（自告知起N日，逾期视为放弃，辽44条；0=不启用）'),
    ('hearing_threshold_individual', '100000', '听证告知阈值-自然人（辽46条为1000）'),
    ('hearing_threshold_org',        '100000', '听证告知阈值-单位（辽46条为10000）'),
    ('hearing_request_days',    '3',        '听证申请期限（自告知起N日，辽46条）'),
    ('meeting_required_fine_individual', '10000',  '较大数额罚款-自然人（达标必须集体讨论，辽54条）'),
    ('meeting_required_fine_org',        '100000', '较大数额罚款-单位（达标必须集体讨论，辽54条）'),
    ('summary_fine_limit_individual', '200',  '简易程序罚款限额-公民（新处罚法200/辽宁旧口径50）'),
    ('summary_fine_limit_org',        '3000', '简易程序罚款限额-单位（新处罚法3000/辽宁旧口径1000）'),
    ('onsite_collect_limit',    '100',      '当场收缴罚款限额（国家100/辽宁20）'),
    ('extension_first_max',     '30',       '办案期限首次延长上限（日）'),
    ('extension_total_max',     '90',       '办案期限累计延长上限（日，国家30+60；辽宁集体讨论定合理期限可调大）'),
    ('decision_publish_days',   '7',        '处罚决定作出后公开期限（日，辽56条）'),
    ('delivery_electronic_decision_allowed', 'true', '处罚决定书是否允许电子送达（国家局令59条允许须确认书/辽61条禁止）'),
    ('cross_exam_required',     'false',    '决定前证据须经当事人质证（辽24条：未经当事人发表意见的证据不能作为决定依据）'),
    ('case_deadline_days',      '90',       '普通程序办案期限（立案起N日）'),
    ('evidence_hold_days',      '7',        '先行登记保存处理期限天数（国家7工作日/辽宁7自然日）'),
    ('evidence_hold_day_unit',  'WORKDAY',  '先行登记保存期限单位 WORKDAY / NATURAL');

-- 菜单：参数设置（ADMIN）
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (5, 1, '参数设置', 'MENU', '/system/settings', 'sys:config:list', 'Tools', 4);
insert into sys_role_menu (role_id, menu_id)
select r.id, 5 from sys_role r where r.code = 'ADMIN';

-- 证据质证（辽24条）
alter table case_evidence add column cross_exam_opinion text;
alter table case_evidence add column cross_exam_at date;

-- 陈述申辩期限（辽44条）
alter table case_notice add column statement_deadline date;

-- 决定：公开时限/从轻减轻/裁量理由/政府备案（辽54-56条）
alter table case_decision add column published_at date;
alter table case_decision add column mitigation varchar(512);
alter table case_decision add column discretion_reason text;
alter table case_decision add column gov_record_no varchar(64);
alter table case_decision add column gov_record_at date;

-- 线索核查期限扣除（辽15条：鉴定检验时间不计入）
alter table case_clue add column excluded_days int not null default 0;

-- 送达回证（辽58条：必须有送达回证，签收日期为送达日期）
alter table case_delivery add column receipt_no varchar(64);
alter table case_delivery add column receipt_signed_at date;
