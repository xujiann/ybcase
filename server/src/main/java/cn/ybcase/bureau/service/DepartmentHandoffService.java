package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DepartmentHandoffService {
    private static final Set<String> STATES = Set.of("PENDING", "ACCEPTED", "REJECTED", "CANCELLED");
    private final JdbcTemplate jdbc;
    private final MaterialAccess materials;
    private final WorkflowUserAccess users;

    public record Request(String sourceType, Long sourceId, Long targetDeptId, String reason) {}
    public record Decision(Boolean accept, String opinion) {}
    public record Cancellation(String opinion) {}
    public record InspectionAssignment(String username, Long expectedAssignedUserId, String reason) {}

    private enum Source {
        CLUE("case_clue", "clue_id", "clue_no"),
        INSPECTION("inspection", "inspection_id", "insp_no"),
        CASE("case_file", "case_id", "case_no");

        final String table;
        final String reference;
        final String number;

        Source(String table, String reference, String number) {
            this.table = table;
            this.reference = reference;
            this.number = number;
        }

        static Source parse(String value) {
            if (value != null) {
                for (Source source : values()) if (source.name().equals(value)) return source;
            }
            throw invalid("请选择线索、检查或案件");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(String username, String status, int page, int size) {
        return list(username, status, page, size, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(String username, String status, int page, int size, Long focusId) {
        var actor = materials.readActor(username);
        requireManager(actor);
        if (page < 1 || size < 1 || size > 100 || status != null && !STATES.contains(status))
            throw invalid("机构交接分页或状态无效");
        if (focusId != null && focusId < 1) throw invalid("待办来源编号须为正整数");
        StringBuilder where = new StringBuilder(" where 1=1");
        var args = new ArrayList<>();
        if (actor.district()) {
            where.append(" and (h.from_dept_id=? or h.to_dept_id=?)");
            args.add(actor.deptId());
            args.add(actor.deptId());
        }
        if (focusId != null) {
            where.append(" and h.id=?");
            args.add(focusId);
            page = 1;
            size = 1;
        } else if (status != null) {
            where.append(" and h.status=?");
            args.add(status);
        }
        Long total = jdbc.queryForObject("select count(*) from department_handoff h" + where, Long.class, args.toArray());
        // can_decide only discloses an action; the receiver and source are checked again in decide().
        boolean leader = actor.roles().contains("LEADER");
        var rowArgs = new ArrayList<>();
        rowArgs.add(actor.deptId());
        rowArgs.add(leader && actor.accountCategory() != null);
        rowArgs.add(!actor.district());
        rowArgs.add(actor.deptId());
        rowArgs.add(actor.deptId());
        rowArgs.add(leader);
        rowArgs.add(actor.accountCategory());
        rowArgs.addAll(args);
        rowArgs.add(size);
        rowArgs.add((long) (page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select h.id, h.source_no, h.from_dept_id, h.to_dept_id,
                       case when h.clue_id is not null then 'CLUE'
                            when h.inspection_id is not null then 'INSPECTION' else 'CASE' end as source_type,
                       coalesce(h.clue_id,h.inspection_id,h.case_id) as source_id,
                       src.name as from_department, dest.name as to_department,
                       sender.real_name as requested_by, receiver.real_name as decided_by,
                       h.reason, h.status, h.requested_at, h.decided_at, h.opinion,
                       coalesce(h.to_dept_id=? and ? and h.status='PENDING', false) as can_decide,
                       coalesce((? or h.from_dept_id=?) and h.status='PENDING', false) as can_cancel,
                       coalesce(h.to_dept_id=? and ? and dest.enabled and dest.org_kind=?
                           and i.clue_id is null and i.owner_dept_id=h.to_dept_id
                           and h.id=(select max(current_handoff.id) from department_handoff current_handoff
                                     where current_handoff.inspection_id=i.id and current_handoff.status='ACCEPTED'),
                           false) as can_assign_inspection
                from department_handoff h left join sys_dept src on src.id=h.from_dept_id
                join sys_dept dest on dest.id=h.to_dept_id
                join sys_user sender on sender.id=h.requested_by
                left join sys_user receiver on receiver.id=h.decided_by
                left join inspection i on i.id=h.inspection_id
                """ + where + " order by h.id desc limit ? offset ?", rowArgs.toArray());
        return Map.of("total", total, "rows", rows, "page", page, "size", size);
    }

    @Transactional
    public long request(Request request, String username) {
        if (request == null || request.sourceId() == null || request.sourceId() < 1 || request.targetDeptId() == null)
            throw invalid("请填写交接来源和接收机构");
        String reason = text(request.reason(), "交接原因");
        Source source = Source.parse(request.sourceType());
        Map<String, Object> row = lockSource(source, request.sourceId());
        requireTarget(request.targetDeptId());
        var actor = materials.lockActor(username, true);
        requireManager(actor);
        Long department = owner(row);
        if (actor.district() && (actor.deptId() == null || !actor.deptId().equals(department)))
            throw forbidden();
        if (Objects.equals(department, request.targetDeptId()))
            throw invalid("接收机构必须与现有所属机构不同");
        requireTransferable(source, request.sourceId(), row);
        if (exists("select exists(select 1 from department_handoff where status='PENDING' and "
                + source.reference + "=?)", request.sourceId()))
            throw invalid("该来源已有待处理的机构交接");

        var created = jdbc.queryForList("insert into department_handoff (" + source.reference
                        + ",source_no,from_dept_id,to_dept_id,requested_by,reason) values(?,?,?,?,?,?)"
                        + " on conflict do nothing returning id", Long.class,
                request.sourceId(), row.get(source.number), department, request.targetDeptId(), actor.id(), reason);
        if (created.isEmpty()) throw invalid("该来源已有待处理的机构交接");
        return created.getFirst();
    }

    @Transactional
    public void decide(long id, Decision decision, String username) {
        if (decision == null || decision.accept() == null) throw invalid("请选择接收或驳回");
        String opinion = text(decision.opinion(), "处理意见");
        var initial = handoff(id, false);
        Source source = initial.get("clue_id") != null ? Source.CLUE
                : initial.get("inspection_id") != null ? Source.INSPECTION : Source.CASE;
        long sourceId = ((Number) initial.get(source.reference)).longValue();
        // The handoff's source binding is immutable. Lock source -> handoff -> department -> actor.
        var row = lockSource(source, sourceId);
        var record = handoff(id, true);
        long target = ((Number) record.get("to_dept_id")).longValue();
        String targetKind = requireTarget(target);
        var actor = materials.lockActor(username, true);
        if (!actor.roles().contains("LEADER") || !Objects.equals(actor.deptId(), target)
                || !targetKind.equals(actor.accountCategory()))
            throw forbidden();
        if (!"PENDING".equals(record.get("status"))) throw invalid("交接事项已处理");
        if (decision.accept()) {
            if (!Objects.equals(owner(row), record.get("from_dept_id")))
                throw invalid("来源所属机构已变化，请驳回本次交接并重新提交");
            requireTransferable(source, sourceId, row);
            switch (source) {
                case CLUE -> jdbc.update("update case_clue set owner_dept_id=?, handler=? where id=?",
                        target, username, sourceId);
                case INSPECTION -> jdbc.update("update inspection set owner_dept_id=?, assigned_user_id=null where id=?", target, sourceId);
                case CASE -> jdbc.update("update case_file set owner_dept_id=?, owner_user=? where id=?",
                        target, username, sourceId);
            }
        }
        recordDecision(id, decision.accept() ? "ACCEPTED" : "REJECTED", actor.id(), opinion);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> inspectionAssignees(long id, String username) {
        var actor = materials.readActor(username);
        var record = handoff(id, false);
        long inspectionId = inspectionId(record);
        long department = departmentId(record);
        String kind = targetKind(department, false);
        requireReceivingLeader(actor, department, kind);
        var rows = jdbc.queryForList("select * from inspection where id=?", inspectionId);
        if (rows.isEmpty()) throw forbidden();
        var inspection = rows.getFirst();
        requireCurrentInspectionHandoff(id, inspectionId, record, inspection);
        var candidates = jdbc.queryForList("""
                select u.id, u.username, u.real_name from sys_user u
                join sys_user_role ur on ur.user_id=u.id join sys_role r on r.id=ur.role_id
                where u.enabled and u.dept_id=? and u.account_category=? and u.id<>? and r.code='HANDLER'
                order by u.real_name,u.id
                """, department, kind, actor.id());
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("candidates", candidates);
        result.put("assignedUserId", inspection.get("assigned_user_id"));
        return result;
    }

    @Transactional
    public void assignInspection(long id, InspectionAssignment assignment, String username) {
        if (assignment == null || assignment.username() == null || assignment.username().isBlank()
                || assignment.username().length() > 64 || assignment.username().indexOf('\0') >= 0)
            throw invalid("请选择接收机构的承办账号");
        String reason = text(assignment.reason(), "指派原因");
        var initial = handoff(id, false);
        long inspectionId = inspectionId(initial);
        // Same lock order as acceptance. Actor rows are acquired together in user-ID order.
        var inspection = lockSource(Source.INSPECTION, inspectionId);
        var record = handoff(id, true);
        long department = departmentId(record);
        String kind = requireTarget(department);
        var locked = users.lockUsers(username, assignment.username());
        var actor = materials.lockActor(username, true);
        requireReceivingLeader(actor, department, kind);
        var assignee = locked.get(assignment.username());
        if (assignee == null || !assignee.enabled() || !Objects.equals(assignee.deptId(), department)
                || !kind.equals(assignee.accountCategory()) || !assignee.roles().contains("HANDLER")
                || actor.id().equals(assignee.id()))
            throw invalid("请选择本机构已启用的办案员，负责人可按原权限直接办理");
        requireCurrentInspectionHandoff(id, inspectionId, record, inspection);
        Long previous = (Long) inspection.get("assigned_user_id");
        if (assignee.id().equals(previous)) return;
        if (!Objects.equals(previous, assignment.expectedAssignedUserId()))
            throw invalid("承办人已变化，请刷新后核对");
        jdbc.update("update inspection set assigned_user_id=? where id=?", assignee.id(), inspectionId);
        jdbc.update("""
                insert into inspection_assignment(inspection_id,handoff_id,department_id,previous_user_id,assigned_user_id,assigned_by,reason)
                values (?,?,?,?,?,?,?)
                """, inspectionId, id, department, previous, assignee.id(), actor.id(), reason);
    }

    private void requireCurrentInspectionHandoff(long id, long inspectionId, Map<String, Object> record,
                                                 Map<String, Object> inspection) {
        Long latest = jdbc.queryForObject("""
                select max(id) from department_handoff where inspection_id=? and status='ACCEPTED'
                """, Long.class, inspectionId);
        if (!"ACCEPTED".equals(record.get("status")) || !Objects.equals(latest, id)
                || !Objects.equals(owner(inspection), departmentId(record)) || inspection.get("clue_id") != null)
            throw invalid("仅当前已接收且未转线索的检查可指派");
    }

    private static long inspectionId(Map<String, Object> record) {
        if (!(record.get("inspection_id") instanceof Number id)) throw forbidden();
        return id.longValue();
    }

    private static long departmentId(Map<String, Object> record) {
        return ((Number) record.get("to_dept_id")).longValue();
    }

    private static void requireReceivingLeader(WorkflowUserAccess.User actor, long department, String kind) {
        if (!actor.roles().contains("LEADER") || !Objects.equals(actor.deptId(), department)
                || !kind.equals(actor.accountCategory())) throw forbidden();
    }

    @Transactional
    public void cancel(long id, Cancellation cancellation, String username) {
        String opinion = text(cancellation == null ? null : cancellation.opinion(), "撤回原因");
        // Cancellation never touches the source. This lock follows the same handoff -> actor order as decide().
        var record = handoff(id, true);
        var actor = materials.lockActor(username, true);
        requireManager(actor);
        if (actor.district() && !Objects.equals(actor.deptId(), record.get("from_dept_id"))) throw forbidden();
        if (!"PENDING".equals(record.get("status"))) throw invalid("交接事项已处理");
        recordDecision(id, "CANCELLED", actor.id(), opinion);
    }

    private void recordDecision(long id, String status, long actorId, String opinion) {
        jdbc.update("""
                update department_handoff set status=?, decided_by=?, decided_at=now(), opinion=? where id=?
                """, status, actorId, opinion, id);
    }

    private Map<String, Object> lockSource(Source source, long id) {
        var rows = jdbc.queryForList("select * from " + source.table + " where id=? for update", id);
        if (rows.isEmpty()) throw forbidden();
        return rows.getFirst();
    }

    private Map<String, Object> handoff(long id, boolean lock) {
        var rows = jdbc.queryForList("select * from department_handoff where id=?" + (lock ? " for update" : ""), id);
        if (rows.isEmpty()) throw forbidden();
        return rows.getFirst();
    }

    private String requireTarget(long department) {
        return targetKind(department, true);
    }

    private String targetKind(long department, boolean lock) {
        var kinds = jdbc.queryForList("""
                select org_kind from sys_dept where id=? and enabled and org_kind in ('DISTRICT','BUREAU')
                """ + (lock ? " for share" : ""), String.class, department);
        if (kinds.isEmpty()) throw invalid("请选择已启用的区市县或局机关接收机构");
        return kinds.getFirst();
    }

    private void requireTransferable(Source source, long id, Map<String, Object> row) {
        if (source == Source.CLUE && (!"PENDING".equals(row.get("status"))
                || exists("select exists(select 1 from case_file where clue_id=?)", id)))
            throw invalid("只有未办结且未立案的线索可交接");
        if (source == Source.INSPECTION && row.get("clue_id") != null)
            throw invalid("检查已转线索，请对对应线索或案件办理交接");
        if (source == Source.CASE && ("CLOSED".equals(row.get("status")) || row.get("archive_no") != null))
            throw invalid("已结案或已归档的案件不可交接");
        if (source != Source.INSPECTION && exists(
                "select exists(select 1 from biz_approval where status='PENDING' and " + source.reference + "=?)", id))
            throw invalid("存在待审批事项，请办结后再交接");
    }

    private boolean exists(String sql, long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, id));
    }

    private static Long owner(Map<String, Object> row) {
        return row.get("owner_dept_id") instanceof Number dept ? dept.longValue() : null;
    }

    private static void requireManager(WorkflowUserAccess.User actor) {
        if (!actor.privileged() || actor.district() && actor.deptId() == null) throw forbidden();
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 512 || value.indexOf('\0') >= 0)
            throw invalid(label + "须为不超过512字的非空文本");
        return value.strip();
    }

    private static BizException invalid(String message) {
        return new BizException(2190, message);
    }

    private static BizException forbidden() {
        return new BizException(2080, "机构交接事项不存在或不在当前机构范围内");
    }
}
