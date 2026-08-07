-- v1.2：待办中心与消息提醒 + 案件数据级权限（归属/查看范围/移交）

-- 站内消息
create table sys_message (
    id         bigserial primary key,
    username   varchar(64)  not null,   -- 收件人（登录名）
    kind       varchar(16)  not null,   -- APPROVAL 审批/DEADLINE 期限提醒/SYSTEM
    title      varchar(255) not null,
    content    varchar(512),
    link       varchar(128),            -- 前端路由（点击跳转）
    dedup_key  varchar(128),            -- 提醒去重键（同一事项同一天只提醒一次）
    created_at timestamptz  not null default now(),
    read_at    timestamptz
);
create index idx_msg_user on sys_message (username, read_at, id desc);
create unique index idx_msg_dedup on sys_message (username, dedup_key) where dedup_key is not null;

-- 案件归属（数据级权限：承办人+承办部门；范围规则见 case_view_scope 参数）
alter table case_file add column owner_user varchar(64);
alter table case_file add column owner_dept_id bigint;
update case_file set owner_user = created_by where owner_user is null;

insert into sys_config (cfg_key, cfg_value, remark) values
    ('case_view_scope', 'ALL', '办案人员案件查看范围：ALL 全局 / SELF 仅本人承办或参办（负责人/法制/管理员始终全局）'),
    ('reminder_days_ahead', '5', '期限提醒提前天数（办案期限/线索核查临期即生成站内消息）');
