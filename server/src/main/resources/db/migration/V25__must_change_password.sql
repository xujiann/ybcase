-- 首次登录强制改密：由管理员设定初始口令的账号（含初始化器建的 admin），
-- 首次登录必须改成本人口令，否则初始口令会长期存在于管理员的口头/纸面传递链里。
alter table sys_user add column if not exists must_change_password boolean not null default false;
