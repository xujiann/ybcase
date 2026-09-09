-- 督办看板每类预警的显示上界。代码里有默认值 100，但不落 sys_config 的话
-- 管理员在参数页看不到、也就无从调整。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('supervision_top_n', '100',
     '督办看板每类预警最多显示的条数（各查询按紧急度排序，截断保留最紧急的）。'
     '此前无上界：真实数据量下一次返回六千余行、前端要渲染四万多个 DOM 节点')
on conflict (cfg_key) do nothing;
