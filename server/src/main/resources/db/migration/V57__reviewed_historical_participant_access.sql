-- Preserve original case_officer rows. A reviewed authorization is separate evidence.
create table case_participant_access (
 id bigserial primary key, case_id bigint not null references case_file(id), officer_id bigint not null references case_officer(id),
 user_id bigint not null references sys_user(id), dept_id bigint not null references sys_dept(id), cert_no varchar(64) not null,
 source_snapshot jsonb not null, fingerprint varchar(64) not null, reason varchar(1000) not null, source_reference varchar(1000) not null,
 approved_by varchar(64) not null, approved_at timestamptz not null default clock_timestamp(),
 unique(case_id,officer_id,user_id)
);
create trigger protect_case_participant_access before update or delete on case_participant_access for each row execute function protect_supplement_immutable();
