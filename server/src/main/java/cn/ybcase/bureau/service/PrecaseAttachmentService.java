package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PrecaseAttachmentService {
    private final JdbcTemplate jdbc;
    private final SourceAttachmentAccess access;
    private final AttachmentStorage storage;
    private final MaterialAccess materials;

    private static final String METADATA = "id,case_id,source_clue_id,source_inspection_id,document_id,evidence_id,filename,"
            + "content_type,size_bytes,category,(file_path is not null) as external,uploaded_by,uploaded_at,voided,void_reason,voided_by,voided_at";

    @Transactional(rollbackFor = Exception.class)
    public long upload(String source, Long id, MultipartFile file, String category, Authentication auth) throws IOException {
        access.lockWritable(source, id, auth.getName());
        return storage.store(null, "CLUE".equals(source) ? id : null, "INSPECTION".equals(source) ? id : null,
                null, null, file, category, auth.getName());
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> list(String source, Long id, boolean includeVoided, Authentication auth) {
        materials.readActor(auth.getName());
        access.assertRead(source, id, auth.getName());
        boolean withVoided = includeVoided && access.hasRole(auth.getName(), "LEADER", "ADMIN");
        if ("CLUE".equals(source))
            return checkedList(jdbc.queryForList("select " + METADATA + " from case_attachment where"
                    + " (source_clue_id=? or source_inspection_id in (select id from inspection where clue_id=?))"
                    + " and (voided=false or ?) order by id", id, id, withVoided), auth.getName());
        return checkedList(jdbc.queryForList("select " + METADATA + " from case_attachment where source_inspection_id=?"
                + " and (voided=false or ?) order by id", id, withVoided), auth.getName());
    }

    private List<Map<String, Object>> checkedList(List<Map<String, Object>> rows, String username) {
        java.util.Set<Long> checked = new java.util.HashSet<>();
        for (Map<String, Object> row : rows) {
            if (row.get("case_id") instanceof Number caseId && checked.add(caseId.longValue()))
                access.assertCaseScope(caseId.longValue(), username);
        }
        return rows;
    }

    private Map<String, Object> attachment(Long id) {
        var rows = jdbc.queryForList("select * from case_attachment where id=?", id);
        if (rows.isEmpty()) throw new BizException(2146, "来源附件不存在");
        Map<String, Object> row = rows.get(0);
        if (row.get("source_clue_id") == null && row.get("source_inspection_id") == null)
            throw new BizException(2146, "该附件不是立案前来源材料，请从案件下载");
        return row;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ResponseEntity<Resource> download(Long id, Authentication auth) {
        return download(id, auth, null);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ResponseEntity<Resource> download(Long id, Authentication auth, String range) {
        materials.readActor(auth.getName());
        Map<String, Object> row = attachment(id);
        access.assertAttachmentRead(row, auth.getName());
        if (Boolean.TRUE.equals(row.get("voided")) && !access.hasRole(auth.getName(), "LEADER", "ADMIN"))
            throw new BizException(2135, "该附件已作废，仅负责人/管理员可查看");
        return storage.response(row, range);
    }

    @Transactional
    public void voidAttachment(Long id, String reason, Authentication auth) {
        access.requireWriter(auth.getName());
        Map<String, Object> row = attachment(id);
        access.lockAttachmentForVoid(row, auth.getName());
        // Re-read after the source lock so a concurrent adoption cannot be overwritten.
        row = attachment(id);
        if (row.get("case_id") != null) throw new BizException(2146, "来源材料已归案，请在对应案件办理附件作废");
        if (reason == null || reason.isBlank() || reason.length() > 2000 || reason.indexOf('\0') >= 0)
            throw new BizException(2130, "须填写2000字以内作废理由");
        if (Boolean.TRUE.equals(row.get("voided"))) throw new BizException(2132, "该附件已作废");
        if (!auth.getName().equals(row.get("uploaded_by")) && !access.hasRole(auth.getName(), "LEADER", "ADMIN"))
            throw new BizException(2133, "仅上传人本人或负责人/管理员可作废附件");
        int n = jdbc.update("update case_attachment set voided=true,void_reason=?,voided_by=?,voided_at=now()"
                + " where id=? and case_id is null and voided=false", reason.trim(), auth.getName(), id);
        if (n == 0) throw new BizException(2132, "该附件已作废或已归案");
    }
}
