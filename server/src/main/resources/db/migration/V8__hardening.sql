-- 审阅整改：编号序列表（消除 count+1 并发竞态）+ 口径参数

-- 业务编号序列（按类别+年度原子取号：insert on conflict do update returning）
create table biz_seq (
    kind varchar(16) not null,   -- CASE / DECISION / CLUE / INSP
    year int         not null,
    val  int         not null,
    primary key (kind, year)
);

-- 从存量数据初始化序列水位（已部署库升级时保证不撞号；全新库结果为空自然从1开始）
insert into biz_seq (kind, year, val)
select 'CLUE', y, cnt from (
    select cast(substring(clue_no from 3 for 4) as int) as y, count(*) as cnt
    from case_clue group by 1) t
on conflict do nothing;
insert into biz_seq (kind, year, val)
select 'CASE', extract(year from filed_at)::int, count(*) from case_file
group by 2 on conflict do nothing;
insert into biz_seq (kind, year, val)
select 'DECISION', extract(year from decided_at)::int, count(*) from case_decision
where decision_no is not null group by 2 on conflict do nothing;
insert into biz_seq (kind, year, val)
select 'INSP', cast(substring(insp_no from 3 for 4) as int), count(*) from inspection
group by 2 on conflict do nothing;

-- 口径参数（与局方法规部门确认后切换）
insert into sys_config (cfg_key, cfg_value, remark) values
    ('late_fee_base', 'FULL', '加处罚款计息基数 FULL 按罚款全额（处罚法字面）/ UNPAID 按未缴部分（实务口径）'),
    ('announce_deliver_days', '60', '公告送达视为送达天数（辽60条：公告之日起60日）'),
    ('review_legal_qualified_required', 'true', '法制审核人须在执法证台账内具备法律职业资格（辽41条）');
