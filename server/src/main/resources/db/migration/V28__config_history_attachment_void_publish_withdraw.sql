-- 参数变更留痕：sys_config 只存最新值，期限/阈值类参数改过之后"当时按几算的"无从回答（对照 doc_template_history）
create table sys_config_history (
    id         bigserial primary key,
    cfg_key    varchar(64) not null,
    old_value  text,
    new_value  text,
    changed_by varchar(64) not null,
    changed_at timestamptz not null default now()
);
create index idx_config_history_key on sys_config_history (cfg_key, changed_at desc);

-- 附件作废：误传件只打标不物理删除（执法留痕），列表默认过滤、下载受限
alter table case_attachment add column voided      boolean not null default false;
alter table case_attachment add column void_reason text;
alter table case_attachment add column voided_by   varchar(64);
alter table case_attachment add column voided_at   timestamptz;

-- 处罚决定公开撤回（行政处罚法第48条第2款：被撤销/变更/确认违法的应三日内撤回公开并说明理由）
-- 不回改 published：保留"曾公开"事实与 7 日内公开的超期证据
alter table case_decision add column publish_withdrawn boolean not null default false;
alter table case_decision add column withdrawn_at      timestamptz;
alter table case_decision add column withdraw_reason   text;
alter table case_decision add column withdrawn_by      varchar(64);
