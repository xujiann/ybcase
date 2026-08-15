-- 纠正 V15 第三个 UPDATE 按姓名兜底可能造成的过度授权：
-- 只保留能被"执法证号 → 台账权威映射"验证的 case_officer.user_id，
-- 其余（当初靠"重名唯一"绑上、并非同一人的）一律清空，从严对齐 resolveOfficerUserId 的新口径。
update case_officer o set user_id = null
where user_id is not null
  and not exists (
      select 1 from enforcer e
      where e.cert_no = o.cert_no and e.user_id = o.user_id
  );
