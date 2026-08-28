-- 上线前加固（第六轮审查）

-- ① 附件外置目录改绝对路径：相对值 './data/attachments' 在容器内解析为 /data/attachments，
--    不在挂载卷（/app/data/attachments）上——切 FILE 模式后附件写入容器可写层，
--    升级 docker compose up --force-recreate 时随可写层一并丢弃，且备份打包的是空目录。
--    执法音像属法定全过程记录证据，丢失不可逆，故改为绝对路径。
update sys_config
set cfg_value = '/app/data/attachments',
    remark = '附件外置存储目录（FILE 模式）。必须是绝对路径且落在挂载卷上，'
             || '否则容器重建即丢失；容器化部署固定为 /app/data/attachments'
where cfg_key = 'attachment_dir'
  and cfg_value in ('./data/attachments', 'data/attachments');

-- ② 财政票据号唯一：同一票据号被重复登记会让 sum(amount) 虚高，
--    进而把未足额缴纳的案件判定为"执行完毕"并结案（第56条）。
--    仅对非空票据号生效（非当场收缴可不填）。
create unique index if not exists uq_execution_receipt
    on case_execution (receipt_no) where receipt_no is not null;
