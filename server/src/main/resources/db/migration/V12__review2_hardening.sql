-- 第二轮审阅整改：签章操作人留痕 + 反馈自动关闭参数

-- 签章记录补"实际操作人"（signer 是署名主体，operator 是点击签章的登录用户——CA 接入前的操作证据链）
alter table doc_signature add column operator varchar(64);

insert into sys_config (cfg_key, cfg_value, remark) values
    ('feedback_autoclose_days', '7', '反馈"已解决"后 N 天提交人未确认则自动关闭（0=立即，配合提醒任务执行）');
