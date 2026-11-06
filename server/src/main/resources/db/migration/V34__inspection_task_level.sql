alter table inspection add column task_level varchar(16)
    check (task_level in ('NATIONAL', 'PROVINCIAL', 'MUNICIPAL'));

create table case_transfer_judgment (
    transfer_id bigint primary key references case_transfer(id),
    judgment_no varchar(128) not null,
    judgment_at date not null,
    judgment_result text not null
);

alter table case_file add column institution_kind varchar(16)
    check (institution_kind in ('MEDICAL', 'SINGLE_PHARMACY', 'CHAIN_PHARMACY'));
