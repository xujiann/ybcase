-- Add facts without rewriting old legal records. Existing rows keep null acceptance/relief facts.
alter table case_review add column reviewer_user_id bigint references sys_user(id);
alter table case_review add column operator varchar(64);
alter table case_clue add column extend_approved_by varchar(64);
alter table case_clue add column extend_approved_at timestamptz;
alter table case_file add column court_enforce_status varchar(24);
alter table case_file add column court_applied_at date;
alter table case_file add column court_receipt_no varchar(128);
alter table case_file add column court_received_at date;
alter table case_file add column court_result_note text;
alter table case_file add column relief_deadline date;
alter table case_file add column relief_pending boolean not null default false;
alter table case_file add column relief_basis text;
alter table case_file add column relief_registered_by varchar(64);
alter table case_file add column defer_until date;

insert into sys_config(cfg_key, cfg_value, remark) values
 ('legal_review_recoup_threshold','100000','较大数额追回基金必审阈值（元），独立于罚款/没收阈值'),
 ('clue_extension_approver_role','LEADER','线索核查延期批准岗位，须由局方授予主要负责人对应岗位'),
 ('court_application_months','3','法定救济期限届满后法院非诉申请期限（月）；由法制登记起算依据')
on conflict (cfg_key) do nothing;

-- NOT VALID preserves old anomalous records for explicit review; new writes are constrained.
alter table case_decision add constraint ck_decision_nonnegative
 check (fine_amount >= 0 and recoup_amount >= 0 and confiscate_amount >= 0) not valid;
alter table case_decision add constraint ck_nonpunish_no_penalty
 check (decision_type = 'PUNISH' or (fine_amount = 0 and confiscate_amount = 0)) not valid;
alter table case_execution add constraint ck_execution_positive check (amount > 0) not valid;
alter table case_installment add constraint ck_installment_positive check (amount > 0) not valid;
