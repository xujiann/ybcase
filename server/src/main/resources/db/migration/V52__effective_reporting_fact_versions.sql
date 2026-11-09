-- Business time and approved knowledge time use the same rules as effective decisions.
-- Existing reporting function/view remain available to older callers.
create function case_effective_reporting_facts(
 p_cutoff date default (current_timestamp at time zone 'Asia/Shanghai')::date,
 p_known_at timestamptz default current_timestamp)
returns table(case_id bigint,institution_code varchar,institution_ownership varchar,institution_grade varchar,
 party_credit_no varchar,confirmed_violation_start date,confirmed_violation_end date,
 confirmed_violation_facts text,confirmed_violation_amount numeric,penalty_basis text,
 has_approved_supplement boolean,institution_kind varchar)
language sql stable as $$
select b.case_id,
 coalesce(v.facts->>'institutionCode',b.institution_code)::varchar,
 coalesce(v.facts->>'institutionOwnership',b.institution_ownership)::varchar,
 coalesce(v.facts->>'institutionGrade',b.institution_grade)::varchar,
 b.party_credit_no,
 coalesce((v.facts->>'confirmedViolationStart')::date,b.confirmed_violation_start),
 coalesce((v.facts->>'confirmedViolationEnd')::date,b.confirmed_violation_end),
 coalesce(v.facts->>'confirmedViolationFacts',b.confirmed_violation_facts),
 coalesce((v.facts->>'confirmedViolationAmount')::numeric,b.confirmed_violation_amount),
 b.penalty_basis,b.has_approved_supplement,
 coalesce(v.facts->>'institutionKind',c.institution_kind)::varchar
from case_reporting_facts_at(p_known_at) b join case_file c on c.id=b.case_id
left join lateral (
 select jsonb_object_agg(e.key,e.value order by f.effective_at,r.reviewed_at,f.id) as facts
 from case_fact_revision f join case_continuation_request r on r.id=f.request_id
 cross join lateral jsonb_each(f.facts) e
 where f.case_id=b.case_id and r.status='APPROVED'
   and r.reviewed_at<=least(p_known_at,statement_timestamp()) and f.effective_at<=p_cutoff
) v on true
where p_cutoff<=(current_timestamp at time zone 'Asia/Shanghai')::date;
$$;
