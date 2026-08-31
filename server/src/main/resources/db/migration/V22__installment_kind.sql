-- 第九轮：分期计划缺款项类型
-- payInstallment 一律以 kind='FINE' 入账（第七轮补的自动入账写死了类型），
-- 于是"只责令退回基金、不罚款"这类案件（医保案常见）分期缴清后：
--   sum(FINE) 虚增、sum(RECOUP) 恒为 0 → fullyExecuted 永远不成立 → 案件无法结案，
--   新增的 paymentOverdue/courtEnforceExpiring 预警也永不消退，最终误报"已失权"。
-- 且该 insert 绕过 ExecutionService 的"累计不超决定额""该类款项须已决定"两道校验。
alter table case_installment add column if not exists kind varchar(16) not null default 'FINE';
comment on column case_installment.kind is '分期款项类型：FINE 罚款 / RECOUP 退回基金 / CONFISCATE 没收违法所得';
