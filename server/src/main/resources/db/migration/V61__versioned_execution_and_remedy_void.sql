alter table case_continuation_request drop constraint case_continuation_request_request_type_check;
alter table case_continuation_request add constraint case_continuation_request_request_type_check
    check (request_type in ('EXECUTION','REVERSAL','REFUND','COURT_APPLY','COURT_RECEIPT','REMEDY_RESULT','FACT_CORRECTION','REMEDY_VOID'));

create view case_remedy_status as
select m.*,
    case when r.request_type = 'REMEDY_VOID' then 'VOIDED'
         when r.id is not null then 'COMPLETED'
         else 'OPEN' end as status
from case_remedy m
left join lateral (
    select a.id,a.request_type from case_continuation_request a
    where a.case_id=m.case_id and a.status='APPROVED'
      and a.request_type in ('REMEDY_RESULT','REMEDY_VOID') and (a.payload->>'remedyId')::bigint=m.id
    order by a.id desc limit 1
) r on true;

alter table case_decision_version add constraint uq_case_decision_version_case unique (id,case_id);
alter table case_court_round add column decision_version_id bigint;
alter table case_court_round add constraint fk_court_round_decision_version
    foreign key (decision_version_id,case_id) references case_decision_version(id,case_id);

alter table case_supplement_delivery add column announced_at date;
alter table case_supplement_delivery add constraint ck_supplement_announced_delivery
    check (announced_at is null or (method='ANNOUNCE' and delivered_at>=announced_at));

create table case_version_execution_basis (
    id bigserial primary key,
    case_id bigint not null references case_file(id),
    decision_version_id bigint not null,
    delivery_id bigint not null references case_supplement_delivery(id),
    relief_deadline date not null,
    relief_pending boolean not null,
    basis varchar(2000) not null,
    defer_until date,
    recorded_by varchar(64) not null,
    recorded_at timestamptz not null default clock_timestamp(),
    foreign key (decision_version_id,case_id) references case_decision_version(id,case_id)
);
create index idx_version_execution_basis on case_version_execution_basis(decision_version_id,id desc);
create trigger preserve_version_execution_basis before update or delete on case_version_execution_basis
    for each row execute function preserve_case_continuation_history();
