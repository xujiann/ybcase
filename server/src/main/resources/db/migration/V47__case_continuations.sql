-- Post-decision work never rewrites a legal case, decision, document or archived volume.
create table case_continuation_request (
    id bigserial primary key,
    case_id bigint not null references case_file(id),
    request_key varchar(64) not null,
    request_type varchar(24) not null check (request_type in ('EXECUTION','REVERSAL','REFUND','COURT_APPLY','COURT_RECEIPT','REMEDY_RESULT')),
    payload jsonb not null,
    source_snapshot jsonb not null,
    reason varchar(1000) not null,
    source_reference varchar(1000) not null,
    requested_by bigint not null references sys_user(id),
    requested_username varchar(64) not null,
    requester_dept_id bigint references sys_dept(id),
    requester_category varchar(16),
    requested_at timestamptz not null default now(),
    status varchar(16) not null default 'PENDING' check (status in ('PENDING','APPROVED','REJECTED','CANCELLED')),
    reviewed_by bigint references sys_user(id),
    reviewed_username varchar(64),
    reviewed_at timestamptz,
    opinion varchar(2000),
    unique(case_id,request_key),
    unique(id,case_id),
    check ((status='PENDING' and num_nonnulls(reviewed_by,reviewed_username,reviewed_at,opinion)=0)
      or (status<>'PENDING' and num_nonnulls(reviewed_by,reviewed_username,reviewed_at,opinion)=4)),
    check (status<>'APPROVED' or requested_by<>reviewed_by)
);
create unique index uq_case_continuation_pending on case_continuation_request(case_id) where status='PENDING';
create index idx_case_continuation_history on case_continuation_request(case_id,id desc);

create table case_remedy (
    id bigserial primary key,
    case_id bigint not null references case_file(id),
    request_key varchar(64) not null,
    remedy_type varchar(24) not null check (remedy_type in ('RECONSIDERATION','LITIGATION','CORRECTION')),
    accepted_at date not null,
    reference varchar(128) not null,
    reason varchar(1000) not null,
    source_reference varchar(1000) not null,
    registered_by bigint not null references sys_user(id),
    registered_username varchar(64) not null,
    registered_at timestamptz not null default now(),
    unique(case_id,request_key),
    unique(id,case_id)
);

create table case_decision_version (
    id bigserial primary key,
    case_id bigint not null references case_file(id),
    request_id bigint not null unique,
    remedy_id bigint not null references case_remedy(id),
    outcome varchar(16) not null check (outcome in ('CORRECT','CHANGE','REVOKE')),
    effective_at date not null,
    decision_type varchar(24) not null check (decision_type in ('PUNISH','NO_PUNISH','NOT_ESTABLISHED','TRANSFER_ADMIN','TRANSFER_JUDICIAL','REVOKED')),
    decision_no varchar(128) not null,
    fine_amount numeric(14,2) not null check (fine_amount>=0),
    recoup_amount numeric(14,2) not null check (recoup_amount>=0),
    confiscate_amount numeric(14,2) not null check (confiscate_amount>=0),
    penalty_basis text not null,
    content text not null,
    other_measures varchar(512),
    foreign key(request_id,case_id) references case_continuation_request(id,case_id),
    check (decision_type='PUNISH' or (fine_amount=0 and confiscate_amount=0)),
    check ((outcome='REVOKE')=(decision_type='REVOKED')),
    check (decision_type<>'REVOKED' or recoup_amount=0)
);
create index idx_case_decision_version on case_decision_version(case_id,effective_at desc,id desc);

create table case_execution_continuation (
    id bigserial primary key,
    case_id bigint not null references case_file(id),
    request_id bigint not null unique,
    kind varchar(16) not null check (kind in ('FINE','RECOUP','CONFISCATE','LATE_FEE')),
    amount numeric(14,2) not null check (amount<>0),
    paid_at date not null,
    receipt_no varchar(128) not null unique,
    method varchar(16) not null check (method in ('BANK','ONSITE','INSTALLMENT','REVERSAL','REFUND')),
    note varchar(1000) not null,
    reverses_execution_id bigint references case_execution(id),
    reverses_entry_id bigint references case_execution_continuation(id),
    refunds_execution_id bigint references case_execution(id),
    refunds_entry_id bigint references case_execution_continuation(id),
    foreign key(request_id,case_id) references case_continuation_request(id,case_id),
    check ((amount>0 and method not in ('REVERSAL','REFUND') and num_nonnulls(reverses_execution_id,reverses_entry_id,refunds_execution_id,refunds_entry_id)=0)
       or (amount<0 and method='REVERSAL' and num_nonnulls(reverses_execution_id,reverses_entry_id)=1 and num_nonnulls(refunds_execution_id,refunds_entry_id)=0)
       or (amount<0 and method='REFUND' and num_nonnulls(refunds_execution_id,refunds_entry_id)=1 and num_nonnulls(reverses_execution_id,reverses_entry_id)=0))
);
create unique index uq_case_execution_reversed_original on case_execution_continuation(reverses_execution_id) where reverses_execution_id is not null;
create unique index uq_case_execution_reversed_entry on case_execution_continuation(reverses_entry_id) where reverses_entry_id is not null;
create index idx_case_execution_continuation on case_execution_continuation(case_id,paid_at,id);
create index idx_case_execution_refund_original on case_execution_continuation(refunds_execution_id) where refunds_execution_id is not null;
create index idx_case_execution_refund_entry on case_execution_continuation(refunds_entry_id) where refunds_entry_id is not null;

