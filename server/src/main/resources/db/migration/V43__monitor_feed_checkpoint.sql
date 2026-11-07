create table external_monitor_checkpoint (
    singleton boolean primary key check (singleton),
    cursor_value varchar(512),
    revision bigint not null default 0
);

insert into external_monitor_checkpoint (singleton) values (true);
