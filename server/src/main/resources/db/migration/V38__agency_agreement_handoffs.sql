alter table agreement_action
    add column agency_dept_id bigint references sys_dept(id),
    add column agency_replied_by bigint references sys_user(id);

create index idx_agreement_agency_dept on agreement_action (agency_dept_id, sent_at desc, id desc)
    where agency_dept_id is not null;

insert into sys_role (name, code, remark)
values ('经办协议处理', 'AGENCY', '查看及回复分派给本部门的协议处理事项');

with menu_dir as (
    insert into sys_menu (name, type, path, icon, sort_no)
    values ('经办协同', 'DIR', '/agency', 'Files', 1)
    returning id
), menu_item as (
    insert into sys_menu (parent_id, name, type, path, perm, icon, sort_no)
    select id, '协议处理待办', 'MENU', '/agency/agreements', 'agency:agreement:reply', 'Document', 1
    from menu_dir
    returning id
)
insert into sys_role_menu (role_id, menu_id)
select r.id, m.id
from sys_role r cross join (select id from menu_dir union all select id from menu_item) m
where r.code = 'AGENCY';
