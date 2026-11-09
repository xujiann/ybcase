alter table electronic_delivery drop constraint electronic_delivery_document_id_key;
alter table electronic_delivery alter column document_id drop not null;
alter table electronic_delivery add column supplement_document_id bigint references case_supplement_document(id);
alter table electronic_delivery add column replaces_id bigint unique references electronic_delivery(id);
alter table electronic_delivery add column superseded_by bigint unique references electronic_delivery(id);
alter table electronic_delivery add column superseded_reason varchar(1000);
alter table electronic_delivery add column superseded_at timestamptz;
alter table electronic_delivery add column supplement_delivery_id bigint unique references case_supplement_delivery(id);
alter table electronic_delivery add check(num_nonnulls(document_id,supplement_document_id)=1);
create unique index electronic_delivery_active_original on electronic_delivery(document_id) where document_id is not null and superseded_at is null;
create unique index electronic_delivery_active_supplement on electronic_delivery(supplement_document_id) where supplement_document_id is not null and superseded_at is null;
create table electronic_delivery_receipt_evidence (
 event_id bigint primary key references electronic_delivery_event(id),
 content_type varchar(100) not null, sha256 varchar(64) not null,
 data bytea not null check(octet_length(data) between 1 and 5242880)
);
create trigger electronic_delivery_receipt_evidence_immutable before update or delete on electronic_delivery_receipt_evidence for each row execute function protect_supplement_immutable();
