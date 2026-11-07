alter table department_interview
    add column source_agreement_id bigint unique references agreement_action(id),
    add column source_agency_cycle integer,
    add check ((source_agreement_id is null and source_agency_cycle is null)
        or (source_agreement_id is not null and source_agency_cycle is not null and source_agency_cycle > 0));

alter table agreement_action add column agency_auto_reminded_at date;
alter table agency_agreement_event drop constraint agency_agreement_event_event_type_check;
alter table agency_agreement_event add constraint agency_agreement_event_event_type_check
    check (event_type in ('DISPATCHED','ACCEPTED','RETURNED','REASSIGNED','REMINDED','COMPLETED','AUTO_REMINDED'));
