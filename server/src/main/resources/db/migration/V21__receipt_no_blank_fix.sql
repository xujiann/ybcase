-- 第七轮：财政票据号空串修正
-- V20 的 uq_execution_receipt 谓词只排除 null，而前端执行登记表单默认发 receiptNo:""，
-- 空串被当成一个有效票据号 → 全库第二条"不填票据号"的执行记录即撞唯一索引，
-- 罚没款入不了账，案件连带无法结案。归一为 null 并把空白一并排除在唯一性之外。

update case_execution set receipt_no = null
where receipt_no is not null and btrim(receipt_no) = '';

drop index if exists uq_execution_receipt;
create unique index uq_execution_receipt
    on case_execution (receipt_no)
    where receipt_no is not null and btrim(receipt_no) <> '';
