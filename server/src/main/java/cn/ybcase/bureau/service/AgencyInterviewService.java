package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;

/** Records the actual interview once, retaining the agreement and dispatch that produced it. */
@Service
@RequiredArgsConstructor
public class AgencyInterviewService {
    private final JdbcTemplate jdbc;
    private final MaterialAccess materials;

    public record ActualInterview(String institutionCode, String interviewType, String reason,
                                  String content, String participants, LocalDate interviewDate) {}

    @Transactional
    public long register(long agreementId, Integer cycle, ActualInterview input, String username) {
        var actor = materials.lockActor(username, false);
        if (!"AGENCY".equals(actor.accountCategory()) || !actor.roles().equals(java.util.Set.of("AGENCY")) || actor.deptId() == null)
            throw new BizException(403, "请使用已归类的医保中心经办账号");
        return registerLocked(agreementId, cycle, input, actor);
    }

    /** The reply transaction already holds the receiving actor and agreement row. */
    long registerLocked(long agreementId, Integer cycle, ActualInterview input, WorkflowUserAccess.User actor) {
        if (cycle == null || cycle <= 0) throw invalid("请刷新经办事项后登记");
        var sources = jdbc.queryForList("""
                select a.agency_cycle,a.agency_dept_id,a.sent_at,c.party_name,rf.institution_code,d.name as department_name
                from agreement_action a join case_file c on c.id=a.case_id
                join case_reporting_facts rf on rf.case_id=c.id
                join sys_dept d on d.id=a.agency_dept_id and d.enabled and d.org_kind='AGENCY'
                where a.id=? and a.action='INTERVIEW' and a.agency_status='COMPLETED'
                  and (a.agency_result_type='COMPLETED' or a.agency_result_type is null) and c.party_type='PROVIDER'
                  and a.agency_dept_id=? and a.agency_cycle=? for update of a
                """, agreementId, actor.deptId(), cycle);
        if (sources.isEmpty()) throw invalid("仅本部门已落实的约谈事项可以登记实际约谈");
        var source = sources.getFirst();
        if (input == null) throw invalid("请填写实际约谈资料");
        String code = input.institutionCode() == null || input.institutionCode().isBlank()
                ? (String) source.get("institution_code") : input.institutionCode();
        var value = DepartmentInterviewService.validate(new DepartmentInterviewService.InterviewInput(
                code, (String) source.get("party_name"), input.interviewType(), input.reason(), input.content(),
                input.participants(), input.interviewDate()));
        if (value.interviewDate().isBefore(((java.sql.Date) source.get("sent_at")).toLocalDate()))
            throw invalid("实际约谈日期不得早于本次移交日期");
        var existing = jdbc.queryForList("select * from department_interview where source_agreement_id=?", agreementId);
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            boolean same = Objects.equals(row.get("institution_code"), value.institutionCode())
                    && Objects.equals(row.get("interview_type"), value.interviewType())
                    && Objects.equals(row.get("reason"), value.reason()) && Objects.equals(row.get("content"), value.content())
                    && Objects.equals(row.get("participants"), value.participants())
                    && Objects.equals(((java.sql.Date) row.get("interview_date")).toLocalDate(), value.interviewDate())
                    && ((Number) row.get("source_agency_cycle")).intValue() == cycle;
            if (!same) throw invalid("本事项已登记实际约谈，请核对原记录");
            return ((Number) row.get("id")).longValue();
        }
        return jdbc.queryForObject("""
                insert into department_interview(dept_id,department_name,institution_code,institution_name,
                    interview_type,reason,content,participants,interview_date,created_by,
                    source_agreement_id,source_agency_cycle)
                values(?,?,?,?,?,?,?,?,?,?,?,?) returning id
                """, Long.class, actor.deptId(), source.get("department_name"), value.institutionCode(),
                value.institutionName(), value.interviewType(), value.reason(), value.content(), value.participants(),
                value.interviewDate(), actor.id(), agreementId, cycle);
    }

    private static BizException invalid(String message) { return new BizException(2192, message); }
}
