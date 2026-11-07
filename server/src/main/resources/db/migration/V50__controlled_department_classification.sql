-- Classify legacy directory rows only through an explicit administrator operation.
-- No production department, account, business source, or credential is backfilled.
create table dept_classification_history (
 id bigserial primary key,
 dept_id bigint not null references sys_dept(id),
 org_kind varchar(16) not null check(org_kind in ('BUREAU','DISTRICT','AGENCY')),
 actor varchar(64) not null,
 reason varchar(1000) not null,
 source_reference varchar(255) not null,
 preview_fingerprint varchar(64) not null,
 account_snapshot jsonb not null,
 created_at timestamptz not null default current_timestamp,
 unique(dept_id)
);

insert into sys_menu(parent_id,name,path,icon,sort_no,type)
select 10,'对账与接口','/system/integrations','Connection',85,'MENU'
where not exists(select 1 from sys_menu where path='/system/integrations');
insert into sys_role_menu(role_id,menu_id)
select r.id,m.id from sys_role r cross join sys_menu m
where r.code in ('HANDLER','LEGAL','LEADER','ADMIN') and m.path='/system/integrations'
on conflict do nothing;
