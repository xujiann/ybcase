package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人员变动闭环：按承办人聚合在办案件、批量移交。
 * 人员离职时管理员要的是"张三名下还有几件、哪几件"这张清单，逐案进详情点移交做不到；
 * 移交本身仍逐案复用 CaseService.transferOwner（接收人启用校验 + 站内消息），此处只做批量编排。
 */
@Service
@RequiredArgsConstructor
public class OwnerTransferService {

    private final JdbcTemplate jdbc;
    private final CaseService caseService;
    private final MaterialAccess materialAccess;

    /** 在办案件：未办结、未终止（含中止——中止案件同样要有人接手恢复） */
    static final String ACTIVE = " status not in ('CLOSED','TERMINATED') ";

    // 有效期限 = 法定期限 + 扣除期间顺延，与督办看板 caseNearDeadline 同一表达式，
    // 且同样只对期限仍在计时的三个状态计超期（决定作出后 90 日期限即停表）
    private static final String EFFECTIVE_DEADLINE =
            "cf.deadline_at + coalesce((select sum(coalesce(e.end_at, current_date) - e.start_at)::int"
            + " from case_period_exclusion e where e.case_id = cf.id), 0)";

    /** 每个承办人的在办/超期件数（含已停用账号——这正是"停用后残留案件"的发现口） */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> owners() {
        var actor = requireCurrentReader();
        String department = actor != null && actor.district() ? " and cf.owner_dept_id = ?" : "";
        return jdbc.queryForList(
                "select cf.owner_user as username, su.real_name, coalesce(su.enabled, false) as enabled,"
                + " count(*) filter (where cf." + ACTIVE + ") as active_cases,"
                + " count(*) filter (where cf.status in ('INVESTIGATING','REPORTED','NOTIFIED')"
                + "   and " + EFFECTIVE_DEADLINE + " < current_date) as overdue_cases"
                + " from case_file cf left join sys_user su on su.username = cf.owner_user"
                + " where cf.owner_user is not null" + department
                + " group by cf.owner_user, su.real_name, su.enabled"
                + " order by active_cases desc, cf.owner_user",
                department.isEmpty() ? new Object[0] : new Object[]{actor.deptId()});
    }

    /** 可接收案件的账号：启用且具办案/负责人/法制角色。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> candidates() {
        var actor = requireCurrentReader();
        String department = actor != null && actor.district() ? " and u.dept_id = ?" : "";
        return jdbc.queryForList("""
                select distinct u.username, u.real_name from sys_user u
                join sys_user_role ur on ur.user_id = u.id
                join sys_role r on r.id = ur.role_id
                where u.enabled = true and r.code in ('HANDLER','LEADER','LEGAL')
                """ + department + " order by u.username",
                department.isEmpty() ? new Object[0] : new Object[]{actor.deptId()});
    }

    private WorkflowUserAccess.User requireCurrentReader() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // Keep existing authless domain callers; HTTP entry always carries the authenticated principal.
        if (auth == null) return null;
        if (!auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()))
            throw new BizException(403, "当前账号无权查看承办移交清单");
        // Authorize and read the catalog from one snapshot, including roles and token version.
        // This is a read check; eligibility is locked and rechecked again during actual transfer.
        var actor = materialAccess.readActor(auth.getName());
        if (actor.roles().stream().noneMatch(Set.of("LEADER", "LEGAL", "ADMIN")::contains))
            throw new BizException(403, "当前岗位无权查看承办移交清单");
        return actor;
    }

    /** 批量移交：caseIds 为空则移交 fromOwner 名下全部在办案件；整批一个事务，任一失败全部回滚 */
    @Transactional
    public Map<String, Object> transfer(String fromOwner, String toOwner, List<Long> caseIds, String operator) {
        if (fromOwner == null || fromOwner.isBlank()) throw new BizException(2110, "移出承办人不能为空");
        if (fromOwner.equals(toOwner)) throw new BizException(2111, "接收人不能与移出承办人相同");
        var actor = materialAccess.departmentScope(operator);
        String department = actor.district() ? " and owner_dept_id = ? " : "";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, case_no from case_file where owner_user = ? and" + ACTIVE + department + "order by id for update",
                actor.district() ? new Object[]{fromOwner, actor.deptId()} : new Object[]{fromOwner});
        if (rows.isEmpty()) throw new BizException(2110, "承办人 " + fromOwner + " 不存在或名下无在办案件");
        if (caseIds != null && !caseIds.isEmpty()) {
            List<Long> owned = rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
            List<Long> stray = caseIds.stream().filter(id -> !owned.contains(id)).toList();
            // 指定了不属于该承办人的案件多半是界面陈旧数据，静默跳过会让操作者以为移交成功
            if (!stray.isEmpty()) throw new BizException(2112, "案件 " + stray + " 不属于该承办人或已不在办");
            rows = rows.stream().filter(r -> caseIds.contains(((Number) r.get("id")).longValue())).toList();
        }
        List<String> caseNos = new java.util.ArrayList<>();
        for (var r : rows) {
            caseService.transferOwner(((Number) r.get("id")).longValue(), toOwner, operator);
            caseNos.add((String) r.get("case_no"));
        }
        return Map.of("count", caseNos.size(), "caseNos", caseNos);
    }
}
