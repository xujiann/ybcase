-- 线索列表按状态筛选（findTop200ByStatusOrderByIdDesc）的支撑索引：
-- 线索是增长最快的业务表之一，终态（FILED/REJECTED/TRANSFERRED）只增不减
create index if not exists idx_clue_status_id on case_clue (status, id desc);
