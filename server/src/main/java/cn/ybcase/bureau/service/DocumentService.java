package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
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
            "OTHER", "SCENE_RECORD", "INQUIRY_RECORD", "ASSIST_LETTER", "SUSPEND_DECISION",
            "RESUME_NOTICE", "FINAL_REPORT", "NOTICE", "HEARING_RECORD", "MEETING_RECORD",
            "DECISION", "DELIVERY_RECEIPT", "ORDER_CORRECT", "TERMINATE_DECISION", "CLOSE_REPORT");

    /** 普通程序处罚案件必备文书（齐全性检查） */
    private static final List<String> REQUIRED_NORMAL_PUNISH = List.of(
            "FINAL_REPORT", "NOTICE", "DECISION", "CLOSE_REPORT");

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

    /** 案卷目录：按法定顺序编排 + 必备文书齐全性检查 */
    public Map<String, Object> archiveCatalog(Long caseId) {
        CaseFile c = caseRepository.findById(caseId)
                .orElseThrow(() -> new BizException(2043, "案件不存在"));
        List<Map<String, Object>> docs = jdbc.queryForList(
                "select id, doc_type, title, made_at, maker, signed from case_document where case_id = ?", caseId);
        docs.sort(Comparator.comparingInt(d -> {
            int i = ARCHIVE_ORDER.indexOf((String) d.get("doc_type"));
            return i < 0 ? 0 : i;
        }));
        int seq = 1;
        for (var d : docs) d.put("seq", seq++);
        var decision = decisionRepository.findByCaseId(caseId).orElse(null);
        List<String> missing = new ArrayList<>();
        if ("NORMAL".equals(c.getProcedureType()) && decision != null && "PUNISH".equals(decision.getDecisionType())) {
            Set<String> present = new HashSet<>();
            docs.forEach(d -> present.add((String) d.get("doc_type")));
            REQUIRED_NORMAL_PUNISH.forEach(t -> { if (!present.contains(t)) missing.add(t); });
        }
        return Map.of("archiveNo", nv(c.getArchiveNo()), "catalog", docs, "missing", missing);
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
