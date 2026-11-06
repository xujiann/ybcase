-- Existing review facts remain untouched. Null snapshots identify legacy, unbound reviews.
alter table case_review add column material_fingerprint varchar(64);
alter table case_review add column material_snapshot text;
alter table case_review add constraint ck_review_material_binding check (
 (material_fingerprint is null and material_snapshot is null)
 or (material_fingerprint is not null and material_snapshot is not null
     and material_fingerprint ~ '^[0-9a-f]{64}$')
);
