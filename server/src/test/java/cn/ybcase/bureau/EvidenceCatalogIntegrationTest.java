package cn.ybcase.bureau;

import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.EvidenceService;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class EvidenceCatalogIntegrationTest {
    @Autowired CaseService cases;
    @Autowired CaseCauseRepository causes;
    @Autowired EvidenceService evidence;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired BureauConfig config;
    record Actor(String username, String token) {}
    Actor actor(String role) {
        var user = new SysUser(); user.setUsername("catalog_" + System.nanoTime()); user.setRealName("目录测试" + role);
        user.setPassword("unused-for-jwt-test"); user.getRoles().add(roles.findByCode(role).orElseThrow());
        user = users.save(user); return new Actor(user.getUsername(), jwt.issue(user.getUsername(), user.getTokenVersion()));
    }
    long create(Actor owner) {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        return cases.create(new CaseService.CaseCreateReq(null, cause, "NORMAL", "CATALOG-" + System.nanoTime(), "PROVIDER",
            null, null, null, null, "证据目录测试", BigDecimal.TEN,
            List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"), new CaseService.OfficerReq("张协办", "YB002", "MEMBER")),
            null, null, null, null), owner.username()).getId();
    }
    JsonNode getData(String path, Actor actor) throws Exception {
        var response = mvc.perform(get(path).header("Authorization", "Bearer " + actor.token())).andReturn().getResponse();
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertEquals(0, body.path("code").asInt(), body.toString()); return body.path("data");
    }
    long addEvidence(long cid) {
        evidence.addEvidence(cid, new EvidenceService.EvidenceReq("DOCUMENT", "书证-" + System.nanoTime(), "现场提取", LocalDate.now(), "王办案", null, false, false));
        return jdbc.queryForObject("select max(id) from case_evidence where case_id = ?", Long.class, cid);
    }
    long upload(long cid, Long evidenceId, Actor actor) throws Exception {
        var request = multipart("/api/bureau/cases/{id}/attachments", cid)
            .file(new MockMultipartFile("file", "catalog-" + System.nanoTime() + ".json", "application/json", "attachment-secret-raw".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer " + actor.token());
        if (evidenceId != null) request.param("evidenceId", evidenceId.toString());
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(0, mapper.readTree(response.getContentAsString()).path("code").asInt(), response.getContentAsString());
        return jdbc.queryForObject("select max(id) from case_attachment where case_id = ?", Long.class, cid);
    }
    JsonNode state(long cid) {
        return mapper.valueToTree(Map.of("attachments", jdbc.queryForList("select * from case_attachment where case_id = ? order by id", cid),
            "evidence", jdbc.queryForList("select * from case_evidence where case_id = ? order by id", cid),
            "documents", jdbc.queryForList("select * from case_document where case_id = ? order by id", cid),
            "seq", jdbc.queryForList("select * from biz_seq order by kind, year")));
    }
    JsonNode find(JsonNode items, String kind, long id) {
        for (JsonNode item : items) if (kind.equals(item.path("kind").asText()) && id == item.path("id").asLong()) return item;
        fail("Missing " + kind + " " + id + " in " + items); return null;
    }

    @Test void directoryUsesPersistentIdsListsLinkedAttachmentOnceAndDoesNotReadOrWriteBinaryMaterial() throws Exception {
        Actor owner = actor("HANDLER"), admin = actor("ADMIN"); long cid = create(owner);
        long evidenceId = addEvidence(cid), attachmentId = upload(cid, evidenceId, owner);
        JsonNode before = state(cid);
        JsonNode result = getData("/api/bureau/cases/" + cid + "/evidence-catalog", owner);
        JsonNode items = result.path("items");
        assertEquals(cid, result.path("caseId").asLong()); assertEquals(2, items.size());
        assertEquals(String.format("ZJ-%08d", evidenceId), find(items, "EVIDENCE", evidenceId).path("number").asText());
        JsonNode attachment = find(items, "ATTACHMENT", attachmentId);
        assertEquals(String.format("FJ-%08d", attachmentId), attachment.path("number").asText());
        assertEquals(evidenceId, attachment.path("evidenceId").asLong());
        assertFalse(items.toString().contains("attachment-secret-raw"));
        for (JsonNode item : items) for (String forbidden : List.of("data", "filePath", "file_path", "content")) assertFalse(item.has(forbidden), item.toString());
        assertEquals(result, getData("/api/bureau/cases/" + cid + "/archive-full", owner).path("evidenceCatalog"));
        assertEquals(result, getData("/api/bureau/cases/" + cid + "/evidence-catalog", owner));
        assertEquals(before, state(cid), "Reading directory and composed archive must not persist numbering or materials");
        addEvidence(cid); upload(cid, null, owner);
        JsonNode expanded = getData("/api/bureau/cases/" + cid + "/evidence-catalog", owner).path("items");
        assertEquals(attachment.path("number"), find(expanded, "ATTACHMENT", attachmentId).path("number"));
        assertEquals(find(items, "EVIDENCE", evidenceId).path("number"), find(expanded, "EVIDENCE", evidenceId).path("number"));
        var response = mvc.perform(post("/api/bureau/cases/{id}/attachments/{aid}/void", cid, attachmentId)
            .header("Authorization", "Bearer " + admin.token()).contentType("application/json").content("{\"reason\":\"误传他案扫描件\"}")).andReturn().getResponse();
        assertEquals(0, mapper.readTree(response.getContentAsString()).path("code").asInt());
        var visible = getData("/api/bureau/cases/" + cid + "/evidence-catalog", owner).path("items");
        assertEquals(3, visible.size());
        var all = getData("/api/bureau/cases/" + cid + "/evidence-catalog?includeVoided=true", admin).path("items");
        assertEquals(4, all.size()); assertTrue(find(all, "ATTACHMENT", attachmentId).path("voided").asBoolean());
        assertEquals("误传他案扫描件", find(all, "ATTACHMENT", attachmentId).path("voidReason").asText());
        assertEquals(attachment.path("number"), find(all, "ATTACHMENT", attachmentId).path("number"));
        assertEquals(3, getData("/api/bureau/cases/" + cid + "/evidence-catalog?includeVoided=true", owner).path("items").size());
        assertEquals(3, getData("/api/bureau/cases/" + cid + "/evidence-catalog?includeVoided=true", actor("LEGAL")).path("items").size());
    }

    @Test void archivedDirectoryRemainsReadableButSelfScopeIsStillEnforced() throws Exception {
        Actor owner = actor("HANDLER"); long cid = create(owner); addEvidence(cid); upload(cid, null, owner);
        cases.terminate(cid, "违法事实不能成立"); cases.close(cid, "终止归档", "admin");
        JsonNode before = state(cid), result = getData("/api/bureau/cases/" + cid + "/evidence-catalog", owner);
        assertEquals(2, result.path("items").size()); assertEquals(before, state(cid));
        String old = jdbc.queryForObject("select cfg_value from sys_config where cfg_key = 'case_view_scope'", String.class);
        try {
            jdbc.update("update sys_config set cfg_value='SELF' where cfg_key='case_view_scope'"); config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
            var response = mvc.perform(get("/api/bureau/cases/{id}/evidence-catalog", cid)
                .header("Authorization", "Bearer " + actor("HANDLER").token())).andReturn().getResponse();
            assertEquals(2080, mapper.readTree(response.getContentAsString()).path("code").asInt());
            assertEquals(result, getData("/api/bureau/cases/" + cid + "/evidence-catalog", owner));
        } finally {
            jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'", old); config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
        }
    }
}
