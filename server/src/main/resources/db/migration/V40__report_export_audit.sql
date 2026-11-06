-- Successful report audits store only bounded, typed metadata. Legacy audit readers remain compatible.
alter table sys_audit_log
    add column report_type varchar(24),
    add column report_start date,
    add column report_end date,
    add column report_cutoff date,
    add column report_row_count integer check (report_row_count >= 0);

create index idx_case_decision_report_date on case_decision(decided_at, case_id) where decision_type = 'PUNISH';
create index idx_case_execution_report_case on case_execution(case_id, paid_at) where kind in ('FINE', 'RECOUP', 'LATE_FEE');
create index idx_inspection_report_done on inspection(done_at, id) where done_at is not null;
create index idx_inspection_report_clue_done on inspection(clue_id, done_at) where done_at is not null;
create index idx_case_transfer_report_case on case_transfer(case_id, sent_at) where direction = 'OUT';
create index idx_case_transfer_report_clue on case_transfer(clue_id, sent_at) where direction = 'OUT' and case_id is null;
create index idx_department_interview_report_order on department_interview(interview_date, department_name, id);
