package cn.ybcase.bureau.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Historical business lists share their source access rules with attachment reads. */
@Service
@RequiredArgsConstructor
public class SourceHistoryService {
    private final JdbcTemplate jdbc;
    private final SourceAttachmentAccess access;

    public enum Kind { INSPECTIONS, REWARDS, TRANSFERS }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Object list(Kind kind, Integer requestedPage, int requestedSize, String query, String username) {
        String projection, from, id;
        List<String> search;
        SourceAttachmentAccess.ReadPredicate scope;
        switch (kind) {
            case INSPECTIONS -> {
                projection = "i.*, li.name as item_name";
                from = "inspection i left join law_enforce_item li on li.id=i.item_id"; id = "i.id";
                search = List.of("i.insp_no", "i.object_name", "i.result", "i.officers", "li.name");
                scope = access.inspectionReadPredicate("i", username);
            }
            case REWARDS -> {
                projection = "r.*, c.clue_no, c.suspect_name";
                from = "reward r join case_clue c on c.id=r.clue_id"; id = "r.id";
                // Do not let search become an oracle for masked reporter identities or contacts.
                search = List.of("c.clue_no", "c.suspect_name", "r.note");
                scope = access.clueReadPredicate("r.clue_id", username);
            }
            case TRANSFERS -> {
                projection = "t.*, c.clue_no, cf.case_no, j.judgment_no, j.judgment_at, j.judgment_result";
                from = "case_transfer t left join case_clue c on c.id=t.clue_id left join case_file cf on cf.id=t.case_id"
                        + " left join case_transfer_judgment j on j.transfer_id=t.id"; id = "t.id";
                search = List.of("c.clue_no", "cf.case_no", "t.target_org", "t.reason", "t.note");
                scope = access.transferReadPredicate("t", username);
            }
            default -> throw new IllegalArgumentException("Unsupported history kind");
        }
        String where = " where (" + scope.sql() + ")";
        var args = new ArrayList<Object>(scope.args());
        if (query != null && !query.isBlank()) {
            where += " and (" + String.join(" or ", search.stream().map(column -> column + " ilike ?").toList()) + ")";
            for (int n = 0; n < search.size(); n++) args.add("%" + query.trim() + "%");
        }
        int page = requestedPage == null ? 1 : Math.max(1, requestedPage);
        int size = requestedPage == null ? 200 : Math.min(100, Math.max(1, requestedSize));
        Long total = requestedPage == null ? null : jdbc.queryForObject("select count(*) from " + from + where, Long.class, args.toArray());
        args.add(size); args.add((long)(page - 1) * size);
        var rows = jdbc.queryForList("select " + projection + " from " + from + where + " order by " + id + " desc limit ? offset ?", args.toArray());
        if (kind == Kind.REWARDS && !access.hasRole(username, "LEGAL", "LEADER", "ADMIN")) {
            for (var row : rows) {
                String name = (String)row.get("reporter_name");
                row.put("reporter_name", name == null || name.isEmpty() ? name : name.charAt(0) + "**");
                row.put("reporter_contact", row.get("reporter_contact") == null ? null : "***");
            }
        }
        return requestedPage == null ? rows : Map.of("rows", rows, "total", total == null ? 0L : total, "page", page, "size", size);
    }
}
