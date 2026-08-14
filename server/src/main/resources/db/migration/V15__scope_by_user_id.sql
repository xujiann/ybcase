-- 数据范围关联键由"姓名相同"改为账号 ID
-- 原实现 case_officer.name = sys_user.real_name：同名执法人员互相可见对方案件，
-- 且管理员改一次姓名就能让 case_view_scope=SELF 的隔离失效。

-- 执法证台账 ↔ 系统账号的权威映射（一名执法人员可能没有账号，故可空）
alter table enforcer add column user_id bigint references sys_user (id);

-- 办案人员登记时固化账号（证号→台账→账号解析），历史行按姓名回填
alter table case_officer add column user_id bigint references sys_user (id);

-- 回填：仅当姓名在 sys_user 中唯一时才认，重名一律留空（宁可少给权限也不错给）
update enforcer e set user_id = su.id
from sys_user su
where su.real_name = e.name
  and e.user_id is null
  and (select count(*) from sys_user x where x.real_name = e.name) = 1;

update case_officer o set user_id = e.user_id
from enforcer e
where e.cert_no = o.cert_no and e.user_id is not null and o.user_id is null;

update case_officer o set user_id = su.id
from sys_user su
where su.real_name = o.name
  and o.user_id is null
  and (select count(*) from sys_user x where x.real_name = o.name) = 1;

create index if not exists idx_officer_user on case_officer (user_id);
create index if not exists idx_enforcer_user on enforcer (user_id);
