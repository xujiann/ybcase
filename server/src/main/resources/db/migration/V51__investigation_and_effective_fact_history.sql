-- Independent fact provenance never rewrites a decided case or a sealed volume.
create table case_fact_revision (
    id bigserial primary key,
    case_id bigint not null references case_file(id),
    revision_type varchar(40) not null check(revision_type in
      ('INVESTIGATION_RETURN','INVESTIGATION_REPORT','INSTITUTION_CORRECTION','APPROVED_RESULT','APPROVED_FACT_CORRECTION')),
    request_id bigint unique,
    review_id bigint references case_review(id),
    effective_at date not null,
    facts jsonb not null check(jsonb_typeof(facts)='object'),
    previous_facts jsonb not null check(jsonb_typeof(previous_facts)='object'),
    reason varchar(2000) not null,
    source_reference varchar(1000) not null,
    actor_id bigint not null references sys_user(id),
    actor_username varchar(64) not null,
    recorded_at timestamptz not null default clock_timestamp(),
    foreign key(request_id,case_id) references case_continuation_request(id,case_id)
);
create index idx_case_fact_revision on case_fact_revision(case_id,effective_at,id);
create trigger trg_case_fact_revision_immutable before update or delete on case_fact_revision
  for each row execute function preserve_case_continuation_history();

create table case_investigation_return (
    id bigserial primary key,
    case_id bigint not null references case_file(id),
    review_id bigint not null unique references case_review(id),
    previous_status varchar(24) not null,
    previous_reported_at date,
    prior_notice_id bigint references case_notice(id),
    reason varchar(2000) not null,
    source_reference varchar(1000) not null,
    actor_id bigint not null references sys_user(id),
    actor_username varchar(64) not null,
    created_at timestamptz not null default clock_timestamp()
);
create trigger trg_case_investigation_return_immutable before update or delete on case_investigation_return
  for each row execute function preserve_case_continuation_history();

alter table case_continuation_request drop constraint case_continuation_request_request_type_check;
alter table case_continuation_request add constraint case_continuation_request_request_type_check
 check(request_type in ('EXECUTION','REVERSAL','REFUND','COURT_APPLY','COURT_RECEIPT','REMEDY_RESULT','FACT_CORRECTION'));
