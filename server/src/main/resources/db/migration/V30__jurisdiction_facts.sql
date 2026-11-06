-- Facts only: these categories are not an automated legal determination of jurisdiction.
-- Existing cases remain operable and may be supplemented with an auditable reason.
alter table case_file add column jurisdiction_type varchar(24) not null default 'PENDING';
alter table case_file add column jurisdiction_basis varchar(2000);
alter table case_file add column violation_place varchar(255);
alter table case_file add column treatment_place varchar(255);
alter table case_file add column insured_place varchar(255);
alter table case_file add column designated_no varchar(128);
alter table case_file add column jurisdiction_dispute_note varchar(2000);
alter table case_file add constraint ck_jurisdiction_type
 check (jurisdiction_type in ('PENDING','LOCAL','TRANSFERRED','DESIGNATED','OTHER'));
