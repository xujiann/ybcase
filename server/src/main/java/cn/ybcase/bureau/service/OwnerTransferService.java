package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

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

    /** 在办案件：未办结、未终止（含中止——中止案件同样要有人接手恢复） */
    static final String ACTIVE = " status not in ('CLOSED','TERMINATED') ";

    // 有效期限 = 法定期限 + 扣除期间顺延，与督办看板 caseNearDeadline 同一表达式，
    // 且同样只对期限仍在计时的三个状态计超期（决定作出后 90 日期限即停表）
    private static final String EFFECTIVE_DEADLINE =
            "cf.deadline_at + coalesce((select sum(coalesce(e.end_at, current_date) - e.start_at)::int"
            + " from case_period_exclusion e where e.case_id = cf.id), 0)";

    /** 每个承办人的在办/超期件数（含已停用账号——这正是"停用后残留案件"的发现口） */
    public List<Map<String, Object>> owners() {
        return jdbc.queryForList(
                "select cf.owner_user as username, su.real_name, coalesce(su.enabled, false) as enabled,"
                + " count(*) filter (where cf." + ACTIVE + ") as active_cases,"
                + " count(*) filter (where cf.status in ('INVESTIGATING','REPORTED','NOTIFIED')"
                + "   and " + EFFECTIVE_DEADLINE + " < current_date) as overdue_cases"
                + " from case_file cf left join sys_user su on su.username = cf.owner_user"
                + " where cf.owner_user is not null"
                + " group by cf.owner_user, su.real_name, su.enabled"
                + " order by active_cases desc, cf.owner_user");
    }

    /** 可接收案件的账号：启用且具办案/负责人/法制角色（用户列表是 ADMIN 独占，负责人移交时需要独立的候选口） */
    public List<Map<String, Object>> candidates() {
        return jdbc.queryForList("""
                select distinct u.username, u.real_name from sys_user u
                join sys_user_role ur on ur.user_id = u.id
                join sys_role r on r.id = ur.role_id
                where u.enabled = true and r.code in ('HANDLER','LEADER','LEGAL')
                order by u.username""");
    }

    /** 批量移交：caseIds 为空则移交 fromOwner 名下全部在办案件；整批一个事务，任一失败全部回滚 */
    @Transactional
    public Map<String, Object> transfer(String fromOwner, String toOwner, List<Long> caseIds, String operator) {
        if (fromOwner == null || fromOwner.isBlank()) throw new BizException(2110, "移出承办人不能为空");
        if (fromOwner.equals(toOwner)) throw new BizException(2111, "接收人不能与移出承办人相同");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, case_no from case_file where owner_user = ? and" + ACTIVE + "order by id", fromOwner);
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
