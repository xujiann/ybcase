package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseDecision;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/** 文书中心：模板渲染（要素+模板分离）、案卷目录编排与齐全性检查（第57条）、案件大事记（第4/35条全过程记录） */
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final CaseFileRepository caseRepository;
    private final CaseCauseRepository causeRepository;
    private final CaseNoticeRepository noticeRepository;
    private final CaseDecisionRepository decisionRepository;
    private final BureauConfig config;
    private final JdbcTemplate jdbc;

    /** 案卷装订顺序（一案一卷，按文书类型排序，第57条） */
    private static final List<String> ARCHIVE_ORDER = List.of(
            "FILING_APPROVAL", "OTHER", "SCENE_RECORD", "INQUIRY_RECORD", "PRESERVE_DECISION",
            "SEAL_DECISION", "ASSIST_LETTER", "APPROVAL_FORM", "SUSPEND_DECISION", "RESUME_NOTICE",
            "FINAL_REPORT", "NOTICE", "HEARING_RECORD", "LEGAL_OPINION", "MEETING_RECORD",
            "DECISION", "DELIVERY_RECEIPT", "ORDER_CORRECT", "URGE_LETTER",
            "TRANSFER_LETTER", "ACCEPT_RECEIPT", "TERMINATE_DECISION", "CLOSE_REPORT");

    /** 普通程序处罚案件必备文书（齐全性检查）。法制审核意见是**条件必备**，见 requiredFor */
    private static final List<String> REQUIRED_NORMAL_PUNISH = List.of(
            "FILING_APPROVAL", "FINAL_REPORT", "NOTICE", "DECISION",
            "DELIVERY_RECEIPT", "CLOSE_REPORT");

    /** 终止调查类案件必备（第47条：无告知/决定，改为终止调查决定书） */
    private static final List<String> REQUIRED_TERMINATED = List.of(
            "FILING_APPROVAL", "TERMINATE_DECISION", "CLOSE_REPORT");

    /** 移送司法类必备（第12条移送函 + 受案回执） */
    private static final List<String> REQUIRED_JUDICIAL = List.of(
            "FILING_APPROVAL", "TRANSFER_LETTER", "CLOSE_REPORT");

    /** 决定书名称按类型取：只有 PUNISH 才是"行政处罚决定书"，其余四类文书名不同且无文号 */
    private static final Map<String, String> DECISION_TITLES = Map.of(
            "PUNISH", "行政处罚决定书", "NO_PUNISH", "不予行政处罚决定书",
            "NOT_ESTABLISHED", "违法事实不成立决定书", "TRANSFER_ADMIN", "案件移送书",
            "TRANSFER_JUDICIAL", "涉嫌犯罪案件移送书");

    private static final Map<String, String> APPROVAL_KIND_NAMES = Map.of(
            "FILE_CASE", "立案审批表", "EXTEND", "延期审批表", "SUSPEND", "中止调查审批表",
            "TERMINATE", "终止调查审批表", "DEFER", "暂缓分期缴纳审批表");

    /** 按模板渲染文书草稿 */
    public Map<String, String> render(Long caseId, String docType) {
        CaseFile c = caseRepository.findById(caseId)
                .orElseThrow(() -> new BizException(2043, "案件不存在"));
        var rows = jdbc.queryForList("select title_tpl, content_tpl from doc_template where doc_type = ?", docType);
        if (rows.isEmpty()) throw new BizException(2053, "该文书类型无模板，可直接手工制作");
        var cause = causeRepository.findById(c.getCauseId()).orElse(null);
        var notice = noticeRepository.findTopByCaseIdOrderByIdDesc(caseId).orElse(null);
        var decision = decisionRepository.findByCaseId(caseId).orElse(null);

        Map<String, String> vars = new HashMap<>();
        vars.put("caseNo", c.getCaseNo());
        vars.put("caseName", c.getName());
        vars.put("partyName", c.getPartyName());
        vars.put("partyAddress", nv(c.getPartyAddress()));
        vars.put("partyCreditNo", nv(c.getPartyCreditNo()));
        vars.put("partyLegalRep", nv(c.getPartyLegalRep()));
        vars.put("partyTypeText", switch (c.getPartyType()) {
            case "AGENCY" -> "经办机构"; case "PROVIDER" -> "定点医药机构";
            case "INDIVIDUAL" -> "自然人"; default -> "其他主体"; });
        vars.put("summary", nv(c.getSummary()));
        vars.put("amountInvolved", c.getAmountInvolved() == null ? "0" : c.getAmountInvolved().toPlainString());
        vars.put("causeText", cause == null ? "" : cause.getCategory() + "——" + cause.getDescription());
        vars.put("basisText", basisText(c));
        vars.put("orgName", config.str("org_name", "医疗保障局"));
        vars.put("today", LocalDate.now().toString());
        vars.put("statementDays", String.valueOf(config.intVal("statement_deadline_days", 3)));
        vars.put("hearingDays", String.valueOf(config.intVal("hearing_request_days", 3)));
        vars.put("proposedFine", notice == null ? "" : notice.getProposedFine().toPlainString());
        vars.put("proposedRecoup", notice == null ? "" : notice.getProposedRecoup().toPlainString());
        vars.put("decisionNo", decision == null || decision.getDecisionNo() == null ? "（决定后生成）" : decision.getDecisionNo());
        vars.put("decisionContent", decision == null ? "" : nv(decision.getContent()));
        // 责令改正通知书的改正期限须承办人填写：替换成空串会渲染出"并于前将…"这种缺法定要素
        // 且无任何待填痕迹的文书，故保留醒目占位提示（与 basisText 缺失时的自身约定一致）
        vars.put("dueAt", "（请填写改正期限）");

        String title = cn.ybcase.bureau.common.TemplateUtil.fill((String) rows.get(0).get("title_tpl"), vars);
        String content = cn.ybcase.bureau.common.TemplateUtil.fill((String) rows.get(0).get("content_tpl"), vars);
        return Map.of("docType", docType, "title", title, "content", content);
    }

    /**
     * 卷内件全集：已制作的文书（case_document） + 由结构化记录渲染出的法定材料。
     * 立案审批表在 biz_approval、法制审核意见在 case_review、集体讨论在 case_meeting、
     * 听证笔录在 case_hearing、送达回证在 case_delivery——这五类恰是案卷评查与复议应诉时
     * 最先被翻的必备件，此前一件都不进卷内目录与合成打印，办案人只能逐个页面截屏手工插页。
     * 去重：同类型若已有手工录入的文书，以手工件为准，不再重复上目录。
     */
    public List<Map<String, Object>> archiveEntries(Long caseId) {
        List<Map<String, Object>> docs = new ArrayList<>(jdbc.queryForList(
                "select id, doc_type, title, content, made_at, maker, signed from case_document where case_id = ?",
                caseId));
        docs.forEach(d -> d.put("source", "DOC"));
        // 刻意不做去重。手工文书与结构化记录之间没有可靠的身份关联，
        // 按类型去重会在手工录过一份告知书后把两次告知（再告知加重，第52条须载明变更理由）
        // 的记录整体抹掉；按"类型+日期"去重在同日再告知时同样丢失。
        // 丢记录是法律问题、重复只是观感问题，故两者并列，用标题与 source 标明来源。
        docs.addAll(recordEntries(caseId));
        docs.sort(Comparator
                .<Map<String, Object>>comparingInt(d -> {
                    int i = ARCHIVE_ORDER.indexOf((String) d.get("doc_type"));
                    return i < 0 ? ARCHIVE_ORDER.size() : i;   // 未登记类型排最后而非最前
                })
                .thenComparing(d -> String.valueOf(d.get("made_at") == null ? "" : d.get("made_at"))));
        int seq = 1;
        for (var d : docs) d.put("seq", seq++);
        return docs;
    }

    /** 案卷目录：按法定顺序编排 + 必备文书齐全性检查 */
    public Map<String, Object> archiveCatalog(Long caseId) {
        CaseFile c = caseRepository.findById(caseId)
                .orElseThrow(() -> new BizException(2043, "案件不存在"));
        List<Map<String, Object>> docs = archiveEntries(caseId);
        var decision = decisionRepository.findByCaseId(caseId).orElse(null);
        List<String> required = requiredFor(c, decision);
        List<String> missing = new ArrayList<>();
        if (!required.isEmpty()) {
            Set<String> present = new HashSet<>();
            docs.forEach(d -> present.add((String) d.get("doc_type")));
            required.forEach(t -> { if (!present.contains(t)) missing.add(t); });
        }
        return Map.of("archiveNo", nv(c.getArchiveNo()), "catalog", docs, "missing", missing);
    }

    /** 按案件出口选必备清单：处罚 / 终止调查 / 移送司法 三类的卷内必备件不同 */
    private List<String> requiredFor(CaseFile c, CaseDecision decision) {
        if ("TERMINATED".equals(c.getStatus())) {
            return decision != null && String.valueOf(decision.getDecisionType()).startsWith("TRANSFER")
                    ? REQUIRED_JUDICIAL : REQUIRED_TERMINATED;
        }
        if ("NORMAL".equals(c.getProcedureType()) && decision != null
                && "PUNISH".equals(decision.getDecisionType())) {
            List<String> req = new ArrayList<>(REQUIRED_NORMAL_PUNISH);
            // 第37条法制审核的范围是条件式的（默认 THRESHOLD：达数额较大或经听证才须审核）。
            // 无条件把 LEGAL_OPINION 计入必备，会让所有小额处罚案件恒被判"必备文书缺失"，
            // 开启齐全性强制后更直接卡死结案——而正规补审路径已被状态守卫堵死（submitReview
            // 限 REPORTED/NOTIFIED），唯一出路将是伪造一份本不属法定审核范围的意见书。
            if (legalReviewRequired(c.getId(), decision)) req.add("LEGAL_OPINION");
            return req;
        }
        return List.of();
    }

    /**
     * 本案是否属法制审核范围（第37条）——与 CaseService.decide() 的 needReview 同一套口径。
     * 两处若各写一遍，任何对审核范围的调整都会让"能不能决定"与"卷齐不齐"再次漂移。
     */
    public boolean legalReviewRequired(Long caseId, CaseDecision d) {
        if ("ALL".equalsIgnoreCase(config.str("legal_review_mode", "THRESHOLD"))) return true;
        java.math.BigDecimal fine = d == null || d.getFineAmount() == null
                ? java.math.BigDecimal.ZERO : d.getFineAmount();
        if (fine.compareTo(config.decimal("legal_review_fine_threshold", "100000")) >= 0) return true;
        Integer held = jdbc.queryForObject(
                "select count(*) from case_notice where case_id = ? and hearing_held_at is not null",
                Integer.class, caseId);
        return held != null && held > 0;
    }

    /** 由结构化记录渲染的虚拟卷内件（不落库，随查随生成） */
    private List<Map<String, Object>> recordEntries(Long caseId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var a : jdbc.queryForList("""
                select kind, reason, applicant, approver, opinion, decided_at
                from biz_approval where case_id = ? and status = 'APPROVED' order by id""", caseId)) {
            String kind = (String) a.get("kind");
            out.add(entry("FILE_CASE".equals(kind) ? "FILING_APPROVAL" : "APPROVAL_FORM",
                    APPROVAL_KIND_NAMES.getOrDefault(kind, "审批表"),
                    "申请人：" + sv(a.get("applicant")) + "\n申请事由：" + sv(a.get("reason"))
                            + "\n批准人：" + sv(a.get("approver")) + "\n批准意见：" + sv(a.get("opinion"))
                            + "\n批准时间：" + dt(a.get("decided_at")),
                    dt(a.get("decided_at")), sv(a.get("approver"))));
        }
        // 告知书与决定书同样只存在于 case_notice / case_decision：不手工制作文书就不进卷，
        // 而它们恰是普通程序处罚案卷里最核心的两件（第39/44条）
        for (var n : jdbc.queryForList("""
                select notified_at, content, proposed_fine, proposed_recoup, statement_deadline,
                       hearing_entitled, hearing_requested, hearing_held_at, statement,
                       statement_review, statement_waived, change_reason
                from case_notice where case_id = ? order by id""", caseId)) {
            out.add(entry("NOTICE", "行政处罚告知书",
                    "告知日期：" + dt(n.get("notified_at"))
                            + "\n拟处罚内容：" + sv(n.get("content"))
                            + "\n拟罚款：" + sv(n.get("proposed_fine"))
                            + "　拟退回基金：" + sv(n.get("proposed_recoup"))
                            + "\n陈述申辩期限：" + dt(n.get("statement_deadline"))
                            + "\n听证权利：" + (Boolean.TRUE.equals(n.get("hearing_entitled")) ? "已告知" : "未达听证标准")
                            + "，当事人" + (Boolean.TRUE.equals(n.get("hearing_requested")) ? "已申请听证" : "未申请听证")
                            + (n.get("hearing_held_at") == null ? "" : "，听证举行于 " + dt(n.get("hearing_held_at")))
                            + "\n当事人陈述申辩：" + (Boolean.TRUE.equals(n.get("statement_waived"))
                                ? "明确放弃" : sv(n.get("statement")))
                            + "\n复核意见：" + sv(n.get("statement_review"))
                            + (n.get("change_reason") == null ? "" : "\n变更理由：" + sv(n.get("change_reason"))),
                    dt(n.get("notified_at")), ""));
        }
        for (var dd : jdbc.queryForList("""
                select decision_type, decision_no, fine_amount, recoup_amount, confiscate_amount,
                       other_measures, content, decided_at, mitigation, discretion_reason,
                       gov_record_no, published, published_at
                from case_decision where case_id = ? order by id""", caseId)) {
            String dno = sv(dd.get("decision_no"));
            out.add(entry("DECISION",
                    DECISION_TITLES.getOrDefault(sv(dd.get("decision_type")), "处理决定书")
                            + (dno.isEmpty() ? "" : " " + dno),
                    "文号：" + sv(dd.get("decision_no")) + "\n决定类型：" + sv(dd.get("decision_type"))
                            + "\n作出日期：" + dt(dd.get("decided_at"))
                            + "\n罚款：" + sv(dd.get("fine_amount"))
                            + "　退回基金：" + sv(dd.get("recoup_amount"))
                            + "　没收违法所得：" + sv(dd.get("confiscate_amount"))
                            + "\n其他措施：" + sv(dd.get("other_measures"))
                            + "\n决定内容：" + sv(dd.get("content"))
                            + "\n从轻/减轻情节：" + sv(dd.get("mitigation"))
                            + "\n裁量理由：" + sv(dd.get("discretion_reason"))
                            + "\n政府备案号：" + sv(dd.get("gov_record_no"))
                            + "\n公开：" + (Boolean.TRUE.equals(dd.get("published"))
                                ? "已公开于 " + dt(dd.get("published_at")) : "未公开"),
                    dt(dd.get("decided_at")), ""));
        }
        for (var r : jdbc.queryForList("""
                select reviewer, opinion_type, opinion, submitted_at, reviewed_at, required_reason, passed
                from case_review where case_id = ? and reviewed_at is not null order by id""", caseId)) {
            out.add(entry("LEGAL_OPINION", "法制审核意见书",
                    "审核事由：" + sv(r.get("required_reason")) + "\n提交日期：" + dt(r.get("submitted_at"))
                            + "\n审核人：" + sv(r.get("reviewer")) + "\n意见类型：" + sv(r.get("opinion_type"))
                            + "\n审核意见：" + sv(r.get("opinion"))
                            + "\n结论：" + (Boolean.TRUE.equals(r.get("passed")) ? "通过" : "未通过"),
                    dt(r.get("reviewed_at")), sv(r.get("reviewer"))));
        }
        for (var m : jdbc.queryForList("""
                select held_at, attendees, record, conclusion, sign_confirmed
                from case_meeting where case_id = ? order by id""", caseId)) {
            out.add(entry("MEETING_RECORD", "负责人集体讨论记录",
                    "讨论时间：" + dt(m.get("held_at")) + "\n参加人员：" + sv(m.get("attendees"))
                            + "\n讨论记录：" + sv(m.get("record")) + "\n讨论结论：" + sv(m.get("conclusion"))
                            + "\n签字确认：" + (Boolean.TRUE.equals(m.get("sign_confirmed")) ? "已签字确认" : "未签字确认"),
                    dt(m.get("held_at")), sv(m.get("attendees"))));
        }
        for (var h : jdbc.queryForList("""
                select notice_sent_at, scheduled_at, held_at, host, host_dept, recorder, record, opinion
                from case_hearing where case_id = ? and status <> 'CANCELLED' order by id""", caseId)) {
            out.add(entry("HEARING_RECORD", "听证笔录",
                    "通知送达：" + dt(h.get("notice_sent_at")) + "\n计划举行：" + dt(h.get("scheduled_at"))
                            + "\n实际举行：" + dt(h.get("held_at")) + "\n主持人：" + sv(h.get("host"))
                            + "（" + sv(h.get("host_dept")) + "）\n记录员：" + sv(h.get("recorder"))
                            + "\n笔录：" + sv(h.get("record")) + "\n听证意见：" + sv(h.get("opinion")),
                    dt(h.get("held_at")), sv(h.get("recorder"))));
        }
        for (var d : jdbc.queryForList("""
                select method, delivered_at, receiver, note, receipt_no, receipt_signed_at
                from case_delivery where case_id = ? order by id""", caseId)) {
            out.add(entry("DELIVERY_RECEIPT", "送达回证",
                    "送达方式：" + sv(d.get("method")) + "\n受送达人：" + sv(d.get("receiver"))
                            + "\n送达日期：" + dt(d.get("delivered_at"))
                            + "\n回证编号：" + sv(d.get("receipt_no"))
                            + "\n回证签收日：" + dt(d.get("receipt_signed_at"))
                            + "\n备注：" + sv(d.get("note")),
                    dt(d.get("delivered_at")), sv(d.get("receiver"))));
        }
        return out;
    }

    private static Map<String, Object> entry(String type, String title, String content,
                                             String madeAt, String maker) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", null);            // 虚拟件无 case_document 行，故无 id、不可签章/送达
        m.put("doc_type", type);
        m.put("title", title + "（系统记录）");   // 与手工制作的同名文书区分
        m.put("content", content);
        m.put("made_at", madeAt.isEmpty() ? null : madeAt);
        m.put("maker", maker);
        m.put("signed", false);
        m.put("source", "RECORD");    // 前端据此隐藏签章/送达等只对实体文书有意义的操作
        return m;
    }

    /** 卷内件里的日期/时间：时间戳收敛到分钟——微秒精度（2026-08-31 12:41:59.293882）不该进法律文书 */
    private static String dt(Object v) {
        if (v == null) return "";
        String t = String.valueOf(v);
        return t.length() > 16 && t.charAt(10) == ' ' ? t.substring(0, 16) : t;
    }

    /** nv 的 Object 版：卷内件由 Map 取值渲染，字段类型各异（日期/布尔/数值） */
    private static String sv(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 案件大事记：全过程记录时间轴 */
    public List<Map<String, Object>> timeline(Long caseId) {
        CaseFile c = caseRepository.findById(caseId)
                .orElseThrow(() -> new BizException(2043, "案件不存在"));
        List<Map<String, Object>> events = new ArrayList<>();
        add(events, c.getFiledAt(), "立案", c.getCaseNo() + " " + c.getName());
        add(events, c.getSuspendedAt(), "中止调查", nv(c.getSuspendReason()));
        add(events, c.getTerminatedAt(), "终止调查", nv(c.getTerminateReason()));
        add(events, c.getReportedAt(), "调查终结", "调查终结报告");
        add(events, c.getDecidedAt(), "处理决定", "");
        add(events, c.getDeliveredAt(), "送达", "");
        add(events, c.getClosedAt(), "结案归档", nv(c.getArchiveNo()));
        jdbc.queryForList("select obtained_at, name, type from case_evidence where case_id = ?", caseId)
                .forEach(r -> add(events, toDate(r.get("obtained_at")), "取证", (String) r.get("name")));
        jdbc.queryForList("select made_at, title from case_document where case_id = ?", caseId)
                .forEach(r -> add(events, toDate(r.get("made_at")), "文书", (String) r.get("title")));
        jdbc.queryForList("select held_at, conclusion from case_meeting where case_id = ?", caseId)
                .forEach(r -> add(events, toDate(r.get("held_at")), "集体讨论", (String) r.get("conclusion")));
        jdbc.queryForList("select paid_at, kind, amount from case_execution where case_id = ?", caseId)
                .forEach(r -> add(events, toDate(r.get("paid_at")), "执行", r.get("kind") + " " + r.get("amount")));
        for (var rev : jdbc.queryForList(
                "select submitted_at, reviewed_at, opinion_type from case_review where case_id = ?", caseId)) {
            add(events, toDate(rev.get("submitted_at")), "提交法制审核", "");
            add(events, toDate(rev.get("reviewed_at")), "法制审核完成", nv((String) rev.get("opinion_type")));
        }
        jdbc.queryForList("select notified_at, hearing_held_at from case_notice where case_id = ?", caseId)
                .forEach(r -> {
                    add(events, toDate(r.get("notified_at")), "处罚告知", "");
                    add(events, toDate(r.get("hearing_held_at")), "听证", "");
                });
        events.sort(Comparator.comparing(e -> (LocalDate) e.get("date")));
        return events;
    }

    public List<String> missingRequiredDocs(Long caseId) {
        return (List<String>) archiveCatalog(caseId).get("missing");
    }

    private String basisText(CaseFile c) {
        if (c.getEnforceItemId() == null) return "（请关联执法事项以带出法律依据）";
        var items = jdbc.queryForList("select basis_refs from law_enforce_item where id = ?", c.getEnforceItemId());
        if (items.isEmpty()) return "";
        String refs = (String) items.get(0).get("basis_refs");
        // 简单匹配依据库条文全文（按 law_name+article 关键词命中）
        StringBuilder sb = new StringBuilder(refs);
        for (var b : jdbc.queryForList("select law_name, article, content from law_basis")) {
            if (refs.contains((String) b.get("law_name")) || refs.contains("《" + b.get("law_name") + "》")) {
                String art = (String) b.get("article");
                String shortRef = ((String) b.get("law_name")).replace("中华人民共和国", "");
                if (refs.contains(art) || refs.contains(art.replace("第", "").replace("条", ""))
                        || refs.contains(shortRef)) {
                    sb.append("\n").append(b.get("law_name")).append(art).append("：").append(b.get("content"));
                }
            }
        }
        return sb.toString();
    }

    private static void add(List<Map<String, Object>> events, LocalDate date, String kind, String detail) {
        if (date != null) {
            Map<String, Object> m = new HashMap<>();
            m.put("date", date);
            m.put("kind", kind);
            m.put("detail", detail);
            events.add(m);
        }
    }

    private static LocalDate toDate(Object v) {
        return v == null ? null : ((java.sql.Date) v).toLocalDate();
    }

    private static String nv(String v) {
        return v == null ? "" : v;
    }
}
