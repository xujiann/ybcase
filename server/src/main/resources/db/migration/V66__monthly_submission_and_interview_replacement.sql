alter table interview_change_request drop constraint interview_change_request_request_kind_check;
alter table interview_change_request add constraint interview_change_request_request_kind_check
    check (request_kind in ('CORRECT', 'VOID', 'REPLACE'));

create view eligible_interview_replacement as
select i.id as interview_id,a.id as agreement_id,a.agency_cycle,i.dept_id,a.case_id,c.party_name as institution_name
from department_interview i join agreement_action a on a.id=i.source_agreement_id
    and a.agency_cycle=i.source_agency_cycle and a.agency_dept_id=i.dept_id
join case_file c on c.id=a.case_id and c.party_type='PROVIDER'
join sys_dept dept on dept.id=i.dept_id and dept.enabled and dept.org_kind='AGENCY'
left join effective_department_interviews() effective on effective.id=i.id
where effective.id is null and a.action='INTERVIEW' and a.agency_status='COMPLETED'
    and (a.agency_result_type='COMPLETED' or a.agency_result_type is null)
    and not exists(select 1 from interview_change_request r where r.interview_id=i.id and r.status='PENDING');

create function case_penalty_missing_fields(p_cutoff date)
returns table(case_id bigint,case_no varchar,party_name varchar,decided_at date,missing_fields text[])
language sql stable as $$
    select checked.* from (
        select c.id,c.case_no,c.party_name,d.decided_at,array_remove(array[
            case when coalesce(case when c.party_type='INDIVIDUAL' then rf.party_credit_no else rf.institution_code end,'')
                !~ '[^[:space:]]' then case when c.party_type='PROVIDER' then '机构编码' else '参保人证件号码' end end,
            case when c.party_type='PROVIDER' and rf.institution_ownership is null then '机构性质' end,
            case when c.party_type='PROVIDER' and rf.institution_grade is null then '机构等级' end,
            case when rf.confirmed_violation_start is null or rf.confirmed_violation_end is null then '违法发生期间' end,
            case when coalesce(rf.confirmed_violation_facts,'') !~ '[^[:space:]]' then '认定违法事实' end,
            case when rf.confirmed_violation_amount is null then '认定违法违规金额' end,
            case when coalesce(case when coalesce(d.penalty_basis,'') ~ '[^[:space:]]' then d.penalty_basis else rf.penalty_basis end,'')
                !~ '[^[:space:]]' then '处罚依据' end
        ],null) as missing_fields
        from case_effective_decisions(p_cutoff) d join case_file c on c.id=d.case_id
        left join case_effective_reporting_facts(p_cutoff) rf on rf.case_id=c.id
        where d.decision_type='PUNISH' and c.party_type in ('PROVIDER','INDIVIDUAL')
    ) checked where cardinality(checked.missing_fields)>0;
$$;

alter table report_frozen_batch drop constraint report_frozen_batch_report_type_check;
alter table report_frozen_batch add constraint report_frozen_batch_report_type_check
    check (report_type in ('PENALTIES', 'ENFORCEMENT_WORK', 'INTERVIEWS', 'MONTHLY_SUBMISSION'));
alter table report_frozen_batch add column scope_label varchar(128);
alter table report_frozen_batch add check (report_type <> 'MONTHLY_SUBMISSION' or scope_label is not null);

create table report_submission_receipt (
    batch_id bigint primary key references report_frozen_batch(id),
    submitted_at date not null,
    recipient varchar(256) not null,
    receipt_no varchar(128) not null,
    note varchar(2000),
    recorded_by bigint not null references sys_user(id),
    recorded_username varchar(64) not null,
    recorded_at timestamptz not null default clock_timestamp()
);
create trigger preserve_report_submission_receipt before update or delete on report_submission_receipt
    for each row execute function preserve_report_batch_event();
