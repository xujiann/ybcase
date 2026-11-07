-- The audit search uses a bounded recent page and exact request IDs.
create index idx_audit_report_type_recent on sys_audit_log (report_type, id desc)
    where http_status = 200 and report_type is not null and report_row_count is not null;

create index idx_audit_request_recent on sys_audit_log (request_id, id desc)
    where request_id is not null;