create table case_court_round (
    id bigserial primary key,
    case_id bigint not null references case_file(id),
    request_id bigint not null unique,
    round_no int not null check (round_no>=1),
    applied_at date not null,
    application_reference varchar(128) not null,
    eligibility_basis varchar(2000) not null,
    unique(case_id,round_no),
    unique(id,case_id),
    foreign key(request_id,case_id) references case_continuation_request(id,case_id)
);
create table case_court_receipt (
    id bigserial primary key,
    case_id bigint not null references case_file(id),
    request_id bigint not null unique,
    round_id bigint not null unique,
    status varchar(16) not null check (status in ('ACCEPTED','RETURNED','REJECTED')),
    received_at date not null,
    receipt_no varchar(128) not null,
    foreign key(round_id,case_id) references case_court_round(id,case_id),
    foreign key(request_id,case_id) references case_continuation_request(id,case_id)
);

create function preserve_case_continuation_history() returns trigger language plpgsql as $$
begin
    if tg_table_name='case_continuation_request' and tg_op='UPDATE' then
        if old.status<>'PENDING' or new.status not in ('APPROVED','REJECTED','CANCELLED')
          or (to_jsonb(new)-array['status','reviewed_by','reviewed_username','reviewed_at','opinion'])
             is distinct from (to_jsonb(old)-array['status','reviewed_by','reviewed_username','reviewed_at','opinion']) then
            raise exception 'Continuation request history is immutable';
        end if;
        return new;
    end if;
    raise exception 'Continuation approved facts and history are immutable';
end;
$$;
create trigger trg_case_continuation_request before update or delete on case_continuation_request
  for each row execute function preserve_case_continuation_history();
create trigger trg_case_remedy_immutable before update or delete on case_remedy
  for each row execute function preserve_case_continuation_history();
create trigger trg_case_decision_version_immutable before update or delete on case_decision_version
  for each row execute function preserve_case_continuation_history();
create trigger trg_case_execution_continuation_immutable before update or delete on case_execution_continuation
  for each row execute function preserve_case_continuation_history();
create trigger trg_case_court_round_immutable before update or delete on case_court_round
  for each row execute function preserve_case_continuation_history();
create trigger trg_case_court_receipt_immutable before update or delete on case_court_receipt
  for each row execute function preserve_case_continuation_history();

-- Business as-of date and knowledge time are intentionally separate. A corrected re-report
-- may include a newly approved historical receipt; frozen report bytes preserve old output.
create function case_effective_decisions(p_cutoff date, p_known_at timestamptz default current_timestamp)
returns table(id bigint,case_id bigint,version_id bigint,decision_type varchar,decision_no varchar,
  fine_amount numeric,recoup_amount numeric,confiscate_amount numeric,other_measures varchar,
  content text,penalty_basis text,decided_at date,effective_at date,published boolean,published_at date,
  publish_withdrawn boolean,withdrawn_at timestamptz,withdraw_reason text,withdrawn_by varchar,
  mitigation varchar,discretion_reason text,gov_record_no varchar,gov_record_at date)
language sql stable as $$
 select d.id,d.case_id,v.id,
   case when v.id is null then d.decision_type else v.decision_type end,
   case when v.id is null then d.decision_no else v.decision_no end,
   coalesce(v.fine_amount,d.fine_amount),coalesce(v.recoup_amount,d.recoup_amount),
   coalesce(v.confiscate_amount,d.confiscate_amount),
   case when v.id is null then d.other_measures else v.other_measures end,
   coalesce(v.content,d.content),coalesce(v.penalty_basis,d.penalty_basis),d.decided_at,
   coalesce(v.effective_at,d.decided_at),d.published,d.published_at,d.publish_withdrawn,d.withdrawn_at,
   d.withdraw_reason,d.withdrawn_by,d.mitigation,d.discretion_reason,d.gov_record_no,d.gov_record_at
 from case_decision d left join lateral (
   select v.* from case_decision_version v join case_continuation_request r on r.id=v.request_id
   where v.case_id=d.case_id and r.status='APPROVED' and r.reviewed_at<=least(p_known_at,statement_timestamp())
     and v.effective_at<=p_cutoff
   order by v.effective_at desc,r.reviewed_at desc,v.id desc limit 1
 ) v on true where d.decided_at<=p_cutoff and p_cutoff<=(current_timestamp at time zone 'Asia/Shanghai')::date
$$;

create function case_effective_executions(p_cutoff date,p_known_at timestamptz default current_timestamp)
returns table(source text,id bigint,entry_id bigint,case_id bigint,kind varchar,amount numeric,
  paid_at date,receipt_no varchar,method varchar,note text)
language sql stable as $$
 select 'ORIGINAL'::text,e.id,null::bigint,e.case_id,e.kind,e.amount,e.paid_at,e.receipt_no,e.method,e.note::text
 from case_execution e where e.paid_at<=p_cutoff and p_cutoff<=(current_timestamp at time zone 'Asia/Shanghai')::date
 union all
 select 'CONTINUATION'::text,e.id,e.id,e.case_id,e.kind,e.amount,e.paid_at,e.receipt_no,e.method,e.note::text
 from case_execution_continuation e join case_continuation_request r on r.id=e.request_id
 where r.status='APPROVED' and r.reviewed_at<=least(p_known_at,statement_timestamp())
   and e.paid_at<=p_cutoff and p_cutoff<=(current_timestamp at time zone 'Asia/Shanghai')::date
$$;
