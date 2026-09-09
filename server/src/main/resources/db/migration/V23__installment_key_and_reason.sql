-- 第十一轮审阅整改

-- ① V22 给 case_installment 加了 kind，却没把唯一键 (case_id, seq) 一并放宽。
--    同一案件既判罚款又判退回基金时，两类款项的分期计划都要从第 1 期排起，
--    第二类的 seq=1 会撞上第一类的 seq=1 → 建不出来（2101 该记录已存在）。
alter table case_installment drop constraint if exists case_installment_case_id_seq_key;
create unique index if not exists uq_installment_case_kind_seq
    on case_installment (case_id, kind, seq);

-- ② 线索期限扣除把历次事由累加进 extend_reason，而该列是 varchar(255)：
--    同一条线索扣除三五次就会溢出，整笔更新回滚，办案人看到的是一个无从理解的 2101。
--    同表 content/verify_result 本就是 text，此处对齐。
alter table case_clue alter column extend_reason type text;

-- ③ 拟没收违法所得：告知阶段此前只记拟罚款与拟退回基金，
--    而"数额较大"的三道门槛（听证告知/法制审核/集体讨论）都只按罚款额比对，
--    于是"罚款填 0 + 巨额没收"可同时跳过三道门槛。
alter table case_notice add column if not exists proposed_confiscate numeric(14,2) not null default 0;

insert into sys_config (cfg_key, cfg_value, remark) values
    ('threshold_include_confiscate', 'true',
     '"数额较大"阈值是否计入没收违法所得（听证告知/法制审核/集体讨论三处同口径）。没收违法所得与罚款同属行政处罚种类，默认计入；如局方另有口径可置 false')
on conflict (cfg_key) do nothing;
