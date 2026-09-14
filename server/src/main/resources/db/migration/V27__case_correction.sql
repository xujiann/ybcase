-- 案件要素更正留痕：立案时录错的当事人要素/案由/摘要/金额此前无任何系统内改法，只能直连库改且不留痕，
-- 违背执法全过程记录（局令第4/35条）。每个变化字段一行，同一次更正共用 batch_no 便于分组展示。
create table if not exists case_correction (
    id          bigserial primary key,
    case_id     bigint      not null references case_file (id),
    batch_no    varchar(32) not null,
    field       varchar(32) not null,
    old_value   text,
    new_value   text,
    reason      text        not null,
    operator    varchar(64) not null,
    created_at  timestamptz not null default now()
);
create index if not exists idx_case_correction_case on case_correction (case_id);

-- 线索分页检索的受理日期区间过滤：V18 只有 (status, id desc)，received_at 无索引
create index if not exists idx_clue_received_at on case_clue (received_at desc);
