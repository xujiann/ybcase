package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseClue;
import cn.ybcase.bureau.repository.CaseClueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** 线索管理（第14条：15个工作日内核查并决定是否立案，特殊情况经主要负责人批准延长15个工作日） */
@Service
@RequiredArgsConstructor
public class ClueService {

    private final CaseClueRepository clueRepository;
    private final BureauConfig cfg;
    private final BizSeqService seq;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final SourceAttachmentAccess sourceAccess;
    private final MaterialAccess materialAccess;

    public record ClueCreateReq(String source, String content, String suspectName,
                                String suspectType, LocalDate receivedAt, String handler) {}

    /** Trusted domain callers supply a recorded creator rather than acting on the HTTP principal. */
    @Transactional
    public CaseClue create(ClueCreateReq req, String username) {
        return create(req, username, materialAccess.departmentScope(username).deptId());
    }

    @Transactional
    public CaseClue createForCurrentActor(ClueCreateReq req, String username) {
        return create(req, username, materialAccess.creationDepartment(username));
    }

    // A converted inspection or an already-authorized import passes its captured organization.
    CaseClue create(ClueCreateReq req, String username, Long ownerDeptId) {
        if (req.content() == null || req.content().isBlank()) throw new BizException(2020, "线索内容不能为空");
        LocalDate received = req.receivedAt() != null ? req.receivedAt() : LocalDate.now();
        CaseClue clue = new CaseClue();
        clue.setClueNo("XS" + received.getYear() + String.format("%04d", seq.next("CLUE", received.getYear())));
        clue.setSource(req.source());
        clue.setContent(req.content());
        clue.setSuspectName(req.suspectName());
        clue.setSuspectType(req.suspectType());
        clue.setReceivedAt(received);
        clue.setDeadlineAt(cfg.plusByUnit(received, cfg.intVal("clue_verify_days", 15), "clue_verify_day_unit"));
        clue.setHandler(req.handler());
        clue.setCreatedBy(username);
        clue.setOwnerDeptId(ownerDeptId);
        return clueRepository.save(clue);
    }

    /** 延长核查期限：仅限一次，再延 15 个工作日（第14条） */
    @Transactional
    public CaseClue extend(Long id, String reason, String approver) {
        if (!canApproveExtension(approver)) throw new BizException(403, "线索延期须由主要负责人对应岗位批准");
        jdbc.queryForList("select id from case_clue where id = ? for update", id);
        // Authentication and the first role check may both predate this source-row wait.
        var actor = materialAccess.lockActor(approver, false);
        if (!canApproveExtension(approver)) throw new BizException(403, "线索延期须由主要负责人对应岗位批准");
        if (actor.district()) sourceAccess.assertRead("CLUE", id, approver);
        CaseClue clue = get(id);
        if (!"PENDING".equals(clue.getStatus())) throw new BizException(2021, "线索已办结，不可延期");
        if (Boolean.TRUE.equals(clue.getExtended())) throw new BizException(2022, "核查期限只能延长一次（第14条）");
        if (reason == null || reason.isBlank()) throw new BizException(2023, "延期须说明特殊情况并经主要负责人批准");
        clue.setExtended(true);
        clue.setExtendReason(reason);
        clue.setDeadlineAt(cfg.plusByUnit(clue.getDeadlineAt(), cfg.intVal("clue_extend_days", 15), "clue_verify_day_unit"));
        clue.setExtendApprovedBy(approver);
        clue.setExtendApprovedAt(java.time.Instant.now());
        return clueRepository.save(clue);
    }

    public boolean canApproveExtension(String username) {
        // This setting grants approval authority. Do not reuse the general 30-second parameter cache.
        var values = jdbc.queryForList("select cfg_value from sys_config where cfg_key='clue_extension_approver_role'", String.class);
        String role = values.isEmpty() || values.get(0) == null ? "LEADER" : values.get(0);
        Integer n = jdbc.queryForObject("""
                select count(*) from sys_user u join sys_user_role ur on ur.user_id = u.id
                join sys_role r on r.id = ur.role_id where u.username = ? and u.enabled = true
                and r.code in ('ADMIN', ?)""", Integer.class, username,
                role);
        return n != null && n > 0;
    }

    @Transactional
    public java.util.Map<String, Object> importMonitor(java.util.List<java.util.Map<String, String>> rows, String username) {
        materialAccess.requireCurrentRoles("HANDLER", "LEADER", "ADMIN");
        return importMonitor(rows, username, materialAccess.departmentScope(username).deptId());
    }

    @Transactional
    public java.util.Map<String, Object> importMonitorForCurrentActor(java.util.List<java.util.Map<String, String>> rows,
                                                                    String username) {
        return importMonitor(rows, username, materialAccess.creationDepartment(username));
    }

    private java.util.Map<String, Object> importMonitor(java.util.List<java.util.Map<String, String>> rows,
                                                        String username, Long ownerDeptId) {
        if (rows == null || rows.isEmpty() || rows.size() > 500)
            throw new BizException(2067, "导入清单须为1至500条");
        for (var row : rows) {
            if (row == null || row.get("content") == null || row.get("content").isBlank())
                throw new BizException(2020, "每条导入线索须填写内容，整批未导入");
        }
        var nos = new java.util.ArrayList<String>();
        for (var row : rows) nos.add(create(new ClueCreateReq("MONITOR", row.get("content"), row.get("suspectName"),
                row.getOrDefault("suspectType", "PROVIDER"), LocalDate.now(), row.get("handler")), username, ownerDeptId).getClueNo());
        return java.util.Map.of("imported", nos.size(), "clueNos", nos);
    }

