-- 五期：协同与上线（委托执法档案/集体讨论签名确认/审计/统计上报支撑）

-- 委托执法档案（局令第8条：委托书公布、不得转委托）
create table delegate_org (
    id           bigserial primary key,
    name         varchar(128) not null,
    agreement_no varchar(64)  not null,   -- 委托书文号
    scope        varchar(512) not null,   -- 委托的具体事项与权限
    start_at     date         not null,
    end_at       date         not null,
    published_at date,                    -- 委托书向社会公布日期
    note         varchar(255)
);

-- 集体讨论：参加人员签字确认（局令44条：讨论记录经参加讨论人员确认签字存入案卷）
alter table case_meeting add column sign_confirmed boolean not null default false;
alter table case_meeting add column sign_names varchar(255);

-- 菜单：委托档案
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (20, 10, '委托档案', 'MENU', '/case/delegates', 'case:delegate:list', 'Collection', 10);
insert into sys_role_menu (role_id, menu_id) select r.id, 20 from sys_role r where r.code in ('ADMIN','LEADER');
