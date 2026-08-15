-- 账号实体加乐观锁列：防止"停用"与"改密"并发时读-改-写互相覆盖
-- （例如管理员停用账号的同一时刻用户在改密，改密的全字段 save 会把 enabled 覆盖回 true）
alter table sys_user add column row_version bigint not null default 0;
