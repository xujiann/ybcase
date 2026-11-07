-- External operations are append-only evidence; no automatic execution posting.
insert into sys_role(code,name,remark) values ('INTEGRATION','外部回执服务','仅供受控电子送达回执服务使用，不授办案权限') on conflict(code) do nothing;
alter table case_file add column e_delivery_receiver varchar(255);
alter table case_file add column e_delivery_channel varchar(500);

create table treasury_batch (
 id bigserial primary key, request_key varchar(80) not null, request_hash varchar(64) not null,
 created_by varchar(64) not null, created_at timestamptz not null default current_timestamp,
 unique(created_by,request_key)
);
create table treasury_batch_item (
 id bigserial primary key, batch_id bigint not null references treasury_batch(id),
 source varchar(16) not null check(source in ('ORIGINAL','CONTINUATION')), source_id bigint not null,
 case_id bigint not null references case_file(id), kind varchar(16) not null, receipt_no varchar(64), amount numeric(14,2) not null,
 paid_at date not null, result varchar(32) not null default 'PENDING',
 amount_matches boolean, date_matches boolean, paid boolean, external_amount numeric, external_paid_at date, attempts int not null default 0,
 checked_at timestamptz, error_code varchar(32),
 review_status varchar(24) not null default 'OPEN' check(review_status in ('OPEN','ASSIGNED','SUBMITTED','RESOLVED','NOT_REQUIRED')),
 assignee varchar(64), investigated_by varchar(64), investigation_note varchar(1000), source_reference varchar(255),
 unique(batch_id,source,source_id)
);
create index idx_treasury_item_case on treasury_batch_item(case_id,batch_id);
create table treasury_item_history (
 id bigserial primary key, item_id bigint not null references treasury_batch_item(id),
 action varchar(24) not null, actor varchar(64) not null, note varchar(1000),
 source_reference varchar(255), result_snapshot jsonb not null, created_at timestamptz not null default current_timestamp
);

create table electronic_delivery (
 id bigserial primary key, case_id bigint not null references case_file(id),
 document_id bigint not null unique references case_document(id),
 request_key varchar(80) not null unique, document_hash varchar(64) not null,
 receiver varchar(255) not null, channel varchar(500) not null,
 status varchar(24) not null default 'DRAFT', provider_id varchar(128) unique,
 created_by varchar(64) not null, created_at timestamptz not null default current_timestamp,
 sent_at timestamptz, deadline_at timestamptz, attempts int not null default 0,
 error_code varchar(32), delivery_id bigint unique references case_delivery(id)
);
create table electronic_delivery_event (
 id bigserial primary key, envelope_id bigint not null references electronic_delivery(id),
 event_key varchar(128) not null unique, payload_hash varchar(64) not null,
 status varchar(24) not null, occurred_at timestamptz not null,
 receipt_no varchar(64), outcome varchar(32) not null, reason varchar(255),
 created_at timestamptz not null default current_timestamp
);
create index idx_electronic_delivery_case on electronic_delivery(case_id,id);
create table integration_operation (
 id bigserial primary key, target varchar(24) not null, operation varchar(32) not null,
 actor varchar(64) not null, source_id bigint, status varchar(24) not null,
 error_code varchar(32), created_at timestamptz not null default current_timestamp
);
