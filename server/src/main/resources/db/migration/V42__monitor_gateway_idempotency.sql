create table external_monitor_clue (
    external_id varchar(128) primary key,
    fingerprint char(64) not null,
    clue_id bigint unique references case_clue(id)
);
