alter table biz_approval add column request_key varchar(80);
alter table biz_approval add column previous_approval_id bigint references biz_approval(id);
create unique index idx_approval_applicant_request
    on biz_approval(applicant, request_key) where request_key is not null;
create unique index idx_approval_previous
    on biz_approval(previous_approval_id) where previous_approval_id is not null;
