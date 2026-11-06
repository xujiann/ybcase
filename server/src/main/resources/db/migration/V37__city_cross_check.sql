-- Retain the V34 categories and distinguish city cross-checks from other municipal inspections.
alter table inspection drop constraint inspection_task_level_check,
    add constraint inspection_task_level_check
    check (task_level in ('NATIONAL', 'PROVINCIAL', 'MUNICIPAL', 'CITY_CROSS_CHECK'));
