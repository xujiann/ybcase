-- 四期：执法事项扩域（行政检查/举报奖励）+ 裁量基准 + 执行深化（分期/票据）+ 专家评审

-- 行政检查（清单第10-12项：检查→发现违法→一键转线索闭环）
create table inspection (
    id          bigserial primary key,
    insp_no     varchar(32)  not null unique,          -- JC20260001
    item_id     bigint references law_enforce_item (id),
    object_name varchar(128) not null,
    object_type varchar(16)  not null,                 -- 同当事人类别
    officers    varchar(255) not null,                 -- 检查人员（≥2人）
    planned_at  date         not null,
    done_at     date,
    result      text,
    violation_found boolean  not null default false,
    clue_id     bigint references case_clue (id),      -- 转线索后回填
    created_by  varchar(64),
    created_at  timestamptz  not null default now()
);

-- 举报奖励（清单第13项，条例第35条）
create table reward (
    id           bigserial primary key,
    clue_id      bigint not null unique references case_clue (id),
    reporter_name    varchar(64)  not null,
    reporter_contact varchar(64),
    verified     boolean,                -- 查实结论
    amount       numeric(12,2),
    approved_by  varchar(64),
    approved_at  date,
    paid_at      date,
    note         varchar(255)
);

-- 裁量基准（按案由种类分阶次；实施期以局方正式裁量基准文件替换种子）
create table discretion_rule (
    id             bigserial primary key,
    cause_category varchar(64) not null,   -- 对应 case_cause.category
    tier           varchar(16) not null,   -- LIGHT 从轻 / NORMAL 一般 / HEAVY 从重
    condition_desc varchar(512) not null,
    multiplier_min numeric(6,2) not null,  -- 罚款倍数下限（×涉案金额）
    multiplier_max numeric(6,2) not null,
    note           varchar(255)
);

-- 分期缴纳计划（第54条批准暂缓/分期后）
create table case_installment (
    id      bigserial primary key,
    case_id bigint not null references case_file (id),
    seq     int    not null,
    due_at  date   not null,
    amount  numeric(14,2) not null,
    paid_at date,
    unique (case_id, seq)
);

-- 专家评审（第25条；评审期间不计入办案期限）
create table expert_review (
    id         bigserial primary key,
    case_id    bigint not null references case_file (id),
    experts    varchar(255) not null,
    started_at date not null,
    ended_at   date,
    opinion    text
);

-- 执行票据登记（第52条：财政统一票据）
alter table case_execution add column receipt_no varchar(64);

-- 参数
insert into sys_config (cfg_key, cfg_value, remark) values
    ('discretion_reason_required', 'true', '普通程序处罚决定须填写裁量理由（辽44条：说明裁量考虑因素）');

-- 菜单
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (18, 10, '行政检查', 'MENU', '/case/inspections', 'case:insp:list', 'View', 8),
    (19, 10, '举报奖励', 'MENU', '/case/rewards', 'case:reward:list', 'Present', 9);
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r cross join sys_menu m
where m.id in (18, 19) and r.code in ('ADMIN', 'HANDLER', 'LEADER');

-- 种子：裁量基准（示例——条例第38条 1-2倍 / 第40条与社保法87·88条 2-5倍，验收前换局方正式基准）
insert into discretion_rule (cause_category, tier, condition_desc, multiplier_min, multiplier_max, note) values
('违反医疗保障基金使用规定', 'LIGHT',  '初次违法、主动退回全部损失、积极配合调查', 1.00, 1.20, '条例38条：损失金额1-2倍'),
('违反医疗保障基金使用规定', 'NORMAL', '一般违法情形', 1.20, 1.60, '条例38条'),
('违反医疗保障基金使用规定', 'HEAVY',  '拒不配合调查、两年内再犯、涉及金额巨大或造成严重后果', 1.60, 2.00, '条例38条'),
('骗取医疗保障基金支出', 'LIGHT',  '主动退回全部骗取金额、危害较小', 2.00, 2.50, '条例40条/社保法87条：骗取金额2-5倍'),
('骗取医疗保障基金支出', 'NORMAL', '一般欺诈骗保情形', 2.50, 4.00, '条例40条/社保法87条'),
('骗取医疗保障基金支出', 'HEAVY',  '组织化骗保、金额巨大、拒不退回或两年内再犯', 4.00, 5.00, '条例40条/社保法87条'),
('骗取医疗保障待遇', 'NORMAL', '一般骗取待遇情形', 2.00, 5.00, '社保法88条：骗取金额2-5倍'),
('骗取社会救助资金（物资或者服务）', 'NORMAL', '一般骗取救助情形', 1.00, 3.00, '社会救助暂行办法68条：1-3倍');
