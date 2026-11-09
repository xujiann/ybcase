create table case_supplement_consent (
 id bigserial primary key, document_id bigint not null references case_supplement_document(id),
 receiver varchar(64) not null, channel varchar(500) not null, source_reference varchar(1000) not null,
 recorded_by varchar(64) not null, recorded_at timestamptz not null default clock_timestamp()
);
create trigger protect_supplement_consent before update or delete on case_supplement_consent for each row execute function protect_supplement_immutable();
