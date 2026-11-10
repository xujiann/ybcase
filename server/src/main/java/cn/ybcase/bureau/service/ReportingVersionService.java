package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportingVersionService {
    private static final String RULE_VERSION = "V48_BUSINESS_DATE_APPROVED_KNOWN_AT";
    private static final String PENALTY_RULE_VERSION = "PENALTIES_EFFECTIVE_DECISION_EXECUTION_V2";
    private static final String METADATA = """
            select b.id,b.report_type,b.status,b.period_start,b.period_end,b.business_cutoff,b.known_at,
                   b.criteria,b.template_version,b.template_sha256,b.rule_version,b.filename,b.file_sha256,
                   b.row_count,b.parent_batch_id,b.generated_username,b.generated_at,b.confirmed_username,
                   b.confirmed_at,b.confirmation_note,b.generated_by,b.confirmed_by,b.scope_label,
                   (b.confirmed_at at time zone 'Asia/Shanghai')::date as confirmed_day
            from report_frozen_batch b
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MaterialAccess materials;
    private final PenaltyReportService penalties;
    private final EnforcementWorkReportService enforcement;
    private final DepartmentInterviewService interviews;
    private final BusinessStatisticsService statistics;

    public record GenerateRequest(ReportExport.Kind type, Integer year, LocalDate start, LocalDate end,
                                  LocalDate cutoff, Long parentBatchId, UUID requestKey) {}
    public record Download(String filename, byte[] bytes, String sha256, ReportExport.Audit audit) {}
    public record Receipt(LocalDate submittedAt, String recipient, String receiptNo, String note) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(String username, ReportExport.Kind type, int page, int size) {
        var actor = reader(username);
        pageBounds(page, size);
        String filter = type == null ? "" : " where b.report_type=?";
        Object[] countArgs = type == null ? new Object[]{} : new Object[]{type.name()};
        long total = jdbc.queryForObject("select count(*) from report_frozen_batch b" + filter, Long.class, countArgs);
        List<Object> args = new ArrayList<>(List.of(countArgs));
        args.add(size); args.add((page - 1L) * size);
        var rows = jdbc.queryForList(METADATA + filter + " order by b.id desc limit ? offset ?", args.toArray())
                .stream().map(row -> metadata(row, actor)).toList();
        return Map.of("rows", rows, "total", total, "page", page, "size", size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(long id, String username) {
        var actor = reader(username);
        return metadata(batch(id), actor);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> generate(GenerateRequest request, String username) {
        var actor = materials.lockActor(username, false);
        requireReader(actor);
        validate(request);
        var existing = jdbc.queryForList(METADATA + " where b.generated_by=? and b.request_key=?",
                actor.id(), request.requestKey());
        if (!existing.isEmpty()) {
            if (!sameJson(json(criteria(request)), existing.getFirst().get("criteria")))
                throw invalid("同一生成请求号不能用于不同报表参数");
            return metadata(existing.getFirst(), actor);
        }
        var knownAt = jdbc.queryForObject("select current_timestamp", OffsetDateTime.class);
        boolean monthly = request.type() == ReportExport.Kind.MONTHLY_SUBMISSION;
        var submission = monthly
                ? statistics.monthlySnapshot(YearMonth.from(request.start()), request.cutoff(), knownAt, username)
                : null;
        ReportExport report = switch (request.type()) {
            case PENALTIES -> penalties.export(request.year(), request.start(), request.end(), request.cutoff());
            case ENFORCEMENT_WORK -> enforcement.export(request.start(), request.end(), true);
            case INTERVIEWS -> interviews.export(request.year(), request.start(), request.end(), request.cutoff(), true, username);
            case MONTHLY_SUBMISSION -> submission.report();
        };
        if (report.lines().size() != report.audit().rowCount() || report.lines().size() > ReportExport.MAX_ROWS
                || report.workbook().length == 0 || report.workbook().length > ReportExport.MAX_WORKBOOK_BYTES)
            throw invalid("报表文件与来源行不一致或超出容量");
        if (request.parentBatchId() != null) {
            var parent = batch(request.parentBatchId());
            if (!replaceable(parent) || !request.type().name().equals(parent.get("report_type"))
                    || !report.audit().start().equals(day(parent.get("period_start")))
                    || !report.audit().end().equals(day(parent.get("period_end")))
                    || !report.audit().cutoff().equals(day(parent.get("business_cutoff"))))
                throw invalid("重报前版须已确认，月度上报可关联确认后作废的批次。类型、业务区间及截至日须相同");
        }
        String templateHash;
        if (monthly) {
            templateHash = sha(String.join(",", BusinessStatisticsService.SUBMISSION_COLUMNS).getBytes(StandardCharsets.UTF_8));
        } else {
            try (var input = new ClassPathResource("reports/" + resource(request.type())).getInputStream()) {
                templateHash = sha(input.readAllBytes());
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("报表模板不可读", failure);
            }
        }
        String filename = monthly ? YearMonth.from(report.audit().start()) + "-医保执法统计上报.csv"
                : report.audit().start() + "至" + report.audit().end() + "-" + request.type().name() + ".xlsx";
        String ruleVersion = switch (request.type()) {
            case PENALTIES -> PENALTY_RULE_VERSION;
            case MONTHLY_SUBMISSION -> BusinessStatisticsService.SUBMISSION_RULE;
            default -> RULE_VERSION;
        };
        long id = jdbc.queryForObject("""
                insert into report_frozen_batch(request_key,report_type,period_start,period_end,business_cutoff,
                    known_at,criteria,template_version,template_sha256,rule_version,filename,workbook,file_sha256,
                    row_count,parent_batch_id,generated_by,generated_username,generator_dept_id,scope_label)
                values (?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?) returning id
                """, Long.class, request.requestKey(), request.type().name(), report.audit().start(), report.audit().end(),
                report.audit().cutoff(), knownAt, json(criteria(request)), templateVersion(request.type()), templateHash,
                ruleVersion, filename, report.workbook(), sha(report.workbook()), report.audit().rowCount(),
                request.parentBatchId(), actor.id(), actor.username(), actor.deptId(), monthly ? submission.scope() : null);
        long textBytes = 0;
        for (var line : report.lines()) {
            String cells = json(line.cells());
            textBytes += cells.getBytes(StandardCharsets.UTF_8).length;
            if (textBytes > 64L * 1024 * 1024) throw ReportExport.tooLarge();
            String content = json(line.cells().subList(1, line.cells().size()));
            jdbc.update("""
                    insert into report_frozen_row(batch_id,row_key,source_type,source_id,source_number,
                        sheet_index,row_number,cells,cells_sha256) values(?,?,?,?,?,?,?,?::jsonb,?)
                    """, id, line.key(), line.sourceType(), line.sourceId(), line.sourceNumber(),
                    line.sheetIndex(), line.rowNumber(), cells, sha(content.getBytes(StandardCharsets.UTF_8)));
        }
        return metadata(batch(id), actor);
    }

    @Transactional
    public Map<String, Object> confirm(long id, String opinion, String username) {
        if (opinion == null || opinion.isBlank() || opinion.length() > 2000 || opinion.indexOf(0) >= 0)
            throw invalid("确认意见须为不超过2000字的非空文本");
        // Confirm changes no key. This remains exclusive against another confirmer,
        // while allowing a concurrent reissue's parent FK KEY SHARE lock.
        var locks = jdbc.queryForList("select id from report_frozen_batch where id=? for no key update", id);
        if (locks.isEmpty()) throw invalid("报表批次不存在");
        var department = jdbc.queryForList("select dept_id from sys_user where username=?", Long.class, username);
        Long observed = department.isEmpty() ? null : department.getFirst();
        if (observed != null) jdbc.queryForList("select id from sys_dept where id=? for share", observed);
        var actor = materials.lockActor(username, false);
        if (!Objects.equals(observed, actor.deptId()) || !canConfirm(actor))
            throw new BizException(403, "须由已归类至局机关处室的另一名法制或负责人确认报表");
        var value = batch(id);
        if ("CONFIRMED".equals(value.get("status"))) {
            if (actor.id().equals(number(value.get("confirmed_by"))) && opinion.strip().equals(value.get("confirmation_note")))
                return metadata(value, actor);
            throw invalid("该冻结批次已确认");
        }
        if (!"GENERATED".equals(value.get("status"))) throw invalid("该批次已撤回、退回或作废，不能确认");
        if (actor.id().equals(number(value.get("generated_by"))))
            throw new BizException(403, "生成者不能确认本人生成的报表");
        long rows = jdbc.queryForObject("select count(*) from report_frozen_row where batch_id=?", Long.class, id);
        if (rows != ((Number) value.get("row_count")).longValue()) throw invalid("冻结来源行数量不一致");
        byte[] file = jdbc.queryForObject("select workbook from report_frozen_batch where id=?", byte[].class, id);
        if (file == null || !sha(file).equals(value.get("file_sha256"))) throw invalid("冻结文件摘要核对失败");
        jdbc.update("""
                update report_frozen_batch set status='CONFIRMED',confirmed_by=?,confirmed_username=?,
                    confirmer_dept_id=?,confirmed_at=now(),confirmation_note=? where id=? and status='GENERATED'
                """, actor.id(), actor.username(), actor.deptId(), opinion.strip(), id);
        return metadata(batch(id), actor);
    }

    @Transactional
    public Map<String,Object> recordReceipt(long id, Receipt receipt, String username) {
        if (receipt == null || receipt.submittedAt() == null) throw invalid("须填写实际报送日期");
        var recipient = receiptText(receipt.recipient(), 256, true);
        var receiptNo = receiptText(receipt.receiptNo(), 128, true);
        var note = receiptText(receipt.note(), 2000, false);
        if (jdbc.queryForList("select id from report_frozen_batch where id=? for no key update", id).isEmpty())
            throw invalid("报表批次不存在");
        var departments = jdbc.queryForList("select dept_id from sys_user where username=?", Long.class, username);
        Long department = departments.isEmpty() ? null : departments.getFirst();
        if (department != null) jdbc.queryForList("select id from sys_dept where id=? for share", department);
        var actor = materials.lockActor(username, false);
        if (!Objects.equals(department, actor.deptId()) || !canConfirm(actor))
            throw new BizException(403, "须由已归类的局机关法制或负责人登记实际报送回执");
        var batch = batch(id);
        if (!"MONTHLY_SUBMISSION".equals(batch.get("report_type")) || !"CONFIRMED".equals(batch.get("status")))
            throw invalid("仅可为已确认的月度上报快照登记回执");
        var confirmedDay = day(batch.get("confirmed_day"));
        if (receipt.submittedAt().isBefore(confirmedDay) || receipt.submittedAt().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            throw invalid("实际报送日期须在确认日与今天之间");
        var existing = receipt(id);
        if (existing != null) {
            if (actor.id().equals(number(existing.get("recorded_by"))) && receipt.submittedAt().equals(day(existing.get("submitted_at")))
                    && recipient.equals(existing.get("recipient")) && receiptNo.equals(existing.get("receipt_no"))
                    && Objects.equals(note, existing.get("note"))) return metadata(batch, actor);
            throw invalid("该批次已登记报送回执，请按关联更正流程保留原记录");
        }
        jdbc.update("""
                insert into report_submission_receipt(batch_id,submitted_at,recipient,receipt_no,note,recorded_by,recorded_username)
                values (?,?,?,?,?,?,?)
                """, id, receipt.submittedAt(), recipient, receiptNo, note, actor.id(), actor.username());
        return metadata(batch, actor);
    }

    @Transactional
    public Map<String,Object> transition(long id,String action,String opinion,String username) {
        if (!Set.of("WITHDRAW","RETURN","VOID").contains(Objects.toString(action,""))) throw invalid("无效的报表处理操作");
        if (opinion==null||opinion.isBlank()||opinion.length()>2000||opinion.indexOf(0)>=0) throw invalid("须填写不超过2000字的处理理由");
        if(jdbc.queryForList("select id from report_frozen_batch where id=? for no key update",id).isEmpty())throw invalid("报表批次不存在");
        var observed=jdbc.queryForList("select dept_id from sys_user where username=?",Long.class,username);
        if(!observed.isEmpty()&&observed.getFirst()!=null)jdbc.queryForList("select id from sys_dept where id=? for share",observed.getFirst());
        var actor=materials.lockActor(username,false);requireReader(actor);
        if(!Objects.equals(observed.isEmpty()?null:observed.getFirst(),actor.deptId()))throw new BizException(403,"账号所属机构已变化，请刷新核对");
        var value=batch(id);
        boolean own=actor.id().equals(number(value.get("generated_by")));
        if("WITHDRAW".equals(action)?!own:own||!canConfirm(actor))throw new BizException(403,"仅生成者可撤回；退回或作废须另一名已归类局机关法制或负责人办理");
        String status=switch(action){case "WITHDRAW"->"WITHDRAWN";case "RETURN"->"REJECTED";default->"VOIDED";};
        var old=jdbc.queryForList("select * from report_batch_event where batch_id=?",id);
        if(!old.isEmpty()){
            var event=old.getFirst();
            if(actor.id().equals(number(event.get("actor_id")))&&action.equals(event.get("event_type"))&&opinion.strip().equals(event.get("opinion")))return metadata(value,actor);
            throw invalid("该批次已终结，不能重复处理");
        }
        if(!"GENERATED".equals(value.get("status"))&&!("VOID".equals(action)&&"CONFIRMED".equals(value.get("status"))))throw invalid("当前批次不能执行该操作");
        jdbc.update("insert into report_batch_event(batch_id,event_type,previous_status,resulting_status,actor_id,actor_username,opinion) values(?,?,?,?,?,?,?)",id,action,value.get("status"),status,actor.id(),actor.username(),opinion.strip());
        jdbc.update("update report_frozen_batch set status=? where id=?",status,id);
        return metadata(batch(id),actor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> rows(long id, String username, int page, int size) {
        reader(username);
        pageBounds(page, size);
        var batch = batch(id);
        var result = jdbc.queryForList("""
                select row_key,source_type,source_id,source_number,sheet_index,row_number,cells
                from report_frozen_row where batch_id=? order by sheet_index,row_number limit ? offset ?
                """, id, size, (page - 1L) * size).stream().map(this::row).toList();
        return Map.of("rows", result, "total", batch.get("row_count"), "page", page, "size", size,
                "columns", columns(ReportExport.Kind.valueOf((String) batch.get("report_type"))));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> changes(long id, String username, int page, int size) {
        reader(username);
        pageBounds(page, size);
        var value = batch(id);
        Long parent = number(value.get("parent_batch_id"));
        if (parent == null) return Map.of("rows", List.of(), "total", 0, "page", page, "size", size);
        String comparison = """
                from (select * from report_frozen_row where batch_id=?) previous
                full join (select * from report_frozen_row where batch_id=?) current using(row_key)
                where previous.row_key is null or current.row_key is null
                      or previous.cells_sha256<>current.cells_sha256
                """;
        long total = jdbc.queryForObject("select count(*) " + comparison, Long.class, parent, id);
        var rows = jdbc.queryForList("""
                select coalesce(current.row_key,previous.row_key) row_key,
                       coalesce(current.source_type,previous.source_type) source_type,
                       coalesce(current.source_id,previous.source_id) source_id,
                       coalesce(current.source_number,previous.source_number) source_number,
                       case when previous.row_key is null then 'ADDED'
                            when current.row_key is null then 'REMOVED' else 'CHANGED' end change_type,
                       previous.cells previous_cells,current.cells current_cells
                """ + comparison + " order by row_key limit ? offset ?", parent, id, size, (page - 1L) * size)
                .stream().map(this::change).toList();
        return Map.of("rows", rows, "total", total, "page", page, "size", size, "parentBatchId", parent);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Download download(long id, String username) {
        reader(username);
        var values = jdbc.queryForList("select filename,workbook,file_sha256,report_type,period_start,period_end,business_cutoff,row_count from report_frozen_batch where id=?", id);
        if (values.isEmpty()) throw invalid("报表批次不存在");
        var row = values.getFirst();
        byte[] file = (byte[]) row.get("workbook");
        String checksum = (String) row.get("file_sha256");
        if (!sha(file).equals(checksum)) throw invalid("冻结文件摘要核对失败");
        return new Download((String) row.get("filename"), file, checksum,
                new ReportExport.Audit(ReportExport.Kind.valueOf((String) row.get("report_type")),
                        day(row.get("period_start")), day(row.get("period_end")), day(row.get("business_cutoff")),
                        ((Number) row.get("row_count")).intValue()));
    }

    private Map<String, Object> batch(long id) {
        var rows = jdbc.queryForList(METADATA + " where b.id=?", id);
        if (rows.isEmpty()) throw invalid("报表批次不存在");
        return rows.getFirst();
    }

    private WorkflowUserAccess.User reader(String username) {
        var actor = materials.readActor(username);
        requireReader(actor);
        return actor;
    }

    private static void requireReader(WorkflowUserAccess.User actor) {
        if (!"BUREAU".equals(actor.accountCategory())
                || actor.roles().stream().noneMatch(Set.of("LEGAL", "LEADER", "ADMIN")::contains))
            throw new BizException(403, "冻结报表由局机关授权岗位查看和生成");
    }

    private boolean canConfirm(WorkflowUserAccess.User actor) {
        return "BUREAU".equals(actor.accountCategory()) && actor.deptId() != null
                && (actor.roles().contains("LEGAL") || actor.roles().contains("LEADER"))
                && Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from sys_dept where id=? and enabled and org_kind='BUREAU')",
                    Boolean.class, actor.deptId()));
    }

    private Map<String, Object> metadata(Map<String, Object> row, WorkflowUserAccess.User actor) {
        var result = new LinkedHashMap<>(row);
        result.put("criteria", parse(row.get("criteria")));
        result.put("canConfirm", "GENERATED".equals(row.get("status")) && canConfirm(actor)
                && !actor.id().equals(number(row.get("generated_by"))));
        boolean own=actor.id().equals(number(row.get("generated_by")));
        result.put("canWithdraw","GENERATED".equals(row.get("status"))&&own);
        result.put("canReturn","GENERATED".equals(row.get("status"))&&!own&&canConfirm(actor));
        result.put("canVoid",Set.of("GENERATED","CONFIRMED").contains(row.get("status"))&&!own&&canConfirm(actor));
        result.put("canReissue",replaceable(row));
        if ("MONTHLY_SUBMISSION".equals(row.get("report_type"))) {
            var receipt = receipt(number(row.get("id")));
            result.put("submissionReceipt",receipt);
            result.put("canRecordReceipt",receipt==null && "CONFIRMED".equals(row.get("status")) && canConfirm(actor));
        }
        result.put("events",jdbc.queryForList("select event_type,previous_status,resulting_status,actor_username,opinion,created_at from report_batch_event where batch_id=? order by id",row.get("id")));
        result.put("columns", columns(ReportExport.Kind.valueOf((String) row.get("report_type"))));
        return result;
    }

    private Map<String,Object> receipt(long id) {
        var rows = jdbc.queryForList("""
                select submitted_at,recipient,receipt_no,note,recorded_by,recorded_username,recorded_at
                from report_submission_receipt where batch_id=?
                """, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String receiptText(String value, int max, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw invalid("须填写接收单位和回执编号");
            return null;
        }
        if (value.length() > max || value.indexOf(0) >= 0) throw invalid("报送回执字段无效");
        return value.strip();
    }

    private static boolean replaceable(Map<String,Object> row) {
        return "CONFIRMED".equals(row.get("status"))
                || "MONTHLY_SUBMISSION".equals(row.get("report_type")) && "VOIDED".equals(row.get("status"))
                && row.get("confirmed_at") != null;
    }

    private Map<String, Object> row(Map<String, Object> row) {
        var value = new LinkedHashMap<>(row);
        value.put("cells", parse(row.get("cells")));
        value.put("link", sourceLink((String) row.get("source_type"), ((Number) row.get("source_id")).longValue()));
        return value;
    }

    private Map<String, Object> change(Map<String, Object> row) {
        var value = new LinkedHashMap<>(row);
        value.put("previous_cells", parse(row.get("previous_cells")));
        value.put("current_cells", parse(row.get("current_cells")));
        value.put("link", sourceLink((String) row.get("source_type"), ((Number) row.get("source_id")).longValue()));
        return value;
    }

    private static String sourceLink(String type, long id) {
        return switch (type) {
            case "CASE" -> "/case/detail/" + id;
            case "INSPECTION" -> "/case/inspections?focus=" + id;
            case "INTERVIEW" -> "/case/interviews?interview=" + id;
            default -> "";
        };
    }

    private static Map<String, Object> criteria(GenerateRequest request) {
        var values = new LinkedHashMap<String, Object>();
        values.put("type", request.type()); values.put("year", request.year());
        values.put("start", request.start()); values.put("end", request.end());
        values.put("cutoff", request.cutoff()); values.put("parentBatchId", request.parentBatchId());
        return values;
    }

    private static void validate(GenerateRequest request) {
        if (request == null || request.type() == null || request.requestKey() == null)
            throw invalid("请选择报表类型并提供生成请求号");
        if (request.type() != ReportExport.Kind.ENFORCEMENT_WORK && request.year() == null)
            throw invalid("请选择报表年度");
        if (request.type() == ReportExport.Kind.ENFORCEMENT_WORK
                && (request.start() == null || request.end() == null
                    || request.cutoff() != null && !request.cutoff().equals(request.end())))
            throw invalid("执法人员报表须填写起止日期，截至日与结束日相同");
        if (request.type() == ReportExport.Kind.MONTHLY_SUBMISSION) {
            var start = request.start();
            var end = request.end();
            var cutoff = request.cutoff();
            if (start == null || end == null || cutoff == null || start.getYear() != request.year()
                    || start.getYear() < 1900 || start.getDayOfMonth() != 1
                    || !end.equals(YearMonth.from(start).atEndOfMonth()) || cutoff.isBefore(end)
                    || cutoff.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
                throw invalid("月度上报须选择一个已结束的自然月，且截至日在月末与今天之间");
        }
        if (request.parentBatchId() != null && request.parentBatchId() <= 0) throw invalid("前版批次无效");
    }

    private static void pageBounds(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw invalid("页码须大于零，每页须为1至100条");
    }

    private static String resource(ReportExport.Kind type) {
        return switch (type) {
            case PENALTIES -> "administrative-penalties-annual.xlsx";
            case ENFORCEMENT_WORK -> "enforcement-work.xlsx";
            case INTERVIEWS -> "department-interviews.xlsx";
            case MONTHLY_SUBMISSION -> throw new IllegalArgumentException("月度上报使用 CSV 表头");
        };
    }

    private static String templateVersion(ReportExport.Kind type) { return type.name() + "_FIELDS_R1"; }

    private static List<String> columns(ReportExport.Kind type) {
        return switch (type) {
            case PENALTIES -> List.of("序号", "立案时间", "处罚日期", "违法发生期间", "机构编码／证件号码", "机构／当事人",
                    "性质", "等级", "违法事实", "认定金额", "处罚依据及措施", "罚款金额", "基金是否全额追回",
                    "罚款是否全缴", "已缴罚款", "未缴罚款", "救济时效", "催告", "申请强制执行");
            case ENFORCEMENT_WORK -> List.of("序号", "部门", "主办人", "机构", "机构类型", "等级", "协办人",
                    "来源", "处理完成", "发现违法", "违法情形", "行政处罚", "协议处理", "调查状态", "移送",
                    "解除协议", "司法判决", "国家飞检", "省飞检", "市级检查", "备注");
            case INTERVIEWS -> List.of("序号", "部门", "机构编码", "机构名称", "约谈类型", "事由", "内容", "人员", "日期");
            case MONTHLY_SUBMISSION -> BusinessStatisticsService.SUBMISSION_COLUMNS;
        };
    }

    private Object parse(Object raw) {
        if (raw == null) return null;
        try { return mapper.readValue(raw.toString(), new TypeReference<Object>() {}); }
        catch (java.io.IOException failure) { throw new IllegalStateException("冻结报表数据不可读", failure); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw invalid("报表参数或行数据无效"); }
    }

    private boolean sameJson(String left, Object right) {
        try { return mapper.readTree(left).equals(mapper.readTree(right.toString())); }
        catch (java.io.IOException failure) { throw invalid("冻结参数不可读"); }
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static LocalDate day(Object value) { return value instanceof java.sql.Date date ? date.toLocalDate() : (LocalDate) value; }
    private static Long number(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private static BizException invalid(String message) { return new BizException(2195, message); }
}
