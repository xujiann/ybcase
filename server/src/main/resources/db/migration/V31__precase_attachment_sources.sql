alter table case_attachment alter column case_id drop not null;
alter table case_attachment add column source_clue_id bigint references case_clue(id);
alter table case_attachment add column source_inspection_id bigint references inspection(id);
alter table case_attachment add constraint ck_attachment_one_source
 check (num_nonnulls(source_clue_id,source_inspection_id) <= 1);
alter table case_attachment add constraint ck_attachment_has_owner
 check (case_id is not null or num_nonnulls(source_clue_id,source_inspection_id) = 1);
alter table case_attachment add constraint ck_attachment_case_links
 check (case_id is not null or (document_id is null and evidence_id is null));
create index idx_attachment_source_clue on case_attachment(source_clue_id) where source_clue_id is not null;
create index idx_attachment_source_inspection on case_attachment(source_inspection_id) where source_inspection_id is not null;

-- A source is immutable; adoption is a one-way null -> case_id transition, never a copy or re-parent.
create function guard_attachment_origin() returns trigger language plpgsql as $$
begin
 if new.source_clue_id is distinct from old.source_clue_id
    or new.source_inspection_id is distinct from old.source_inspection_id then
   raise exception 'Attachment source is immutable';
 end if;
 if old.case_id is not null and new.case_id is distinct from old.case_id then
   raise exception 'Adopted attachment cannot be moved to another case';
 end if;
 return new;
end $$;
create trigger trg_attachment_origin before update on case_attachment
 for each row execute function guard_attachment_origin();
