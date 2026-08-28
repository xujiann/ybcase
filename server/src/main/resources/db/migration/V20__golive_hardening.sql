-- 上线前加固（第六轮审查）

-- ① 附件外置目录：仅更新说明，不写死路径。
--    容器内相对值 './data/attachments' 会以工作目录解析（compose 已补 working_dir: /app 使其落在挂载卷上）；
--    裸机/其它编排部署时须按实际情况改为绝对路径，否则切 FILE 模式后附件可能落在非持久化位置，
--    升级重建容器即丢失执法音像证据，且备份打包到的是空目录。
update sys_config
set remark = '附件外置存储目录（FILE 模式）。容器部署须确保该目录落在挂载卷上'
             || '（compose 已设 working_dir=/app，相对值解析为 /app/data/attachments）；'
             || '裸机部署请改为绝对路径。切 FILE 前务必确认，否则容器重建即丢失附件。'
where cfg_key = 'attachment_dir';

-- ② 财政票据号唯一：同一票据号被重复登记会让 sum(amount) 虚高，
--    进而把未足额缴纳的案件判定为"执行完毕"并结案（第56条）。
--    仅对非空票据号生效（非当场收缴可不填）。
create unique index if not exists uq_execution_receipt
    on case_execution (receipt_no) where receipt_no is not null;
