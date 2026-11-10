package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Source row locks serialize uploads, inspection conversion and one-way case adoption. */
@Service
@RequiredArgsConstructor
public class SourceAttachmentAccess {
    private final JdbcTemplate jdbc;
    private final BureauConfig config;
    private final MaterialAccess materials;

    public boolean hasRole(String username, String... roles) {
        Object[] args = new Object[roles.length + 1]; args[0] = username;
        System.arraycopy(roles, 0, args, 1, roles.length);
        Integer n = jdbc.queryForObject("select count(*) from sys_user u join sys_user_role ur on ur.user_id=u.id"
                + " join sys_role r on r.id=ur.role_id where u.username=? and u.enabled=true and r.code in ("
                + String.join(",", java.util.Collections.nCopies(roles.length, "?")) + ")", Integer.class, args);
        return n != null && n > 0;
    }

    public void requireWriter(String username) {
        if (!hasRole(username, "HANDLER", "LEADER", "ADMIN")) throw new BizException(403, "当前岗位无权办理来源附件");
    }

    private Map<String, Object> row(String source, Long id, boolean lock) {
        String table = switch (source) { case "CLUE" -> "case_clue"; case "INSPECTION" -> "inspection";
            default -> throw new BizException(2146, "附件来源类型无效"); };
        List<Map<String, Object>> rows = jdbc.queryForList("select * from " + table + " where id=?" + (lock ? " for update" : ""), id);
        if (rows.isEmpty()) throw new BizException(2146, "附件来源记录不存在");
        return rows.get(0);
    }

