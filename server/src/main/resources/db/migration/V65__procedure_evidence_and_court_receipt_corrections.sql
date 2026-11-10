alter table case_continuation_request drop constraint case_continuation_request_request_type_check;
alter table case_continuation_request add constraint case_continuation_request_request_type_check
    check (request_type in ('EXECUTION','REVERSAL','REFUND','COURT_APPLY','COURT_RECEIPT','REMEDY_RESULT',
                            'FACT_CORRECTION','REMEDY_VOID','COURT_RECEIPT_CORRECTION'));

create table case_court_receipt_correction (
    id bigserial primary key,
    case_id bigint not null references case_file(id),
    request_id bigint not null unique,
    round_id bigint,
    status varchar(16) not null check (status in ('ACCEPTED','RETURNED','REJECTED')),
    received_at date not null,
    receipt_no varchar(128) not null check (btrim(receipt_no) <> ''),
    foreign key (round_id,case_id) references case_court_round(id,case_id),
    foreign key (request_id,case_id) references case_continuation_request(id,case_id)
);
create index idx_court_receipt_correction_round on case_court_receipt_correction(case_id,round_id,id desc);
create trigger preserve_court_receipt_correction before update or delete on case_court_receipt_correction
    for each row execute function preserve_case_continuation_history();

create function case_effective_court_receipt(p_day date)
returns table(case_id bigint, id bigint, round_no int, applied_at date, application_reference text,
    decision_version_id bigint, status text, received_at date, receipt_no text, correction_id bigint)
language sql stable as $$
    with rounds as (
        select r.case_id,r.id,r.round_no,r.applied_at,r.application_reference::text,
               r.decision_version_id,p.status::text,p.received_at,p.receipt_no::text
        from case_court_round r join case_continuation_request a on a.id=r.request_id and a.status='APPROVED'
        left join case_court_receipt p on p.round_id=r.id and p.received_at<=p_day
        where r.applied_at<=p_day
        union all
        select c.id,null::bigint,1,c.court_applied_at,null::text,null::bigint,
               case when c.court_received_at is null or c.court_received_at<=p_day then c.court_enforce_status end,
               case when c.court_received_at<=p_day then c.court_received_at end,
               case when c.court_received_at<=p_day then c.court_receipt_no end
        from case_file c where c.court_enforce_applied and (c.court_applied_at is null or c.court_applied_at<=p_day)
    )
    select r.case_id,r.id,r.round_no,r.applied_at,r.application_reference,r.decision_version_id,
           coalesce(v.status,r.status,'APPLIED'),coalesce(v.received_at,r.received_at),
           coalesce(v.receipt_no,r.receipt_no),v.id
    from rounds r
    left join lateral (
        select v.* from case_court_receipt_correction v
        join case_continuation_request a on a.id=v.request_id and a.status='APPROVED'
        where v.case_id=r.case_id and v.round_id is not distinct from r.id and v.received_at<=p_day
        order by v.id desc limit 1
    ) v on true
$$;

alter table case_document
    add column notice_id bigint unique,
    add constraint fk_case_document_notice foreign key (case_id,notice_id) references case_notice(case_id,id),
    add constraint ck_case_document_notice check (notice_id is null or doc_type='NOTICE');

alter table case_delivery
    add column hearing_notice_version_id bigint,
    add constraint uq_case_delivery_hearing_notice unique(case_id,hearing_notice_version_id,id),
    add constraint fk_case_delivery_hearing_notice_version
        foreign key (case_id,hearing_notice_version_id) references case_notice(case_id,id),
    add constraint ck_case_delivery_hearing_notice_receipt check (hearing_notice_version_id is null or
        (doc_kind='HEARING_NOTICE' and coalesce(btrim(receipt_no),'')<>''));
alter table case_hearing
    add column hearing_notice_delivery_id bigint unique,
    add constraint ck_case_hearing_notice_delivery_version
        check (hearing_notice_delivery_id is null or notice_id is not null),
    add constraint fk_case_hearing_notice_delivery
        foreign key (case_id,notice_id,hearing_notice_delivery_id)
        references case_delivery(case_id,hearing_notice_version_id,id);
