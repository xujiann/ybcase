alter table case_file
    add column reporting_details_required boolean not null default false,
    add column institution_code varchar(64),
    add column institution_ownership varchar(16) check (institution_ownership in ('PUBLIC', 'SOCIAL')),
    add column institution_grade varchar(16) check (institution_grade in ('TERTIARY', 'SECONDARY', 'PRIMARY', 'UNGRADED')),
    add column confirmed_violation_start date,
    add column confirmed_violation_end date,
    add column confirmed_violation_facts text,
    add column confirmed_violation_amount numeric(14,2) check (confirmed_violation_amount >= 0),
    add check (confirmed_violation_start <= confirmed_violation_end);

alter table case_decision add column penalty_basis text;

alter table sys_user add column account_category varchar(16)
    check (account_category in ('DISTRICT', 'BUREAU'));