    private Long caseForClue(Long clueId) {
        var rows = jdbc.queryForList("select id from case_file where clue_id=? order by id", clueId);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    private void assertClueOwner(Long id, String username, boolean allowLegal) {
        boolean privileged = allowLegal ? hasRole(username, "LEADER", "ADMIN", "LEGAL") : hasRole(username, "LEADER", "ADMIN");
        var scope = clueOwnerPredicate("cl", username, privileged, materials.departmentScope(username));
        var args = new java.util.ArrayList<Object>();
        args.add(id);
        args.addAll(scope.args());
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from case_clue cl where cl.id=? and " + scope.sql() + ")",
                Boolean.class, args.toArray())))
            throw new BizException(2080, "该来源材料不在您的办理范围内");
    }

    /** Trusted SQL expressions only. Arguments stay bound; scopes run before count/limit/offset. */
    public record ReadPredicate(String sql, List<Object> args) {}

    public ReadPredicate caseReadPredicate(String caseIdExpression, String username) {
        var scope = materials.caseScope(username, hasRole(username, "LEADER", "ADMIN", "LEGAL"));
        return new ReadPredicate("exists (select 1 from case_file where case_file.id=" + caseIdExpression
                + scope.sql() + ")", scope.args());
    }

    public ReadPredicate clueReadPredicate(String clueIdExpression, String username) {
        boolean privileged = hasRole(username, "LEADER", "ADMIN", "LEGAL");
        var department = materials.departmentScope(username);
        if (privileged && !department.district())
            return new ReadPredicate("exists (select 1 from case_clue access_clue where access_clue.id="
                    + clueIdExpression + ")", List.of());
        // assertRead/caseForClue use the first adopted case if historical duplicates exist.
        ReadPredicate adopted = caseReadPredicate("(select min(adopted.id) from case_file adopted where adopted.clue_id=access_clue.id)", username);
        var source = clueOwnerPredicate("access_clue", username, privileged, department);
        var args = new java.util.ArrayList<Object>(source.args());
        args.addAll(adopted.args());
        return new ReadPredicate("exists (select 1 from case_clue access_clue where access_clue.id=" + clueIdExpression
                + " and ((not exists (select 1 from case_file unadopted where unadopted.clue_id=access_clue.id)"
                + " and " + source.sql() + ")"
                + " or " + adopted.sql() + "))", List.copyOf(args));
    }

    private ReadPredicate clueOwnerPredicate(String alias, String username, boolean privileged,
                                               MaterialAccess.DepartmentScope department) {
        var organization = sourceDepartmentPredicate(alias + ".owner_dept_id", department);
        if (privileged) return organization;
        var args = new java.util.ArrayList<Object>(organization.args());
        args.add(username);
        args.add(username);
        args.add("[、,，;；\\s]+");
        args.add(username);
        String ownership = """
                %1$s and (((%2$s.created_by=? or ?=any(regexp_split_to_array(coalesce(%2$s.handler,''),?)))
                    and not exists (select 1 from department_handoff h where h.clue_id=%2$s.id and h.status='ACCEPTED'))
                    or exists (select 1 from sys_user u join sys_dept d on d.id=u.dept_id
                               join sys_user_role ur on ur.user_id=u.id join sys_role r on r.id=ur.role_id
                               where u.id=(select a.assigned_user_id from clue_assignment a
                                           where a.handoff_id=(select max(h.id) from department_handoff h
                                               where h.clue_id=%2$s.id and h.status='ACCEPTED')
                                           order by a.id desc limit 1)
                                 and u.username=? and u.enabled and d.enabled
                                 and d.id=%2$s.owner_dept_id and d.org_kind=u.account_category and r.code='HANDLER'))
                """.formatted(organization.sql(), alias);
        return new ReadPredicate(ownership, List.copyOf(args));
    }

    public ReadPredicate inspectionReadPredicate(String inspectionAlias, String username) {
        boolean privileged = hasRole(username, "LEADER", "ADMIN", "LEGAL");
        var department = materials.departmentScope(username);
        if (privileged && !department.district()) return new ReadPredicate("true", List.of());
        ReadPredicate clue = clueReadPredicate(inspectionAlias + ".clue_id", username);
        var source = inspectionOwnerPredicate(inspectionAlias, username, privileged, department);
        var args = new java.util.ArrayList<Object>(source.args());
        args.addAll(clue.args());
        return new ReadPredicate("((" + inspectionAlias + ".clue_id is null and " + inspectionAlias
                + ".id is not null and " + source.sql()
                + ") or (" + inspectionAlias + ".clue_id is not null and " + clue.sql() + "))", List.copyOf(args));
    }

    private ReadPredicate inspectionOwnerPredicate(String alias, String username, boolean privileged,
                                                   MaterialAccess.DepartmentScope department) {
        var organization = sourceDepartmentPredicate(alias + ".owner_dept_id", department);
        if (privileged) return organization;
        var args = new java.util.ArrayList<Object>(organization.args());
        args.add(username);
        args.add(username);
        String ownership = """
                %1$s and ((%2$s.assigned_user_id is null and %2$s.created_by=?
                    and not exists (select 1 from department_handoff h
                                    where h.inspection_id=%2$s.id and h.status='ACCEPTED'))
                    or exists (select 1 from sys_user u join sys_dept d on d.id=u.dept_id
                               join sys_user_role ur on ur.user_id=u.id join sys_role r on r.id=ur.role_id
                               where u.id=%2$s.assigned_user_id and u.username=? and u.enabled and d.enabled
                                 and d.id=%2$s.owner_dept_id and d.org_kind=u.account_category
                                 and r.code='HANDLER'))
                """.formatted(organization.sql(), alias);
        return new ReadPredicate(ownership, List.copyOf(args));
    }

    private void assertInspectionOwner(Long id, String username, boolean allowLegal) {
        boolean privileged = allowLegal ? hasRole(username, "LEADER", "ADMIN", "LEGAL") : hasRole(username, "LEADER", "ADMIN");
        var scope = inspectionOwnerPredicate("i", username, privileged, materials.departmentScope(username));
        var args = new java.util.ArrayList<Object>();
        args.add(id);
        args.addAll(scope.args());
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from inspection i where i.id=? and " + scope.sql() + ")",
                Boolean.class, args.toArray())))
            throw new BizException(2080, "该检查材料不在您的办理范围内");
    }

    private ReadPredicate sourceDepartmentPredicate(String ownerDeptExpression, MaterialAccess.DepartmentScope department) {
        if (!department.district()) return new ReadPredicate("true", List.of());
        if (department.deptId() == null) return new ReadPredicate("false", List.of());
        return new ReadPredicate(ownerDeptExpression + "=?", List.of(department.deptId()));
    }

    /** Pre-case applications follow their recorded organization, never the applicant's latest assignment. */
    public ReadPredicate approvalDepartmentPredicate(String alias, String username) {
        var department = materials.departmentScope(username);
        if (!department.district()) return new ReadPredicate("true", List.of());
        if (department.deptId() == null) return new ReadPredicate("false", List.of());
        return new ReadPredicate("((exists (select 1 from case_file approval_case where approval_case.id="
                + alias + ".case_id and approval_case.owner_dept_id=?)) or (" + alias + ".case_id is null and "
                + alias + ".applicant_dept_id=?))",
                List.of(department.deptId(), department.deptId()));
    }

    public ReadPredicate transferReadPredicate(String alias, String username) {
        var caseScope = caseReadPredicate(alias + ".case_id", username);
        var clueScope = clueReadPredicate(alias + ".clue_id", username);
        var args = new java.util.ArrayList<Object>(caseScope.args()); args.addAll(clueScope.args());
        return new ReadPredicate("(" + alias + ".case_id is not null or " + alias + ".clue_id is not null)"
                + " and (" + alias + ".case_id is null or " + caseScope.sql() + ")"
                + " and (" + alias + ".clue_id is null or " + clueScope.sql() + ")", List.copyOf(args));
    }

    /** Uses the same scope as case reads after source materials enter a case. */
    public void assertCaseScope(Long caseId, String username) {
        materials.assertCaseScope(caseId, materials.caseScope(username, hasRole(username, "LEADER", "ADMIN", "LEGAL")));
    }

    public void assertRead(String source, Long id, String username) {
        Map<String, Object> r = row(source, id, false);
        if ("INSPECTION".equals(source) && r.get("clue_id") instanceof Number clue) {
            assertRead("CLUE", clue.longValue(), username); return;
        }
        Long caseId = "CLUE".equals(source) ? caseForClue(id) : null;
        if (caseId != null) assertCaseScope(caseId, username);
        else if ("INSPECTION".equals(source)) assertInspectionOwner(id, username, true);
        else assertClueOwner(id, username, true);
    }

    public void lockWritable(String source, Long id, String username) {
        Map<String, Object> r = row(source, id, true);
        materials.lockActor(username, true);
        assertWritable(source, id, r, username);
    }

    private void assertWritable(String source, Long id, Map<String, Object> r, String username) {
        if ("CLUE".equals(source)) {
            assertClueOwner(id, username, false);
            if (!"PENDING".equals(r.get("status")) || caseForClue(id) != null)
                throw new BizException(2146, "线索已办结或已立案，请在对应案件办理附件");
        } else {
            assertInspectionOwner(id, username, false);
            if (r.get("clue_id") != null)
                throw new BizException(2146, "检查已转线索，请在对应线索或案件办理附件");
        }
    }

    public void lockInspectionForTransition(Long id, String username) {
        Map<String, Object> inspection = row("INSPECTION", id, true);
        materials.lockActor(username, true);
        assertInspectionOwner(id, username, false);
        if (inspection.get("clue_id") != null) throw new BizException(2063, "该检查已转线索");
    }

    /** Scope for clue business actions, including receipts after transfer. Caller checks lifecycle. */
    public void lockClueForHandling(Long id, String username) {
        row("CLUE", id, true);
        // General clue actions may lock linked existing cases next. Only attachment-specific
        // paths hold actor locks; keeping this check non-locking avoids user -> case inversion.
        requireWriter(username);
        assertClueOwner(id, username, false);
    }

    /** Create and approval share this check, so arbitrary clue IDs cannot be used to acquire other source files. */
    public void clueForFiling(Long id, String username, boolean lock) {
        Map<String, Object> clue = row("CLUE", id, lock);
        assertClueOwner(id, username, false);
        if (!"PENDING".equals(clue.get("status")) || caseForClue(id) != null)
            throw new BizException(2021, "线索已办结，不可重复立案");
    }

    /** Caller holds the clue first; lock every existing inspection before any workflow actor lock. */
    public void lockInspectionsForAdoption(Long clueId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Source adoption must be transactional");
        jdbc.queryForList("select id from inspection where clue_id=? order by id for update", clueId);
    }

    public void adoptClue(Long clueId, Long caseId) {
        // Creation/approval already took these rows before locking actors; re-locking in the same transaction is safe.
        lockInspectionsForAdoption(clueId);
        String origin = "(source_clue_id=? or source_inspection_id in (select id from inspection where clue_id=?))";
        Integer conflict = jdbc.queryForObject("select count(*) from case_attachment where " + origin + " and case_id is not null and case_id<>?",
                Integer.class, clueId, clueId, caseId);
        if (conflict != null && conflict > 0) throw new BizException(2146, "来源材料已归属其他案件，不可重复归案");
        jdbc.update("update case_attachment set case_id=? where " + origin + " and case_id is null", caseId, clueId, clueId);
    }

    public void assertAttachmentRead(Map<String, Object> attachment, String username) {
        if (attachment.get("case_id") instanceof Number caseId) assertCaseScope(caseId.longValue(), username);
        else if (attachment.get("source_clue_id") instanceof Number clueId) assertRead("CLUE", clueId.longValue(), username);
        else if (attachment.get("source_inspection_id") instanceof Number inspectionId) assertRead("INSPECTION", inspectionId.longValue(), username);
        else throw new BizException(2146, "附件来源记录无效");
    }

    public void lockAttachmentForVoid(Map<String, Object> attachment, String username) {
        if (attachment.get("case_id") != null) throw new BizException(2146, "来源材料已归案，请在对应案件办理附件作废");
        if (attachment.get("source_clue_id") instanceof Number clueId) lockWritable("CLUE", clueId.longValue(), username);
        else if (attachment.get("source_inspection_id") instanceof Number inspectionId) {
            Map<String, Object> inspection = row("INSPECTION", inspectionId.longValue(), false);
            if (inspection.get("clue_id") instanceof Number clue) {
                // Never take an inspection and then an existing clue: adoption locks clue -> inspection.
                Map<String, Object> lockedClue = row("CLUE", clue.longValue(), true);
                Map<String, Object> lockedInspection = row("INSPECTION", inspectionId.longValue(), true);
                if (!(lockedInspection.get("clue_id") instanceof Number current) || current.longValue() != clue.longValue())
                    throw new BizException(2146, "检查来源已变化，请刷新后办理附件");
                materials.lockActor(username, true);
                assertWritable("CLUE", clue.longValue(), lockedClue, username);
            } else {
                Map<String, Object> lockedInspection = row("INSPECTION", inspectionId.longValue(), true);
                materials.lockActor(username, true);
                // Conversion may have committed while this request waited. Do not reverse the lock order to follow it.
                if (lockedInspection.get("clue_id") != null)
                    throw new BizException(2146, "检查已转线索，请刷新后在对应来源办理附件");
                assertWritable("INSPECTION", inspectionId.longValue(), lockedInspection, username);
            }
        } else throw new BizException(2146, "附件来源记录无效");
    }
}
