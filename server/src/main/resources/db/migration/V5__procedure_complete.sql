-- 三期：程序完备（听证子流程/简易备案/移送/协查/当事人回避/电子送达确认/执法证台账）

-- 听证（辽46-50条：公告、7日前通知、主持人与办案人员不同部门、6步骤、笔录、2日内听证意见）
create table case_hearing (
    id             bigserial primary key,
    case_id        bigint      not null references case_file (id),
    announced_at   date,                     -- 公开听证公告日期
    notice_sent_at date        not null,     -- 听证通知书送达日
    scheduled_at   date        not null,     -- 举行日期（须晚于通知≥7日）
    host           varchar(64) not null,     -- 主持人（不得为本案办案人员）
    host_dept      varchar(64),
    recorder       varchar(64),
    held_at        date,
    record         text,                     -- 听证笔录
    opinion        text,                     -- 听证意见（结束2日内）
    opinion_at     date,
    status         varchar(16) not null default 'SCHEDULED'  -- SCHEDULED/HELD/OPINION_DONE
);

-- 移送台账（第10-12条：无管辖权移送/受移送；行刑衔接移送司法）
create table case_transfer (
    id          bigserial primary key,
    clue_id     bigint references case_clue (id),
    case_id     bigint references case_file (id),
    direction   varchar(8)   not null,   -- OUT 移出 / IN 移入
    target_org  varchar(128) not null,   -- 移送对象/来函机关
    kind        varchar(16)  not null,   -- ADMIN 其他行政机关 / JUDICIAL 司法机关 / MSA 医保部门间
    reason      varchar(512) not null,
    sent_at     date         not null,
    confirmed_at date,                   -- 受移送方接收确认
    note        varchar(255)
);

-- 协查台账（第34条/辽12-14条：出具协助调查函，15日完成）
create table case_assist (
    id         bigserial primary key,
    case_id    bigint       not null references case_file (id),
    direction  varchar(8)   not null,   -- OUT 我方发出 / IN 我方受托
    org        varchar(128) not null,
    content    varchar(512) not null,
    sent_at    date         not null,
    due_at     date         not null,   -- 15日
    replied_at date,
    result     text,
    refused    boolean      not null default false,
    refuse_reason varchar(255)
);

-- 执法证台账（第16条：具备执法资格；辽41条：初次审核人员须法律职业资格）
create table enforcer (
    id            bigserial primary key,
    name          varchar(64) not null,
    cert_no       varchar(64) not null unique,
    dept          varchar(64),
    cert_expire_at date       not null,
    legal_qualified boolean   not null default false,  -- 法律职业资格（法制审核岗）
    enabled       boolean     not null default true
);

-- 办案人员回避：当事人申请（第5条分级批准）
alter table case_officer add column avoid_applicant varchar(16);  -- SELF 主动/PARTY 当事人申请
alter table case_officer add column avoid_decided_by varchar(64); -- 批准人

-- 简易程序备案（第51条：作出决定7个工作日内报所属部门备案）
alter table case_file add column summary_record_at date;

-- 电子送达确认书（第59条：当事人同意并签订确认书方可电子送达非决定书文书）
alter table case_file add column e_delivery_consent boolean not null default false;

-- 参数
insert into sys_config (cfg_key, cfg_value, remark) values
    ('hearing_notice_ahead_days', '7',  '听证通知须在举行前N日送达（辽47条）'),
    ('hearing_opinion_days',      '2',  '听证结束后N日内提出听证意见（辽50条）'),
    ('summary_record_days',       '7',  '简易程序决定后N个工作日内备案（第51条）'),
    ('assist_due_days',           '15', '协查完成期限（日，第34条）'),
    ('enforcer_cert_check',       'true', '立案时校验办案人员执法证在台账内且未过期');

-- 菜单：移送台账 + 执法证台账
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (17, 10, '移送台账', 'MENU', '/case/transfers', 'case:transfer:list', 'Promotion', 7),
    (7,  1,  '执法证台账', 'MENU', '/system/enforcers', 'sys:enforcer:list', 'Postcard', 6);
insert into sys_role_menu (role_id, menu_id) select r.id, 17 from sys_role r where r.code in ('ADMIN','HANDLER','LEADER');
insert into sys_role_menu (role_id, menu_id) select r.id, 7 from sys_role r where r.code = 'ADMIN';

-- 种子：执法证台账（演示人员）
insert into enforcer (name, cert_no, dept, cert_expire_at, legal_qualified) values
    ('王办案', 'YB001', '基金监督管理处', '2028-12-31', false),
    ('张协办', 'YB002', '基金监督管理处', '2028-12-31', false),
    ('李替补', 'YB003', '基金监督管理处', '2027-06-30', false),
    ('李法制', 'FZ001', '政策法规处',     '2028-12-31', true);
