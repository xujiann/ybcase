-- Preserve historical replies and leave an unknown processing deadline unset.
alter table agreement_action
    add column agency_status varchar(16),
    add column agency_cycle integer not null default 0,
    add column agency_due_at date,
    add column agency_received_at date,
    add column agency_received_by bigint references sys_user(id),
    add column agency_returned_at date,
    add column agency_return_reason text,
    add column agency_reminded_at date,
    add column agency_result_type varchar(16),
    add column agency_recovered_amount numeric(14,2),
    add column agency_result_effective_at date,
    add column agency_result_end_at date,
    add column agency_result_reference varchar(128);

update agreement_action
set agency_status = case when replied_at is null then 'PENDING' else 'COMPLETED' end,
    agency_cycle = 1
where agency_dept_id is not null;

alter table agreement_action
    add constraint agreement_agency_scope check (
        (agency_dept_id is null and agency_status is null and agency_cycle = 0)
        or (agency_dept_id is not null and agency_status is not null and agency_cycle > 0
            and agency_status in ('PENDING', 'ACCEPTED', 'RETURNED', 'COMPLETED')
            and ((agency_status = 'COMPLETED') = (replied_at is not null)))
    ),
    add constraint agreement_agency_result_type check (
        agency_result_type is null or (agency_status = 'COMPLETED'
            and agency_result_type in ('COMPLETED', 'NOT_APPLICABLE'))
    ),
    add constraint agreement_agency_recovered_amount check (
        agency_recovered_amount is null or agency_recovered_amount > 0
    );

create table agency_agreement_event (
    id bigserial primary key,
    agreement_id bigint not null references agreement_action(id),
    agency_cycle integer not null,
    event_type varchar(16) not null
        check (event_type in ('DISPATCHED', 'ACCEPTED', 'RETURNED', 'REASSIGNED', 'REMINDED', 'COMPLETED')),
    agency_dept_id bigint not null references sys_dept(id),
    department_name varchar(128) not null,
    actor_username varchar(64),
    detail text,
    due_at date,
    created_at timestamptz not null default now()
);
create index idx_agency_agreement_event on agency_agreement_event (agreement_id, id);
