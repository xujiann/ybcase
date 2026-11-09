alter table case_participant_access drop constraint case_participant_access_case_id_officer_id_user_id_key;
create table case_participant_access_revocation (
 grant_id bigint primary key references case_participant_access(id),
 reason varchar(1000) not null, source_reference varchar(1000) not null,
 revoked_by varchar(64) not null, revoked_at timestamptz not null default clock_timestamp()
);
create trigger protect_participant_access_revocation before update or delete on case_participant_access_revocation for each row execute function protect_supplement_immutable();
