-- Later materials are independent of the sealed original case and archive.
create table case_supplement_bundle (
 id bigserial primary key, case_id bigint not null references case_file(id),
 request_key varchar(80) not null, title varchar(200) not null, source_reference varchar(1000) not null,
 status varchar(16) not null default 'DRAFT' check(status in ('DRAFT','PENDING','APPROVED','REJECTED','WITHDRAWN')),
 created_by varchar(64) not null, applicant_id bigint not null references sys_user(id),
 applicant_dept_id bigint references sys_dept(id), applicant_category varchar(16),
 created_at timestamptz not null default clock_timestamp(), source_snapshot jsonb not null,
 manifest jsonb, manifest_sha256 varchar(64), submitted_at timestamptz,
 reviewed_by varchar(64), reviewed_at timestamptz, opinion varchar(2000),
 unique(created_by,request_key)
);
create table case_supplement_file (
 id bigserial primary key, bundle_id bigint not null references case_supplement_bundle(id),
 filename varchar(200) not null, content_type varchar(100) not null, sha256 varchar(64) not null,
 size_bytes int not null check(size_bytes between 1 and 10485760), data bytea not null,
 uploaded_by varchar(64) not null, uploaded_at timestamptz not null default clock_timestamp(),
 unique(bundle_id,filename,sha256), check(octet_length(data)=size_bytes)
);
create table case_supplement_request_material (
 request_id bigint primary key references case_continuation_request(id),
 bundle_id bigint not null references case_supplement_bundle(id), manifest_sha256 varchar(64) not null,
 bound_by varchar(64) not null, bound_at timestamptz not null default clock_timestamp()
);
create table case_supplement_document (
 id bigserial primary key, case_id bigint not null references case_file(id),
 bundle_id bigint not null references case_supplement_bundle(id),
 decision_version_id bigint references case_decision_version(id),
 request_key varchar(80) not null, doc_type varchar(24) not null,
 title varchar(200) not null, content text not null, content_sha256 varchar(64) not null,
 made_at date not null, created_by varchar(64) not null, created_at timestamptz not null default clock_timestamp(),
 unique(bundle_id,request_key)
);
create table case_supplement_signature (
 id bigserial primary key, document_id bigint not null references case_supplement_document(id),
 signer varchar(64) not null, provider varchar(64) not null, content_hash varchar(64) not null,
 signature text not null, signed_at timestamptz not null default clock_timestamp(),
 unique(document_id,signer,provider,content_hash)
);
create table case_supplement_delivery (
 id bigserial primary key, document_id bigint not null references case_supplement_document(id),
 method varchar(24) not null, delivered_at date not null, receiver varchar(64) not null,
 receipt_no varchar(64) not null, source_reference varchar(1000) not null,
 recorded_by varchar(64) not null, recorded_at timestamptz not null default clock_timestamp(),
 unique(document_id,receipt_no)
);
create function protect_supplement_immutable() returns trigger language plpgsql as $$
begin raise exception 'Supplement evidence is immutable'; end $$;
create trigger protect_supplement_file before update or delete on case_supplement_file for each row execute function protect_supplement_immutable();
create trigger protect_supplement_binding before update or delete on case_supplement_request_material for each row execute function protect_supplement_immutable();
create trigger protect_supplement_document before update or delete on case_supplement_document for each row execute function protect_supplement_immutable();
create trigger protect_supplement_signature before update or delete on case_supplement_signature for each row execute function protect_supplement_immutable();
create trigger protect_supplement_delivery before update or delete on case_supplement_delivery for each row execute function protect_supplement_immutable();
create function protect_supplement_bundle() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' then raise exception 'Supplement bundle cannot be deleted'; end if;
 if row(new.case_id,new.request_key,new.title,new.source_reference,new.created_by,new.applicant_id,new.applicant_dept_id,new.applicant_category,new.created_at,new.source_snapshot)
 is distinct from row(old.case_id,old.request_key,old.title,old.source_reference,old.created_by,old.applicant_id,old.applicant_dept_id,old.applicant_category,old.created_at,old.source_snapshot)
 or not ((old.status='DRAFT' and new.status in ('PENDING','WITHDRAWN')) or (old.status='PENDING' and new.status in ('APPROVED','REJECTED','WITHDRAWN')))
 then raise exception 'Invalid supplement transition'; end if;
 if old.status<>'DRAFT' and row(new.manifest,new.manifest_sha256,new.submitted_at) is distinct from row(old.manifest,old.manifest_sha256,old.submitted_at)
 then raise exception 'Submitted supplement manifest is immutable'; end if;
 return new;
end $$;
create trigger protect_supplement_bundle before update or delete on case_supplement_bundle for each row execute function protect_supplement_bundle();
create function supplement_file_requires_draft() returns trigger language plpgsql as $$
begin
 perform 1 from case_supplement_bundle where id=new.bundle_id and status='DRAFT' for update;
 if not found then raise exception 'Files require an open draft bundle'; end if;
 return new;
end $$;
create trigger supplement_file_requires_draft before insert on case_supplement_file for each row execute function supplement_file_requires_draft();
