-- Keep legacy records unlinked. A later notice must not silently adopt an older hearing.
alter table case_notice
    add column delivery_required boolean not null default false,
    add column statement_period_days integer check (statement_period_days >= 0),
    add column hearing_period_days integer check (hearing_period_days >= 0),
    add column hearing_request_deadline date,
    add column statement_received_at date,
    add column hearing_requested_at date,
    add constraint uq_case_notice_case_version unique (case_id, id);

alter table case_delivery
    add column notice_id bigint unique,
    add constraint fk_case_delivery_notice
        foreign key (case_id, notice_id) references case_notice (case_id, id),
    add constraint ck_case_delivery_notice_receipt
        check (notice_id is null or (doc_kind = 'NOTICE' and coalesce(btrim(receipt_no), '') <> ''));

alter table case_hearing
    add column notice_id bigint,
    add constraint fk_case_hearing_notice
        foreign key (case_id, notice_id) references case_notice (case_id, id);
create index idx_case_hearing_notice on case_hearing (case_id, notice_id);

create table case_notice_procedure_event (
    id bigserial primary key,
    case_id bigint not null,
    notice_id bigint not null,
    event_type varchar(24) not null check (event_type in ('HEARING_WITHDRAWAL', 'STATEMENT_WAIVER')),
    reference text not null check (btrim(reference) <> ''),
    recorded_by varchar(128),
    recorded_at timestamptz not null default clock_timestamp(),
    foreign key (case_id, notice_id) references case_notice (case_id, id)
);
create index idx_notice_procedure_event_notice on case_notice_procedure_event (notice_id, id);

insert into sys_config(cfg_key, cfg_value, remark)
values ('notice_delivery_required', 'false', '新版告知须登记实际送达回证（开启后也适用于旧版接口新增告知）')
on conflict (cfg_key) do nothing;
