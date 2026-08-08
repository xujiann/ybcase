-- 第三轮审阅整改（第一批）：令牌即时失效、重新告知留痕、陈述申辩放弃留痕

-- 令牌版本：改密/停用后自增，旧令牌立即失效（此前旧令牌最长可再用 12 小时）
alter table sys_user add column token_version int not null default 0;

-- 辽52条：改变原认定事实/证据/依据须重新告知——加重再告知须载明变更理由（防止绕过"不得因申辩加重"）
alter table case_notice add column change_reason varchar(500);

-- 当事人明确放弃陈述申辩的留痕（放弃后方可在期限届满前决定）
alter table case_notice add column statement_waived boolean not null default false;

insert into sys_config (cfg_key, cfg_value, remark) values
    ('statement_wait_required', 'true', '陈述申辩期届满前不得作出处罚决定（当事人已陈述申辩或明确放弃的除外）'),
    ('exclusion_max_days', '180', '单次期限扣除最长天数：防止以"不计入期限"变相无限延长办案期限');

-- 死参数清理：代码实际读 hearing_threshold_individual/hearing_threshold_org（V3），此键无任何读取
delete from sys_config where cfg_key = 'hearing_fine_threshold';