    /** 核查期限扣除（辽15条：鉴定、检验时间不计入核查期限） */
    @Transactional
    public CaseClue addExclusion(Long id, int days, String reason, String username) {
        lockClueWriter(id, username);
        CaseClue clue = get(id);
        if (!"PENDING".equals(clue.getStatus())) throw new BizException(2021, "线索已办结");
        if (days <= 0) throw new BizException(2026, "扣除天数须为正数");
        if (reason == null || reason.isBlank()) throw new BizException(2026, "须说明鉴定/检验事由");
        int total;
        try { total = Math.addExact(clue.getExcludedDays(), days); }
        catch (ArithmeticException ex) { throw new BizException(2026, "累计扣除天数超过支持范围"); }
        clue.setExcludedDays(total);
        clue.setDeadlineAt(clue.getDeadlineAt().plusDays(days));
        clue.setExtendReason((clue.getExtendReason() == null ? "" : clue.getExtendReason() + "；")
                + "期限扣除" + days + "日：" + reason);
        return clueRepository.save(clue);
    }

    /** 不予立案 */
    @Transactional
    public CaseClue reject(Long id, String verifyResult, String username) {
        lockClueWriter(id, username);
        CaseClue clue = get(id);
        if (!"PENDING".equals(clue.getStatus())) throw new BizException(2021, "线索已办结");
        if (verifyResult == null || verifyResult.isBlank()) throw new BizException(2024, "不予立案须记录核查结果");
        clue.setStatus("REJECTED");
        clue.setVerifyResult(verifyResult);
        return clueRepository.save(clue);
    }

    private void lockClueWriter(Long id, String username) {
        // Source -> actor order matches filing, source attachments and transfers. Once the
        // actor is held, the shared source helper checks the current roles and ownership.
        jdbc.queryForList("select id from case_clue where id=? for update", id);
        materialAccess.lockActor(username, true);
        sourceAccess.lockClueForHandling(id, username);
    }

    /** 立案时由 CaseService 回调标记 */
    @Transactional
    public void markFiled(Long id, String verifyResult) {
        // 原子占用：读已提交下"先查 PENDING 再改"会让同一线索被两个并发立案同时通过，生成两个案件
        int n = jdbc.update("""
                update case_clue set status = 'FILED',
                       verify_result = coalesce(?, verify_result)
                where id = ? and status = 'PENDING'""",
                verifyResult == null || verifyResult.isBlank() ? null : verifyResult, id);
        if (n == 0) {
            get(id);  // 不存在则抛 2025，存在则说明已办结
            throw new BizException(2021, "线索已办结，不可重复立案");
        }
    }

    public CaseClue get(Long id) {
        return clueRepository.findById(id).orElseThrow(() -> new BizException(2025, "线索不存在"));
    }

    /** A task link uses one scoped SQL lookup, independent of list filters and capped legacy pages. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public java.util.Map<String, Object> focus(String username, long focusId) {
        if (focusId < 1) throw new BizException(2025, "待办来源编号须为正整数");
        var actor = materialAccess.readActor(username);
        if (actor.roles().contains("AGENCY") || actor.roles().contains("INTEGRATION")
                || actor.roles().stream().noneMatch(java.util.Set.of("HANDLER","LEGAL","LEADER","ADMIN")::contains))
            throw new BizException(403, "当前岗位无权查看线索");
        var scope = sourceAccess.clueReadPredicate("case_clue.id", username);
        var args = new java.util.ArrayList<Object>(scope.args());
        args.add(focusId);
        var rows = jdbc.queryForList("select * from case_clue where " + scope.sql() + " and id=?", args.toArray());
        return java.util.Map.of("rows", rows, "total", rows.size(), "page", 1, "size", 1);
    }

    /**
     * 分页+关键词检索（线索编号/嫌疑人/内容）+ 受理日期区间。与 CaseService.pageList 同模式
     * （count + limit/offset，JdbcTemplate 蛇形列名）；区市县账号按来源机构收窄。
     */
    public java.util.Map<String, Object> pageList(String status, String q, LocalDate from, LocalDate to,
                                                  int page, int size) {
        return pageList(status, null, q, from, to, page, size);
    }

    public java.util.Map<String, Object> pageList(String status, String source, String q, LocalDate from, LocalDate to,
                                                  int page, int size) {
        return pageList(status, source, q, from, to, page, size, null);
    }

    public java.util.Map<String, Object> pageList(String status, String source, String q, LocalDate from, LocalDate to,
                                                  int page, int size, String districtUsername) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (districtUsername != null) {
            var scope = sourceAccess.clueReadPredicate("case_clue.id", districtUsername);
            where.append(" and ").append(scope.sql());
            args.addAll(scope.args());
        }
        if (status != null && !status.isBlank()) {
            where.append(" and status = ? ");
            args.add(status);
        }
        if (source != null && !source.isBlank()) {
            where.append(" and source = ? ");
            args.add(source);
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (clue_no ilike ? or suspect_name ilike ? or content ilike ?) ");
            String like = "%" + q.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (from != null) {
            where.append(" and received_at >= ? ");
            args.add(from);
        }
        if (to != null) {
            where.append(" and received_at <= ? ");
            args.add(to);
        }
        Long total = jdbc.queryForObject("select count(*) from case_clue" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) (Math.max(page, 1) - 1) * size);   // int 乘法在大页号下溢出为负 offset，PG 报错被翻成 2101
        java.util.List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "select * from case_clue" + where + " order by id desc limit ? offset ?", args.toArray());
        return java.util.Map.of("total", total == null ? 0 : total, "page", page, "size", size, "rows", rows);
    }
}
