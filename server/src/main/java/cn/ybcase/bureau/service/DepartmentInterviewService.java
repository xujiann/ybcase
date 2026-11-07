package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DepartmentInterviewService {
    private static final Set<String> READ_ROLES = Set.of("HANDLER", "LEADER", "LEGAL", "ADMIN");
    private static final Set<String> SUPERVISOR_ROLES = Set.of("LEADER", "LEGAL", "ADMIN");
    private static final String SELECT = """
            select id, department_name, institution_code, institution_name, interview_type,
                   reason, content, participants, interview_date, dept_id, created_by, source_agreement_id
            from department_interview
            where interview_date >= ? and interview_date < ?
            """;
    private static final String ORDER = " order by interview_date, department_name, id";
    private final JdbcTemplate jdbc;
    private final MaterialAccess materialAccess;

    public record InterviewInput(String institutionCode, String institutionName, String interviewType,
                                 String reason, String content, String participants, LocalDate interviewDate) {}

    public record InterviewRow(long id, String departmentName, String institutionCode, String institutionName,
                               String interviewType, String reason, String content, String participants,
                               LocalDate interviewDate, boolean editable) {}

    public record InterviewPage(List<InterviewRow> rows, long total) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<InterviewRow> list(int year, String username) {
        var scope = readScope(username);
        return rows(scope, listPeriodStart(year), null, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InterviewPage page(int year, int page, int size, String username) {
        var scope = readScope(username);
        var start = listPeriodStart(year);
        if (page < 1 || size < 1 || size > 100)
            throw new BizException(2100, "页码须大于零，每页须为1至100条");
        long total = jdbc.queryForObject("""
                select count(*) from department_interview where interview_date >= ? and interview_date < ?
                """ + scope.deptFilter(), Long.class, scope.params(start, null, null));
        long offset = (page - 1L) * size;
        return new InterviewPage(offset >= total ? List.of() : rows(scope, start, size, offset), total);
    }

    private Scope readScope(String username) {
        var actor = materialAccess.readActor(username);
        if (actor.roles().stream().noneMatch(READ_ROLES::contains))
            throw new BizException(403, "当前岗位无权查看约谈台账");
        String category = category(actor.id());
        boolean supervisor = isBureauSupervisor(actor, category);
        if (!supervisor) requireDepartment(actor, category);
        return new Scope(actor, supervisor);
    }

    @Transactional
    public void register(InterviewInput input, String username) {
        var actor = materialAccess.lockActor(username, true);
        String departmentName = requireDepartment(actor, category(actor.id()));
        var value = validate(input);
        jdbc.update("""
                insert into department_interview (dept_id, department_name, institution_code,
                    institution_name, interview_type, reason, content, participants, interview_date, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, actor.deptId(), departmentName, value.institutionCode(), value.institutionName(),
                value.interviewType(), value.reason(), value.content(), value.participants(), value.interviewDate(), actor.id());
    }

    @Transactional
    public void edit(long id, InterviewInput input, String username) {
        var actor = materialAccess.lockActor(username, true);
        requireDepartment(actor, category(actor.id()));
        var value = validate(input);
        int changed = jdbc.update("""
                update department_interview set institution_code = ?, institution_name = ?, interview_type = ?,
                    reason = ?, content = ?, participants = ?, interview_date = ?, updated_at = now()
                where id = ? and dept_id = ? and (created_by = ? or ?) and source_agreement_id is null
                """, value.institutionCode(), value.institutionName(), value.interviewType(), value.reason(),
                value.content(), value.participants(), value.interviewDate(), id, actor.deptId(), actor.id(), canManageDepartment(actor));
        if (changed == 0) throw new BizException(403, "约谈记录不存在或不在当前账号编辑范围内");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportExport export(int year, LocalDate start, LocalDate cutoff, boolean reconcile, String username) {
        return export(year, start, null, cutoff, reconcile, username);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportExport export(int year, LocalDate start, LocalDate end, LocalDate cutoff, boolean reconcile, String username) {
        var actor = materialAccess.readActor(username);
        if (!isBureauSupervisor(actor, category(actor.id())))
            throw new BizException(403, "全局约谈台账由局机关授权岗位导出");
        var period = ReportExport.AnnualPeriod.of(year, start, end, cutoff, LocalDate.now());
        List<Object[]> rows = new ArrayList<>();
        var budget = new ReportExport.Budget();
        List<ReportExport.Line> lines = new ArrayList<>();
        ReportExport.query(jdbc, SELECT + ORDER,
                rs -> {
                    budget.add(rows, new Object[]{rows.size() + 1, rs.getString("department_name"),
                            rs.getString("institution_code"), rs.getString("institution_name"),
                            rs.getString("interview_type"), rs.getString("reason"), rs.getString("content"),
                            rs.getString("participants"), rs.getObject("interview_date", LocalDate.class)});
                    lines.add(ReportExport.Line.of("INTERVIEW", rs.getLong("id"), rs.getString("institution_code"),
                            0, rows.size() + 3, rows.getLast()));
                },
                period.start(), period.end().plusDays(1));
        var sheet = new PenaltyReportWorkbook.Sheet(rows, 'I', Map.of("A1", year + "年医保部门约谈定点医药机构台账"));
        List<Object[]> notes = reconcile || start != null || cutoff != null ? List.of(
                new Object[]{"核对项目", "口径及数量"},
                new Object[]{"约谈日期范围", period.start() + " 至 " + period.end() + "（含首尾）"},
                new Object[]{"统计截止日", period.cutoff()},
                new Object[]{"合计数据行", rows.size()},
                new Object[]{"取数来源", "按约谈台账登记的实际约谈日期筛选，采用已登记的部门名称和当前修订内容。"}
        ) : List.of();
        return new ReportExport(PenaltyReportWorkbook.write("department-interviews.xlsx", List.of(sheet), notes),
                new ReportExport.Audit(ReportExport.Kind.INTERVIEWS, period.start(), period.end(), period.cutoff(), rows.size()), lines);
    }

    private static LocalDate listPeriodStart(int year) {
        if (year < 1900 || year > LocalDate.now().getYear())
            throw new BizException(2100, "请选择1900年至当年的统计年度");
        return LocalDate.of(year, 1, 1);
    }

    private List<InterviewRow> rows(Scope scope, LocalDate start, Integer limit, Long offset) {
        var actor = scope.actor();
        boolean canWrite = actor.roles().contains("HANDLER") || canManageDepartment(actor);
        return jdbc.query(SELECT + scope.deptFilter() + ORDER + (limit == null ? "" : " limit ? offset ?"),
                (rs, index) -> new InterviewRow(rs.getLong("id"), rs.getString("department_name"),
                        rs.getString("institution_code"), rs.getString("institution_name"),
                        rs.getString("interview_type"), rs.getString("reason"), rs.getString("content"),
                        rs.getString("participants"), rs.getObject("interview_date", LocalDate.class),
                        canWrite && rs.getObject("source_agreement_id") == null
                                && actor.deptId() != null && actor.deptId().equals(rs.getLong("dept_id"))
                                && (canManageDepartment(actor) || actor.id().equals(rs.getLong("created_by")))),
                scope.params(start, limit, offset));
    }

    private record Scope(WorkflowUserAccess.User actor, boolean allDepartments) {
        String deptFilter() {
            return allDepartments ? "" : " and dept_id = ?";
        }

        Object[] params(LocalDate start, Integer limit, Long offset) {
            List<Object> values = new ArrayList<>(List.of(start, start.plusYears(1)));
            if (!allDepartments) values.add(actor.deptId());
            if (limit != null) {
                values.add(limit);
                values.add(offset);
            }
            return values.toArray();
        }
    }

    private String category(Long userId) {
        return jdbc.queryForObject("select account_category from sys_user where id = ?", String.class, userId);
    }

    private String requireDepartment(WorkflowUserAccess.User actor, String category) {
        if (!("DISTRICT".equals(category) || "BUREAU".equals(category)) || actor.deptId() == null)
            throw new BizException(403, "请先由管理员设置账号类别和所属单位／处室");
        var department = jdbc.queryForList("select name from sys_dept where id = ? and enabled", String.class, actor.deptId());
        if (department.isEmpty()) throw new BizException(403, "账号所属单位／处室未启用");
        return department.getFirst();
    }

    private static boolean isBureauSupervisor(WorkflowUserAccess.User actor, String category) {
        return "BUREAU".equals(category) && actor.roles().stream().anyMatch(SUPERVISOR_ROLES::contains);
    }

    private static boolean canManageDepartment(WorkflowUserAccess.User actor) {
        return actor.roles().contains("LEADER") || actor.roles().contains("ADMIN");
    }

    static InterviewInput validate(InterviewInput input) {
        if (input == null || input.interviewDate() == null || input.interviewDate().getYear() < 1900
                || input.interviewDate().isAfter(LocalDate.now()))
            throw new BizException(2100, "请填写实际约谈日期，日期不得晚于今天");
        return new InterviewInput(required(input.institutionCode(), 64, "机构编码"),
                required(input.institutionName(), 128, "机构名称"),
                required(input.interviewType(), 64, "约谈类型"),
                required(input.reason(), 1000, "约谈事由"),
                required(input.content(), 12000, "约谈内容"),
                required(input.participants(), 512, "约谈人员"), input.interviewDate());
    }

    private static String required(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf(0) >= 0)
            throw new BizException(2100, name + "须填写，且不得超过" + max + "字");
        return value.strip();
    }
}
