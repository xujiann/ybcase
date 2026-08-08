-- 用户反馈直报（bug/需求）：10 秒提交 + 自动上下文 + request-id 定位闭环

create table feedback (
    id            bigserial primary key,
    kind          varchar(16)  not null,  -- BUG / FEATURE / QUESTION
    title         varchar(255) not null,
    content       text         not null,
    page_route    varchar(128),           -- 提交时所在页面（自动）
    case_ref      varchar(64),            -- 关联案号（详情页提交时自动）
    app_version   varchar(32),            -- 前端构建版本（自动）
    user_agent    varchar(255),           -- 浏览器（自动）
    request_id    varchar(64),            -- 最近一次失败请求的 X-Request-Id（自动，服务端日志可直查）
    recent_errors text,                   -- 最近 5 条 API 错误快照 JSON（自动）
    screenshot    bytea,                  -- 截图（Ctrl+V 粘贴）
    username      varchar(64)  not null,
    created_at    timestamptz  not null default now(),
    status        varchar(16)  not null default 'NEW',  -- NEW/PROCESSING/RESOLVED/REJECTED/CLOSED
    handler       varchar(64),
    reply         text,
    handled_at    timestamptz
);
create index idx_feedback_status on feedback (status, id desc);

-- 审计日志关联 request-id（用户反馈 ↔ 服务端记录对账的钥匙）
alter table sys_audit_log add column request_id varchar(64);

-- 菜单：反馈管理（管理员）
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (9, 1, '反馈管理', 'MENU', '/system/feedback', 'sys:feedback:list', 'ChatDotRound', 8);
insert into sys_role_menu (role_id, menu_id) select r.id, 9 from sys_role r where r.code = 'ADMIN';
