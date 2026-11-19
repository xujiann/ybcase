package cn.ybcase.bureau;

import cn.ybcase.bureau.repository.CaseCauseRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Render the seeded NOTICE through authenticated HTTP without changing its template. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DocumentTemplateLiteralIntegrationTest {
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired CaseCauseRepository causes;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    String leaderToken() {
        var user = new SysUser();
        user.setUsername("literal_" + System.nanoTime());
        user.setRealName("模板原文回归负责人");
        user.setPassword("unused-jwt-fixture");
        user.getRoles().add(roles.findByCode("LEADER").orElseThrow());
        user = users.saveAndFlush(user);
        return jwt.issue(user.getUsername(), user.getTokenVersion());
    }

    long create(String token, String partyName, String summary) throws Exception {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        var body = new LinkedHashMap<String, Object>();
        body.put("causeId", cause);
        body.put("procedureType", "NORMAL");
        body.put("partyType", "PROVIDER");
        body.put("partyName", partyName);
        body.put("partyLegalRep", "原始法定代表人");
        body.put("summary", summary);
        body.put("amountInvolved", 10);
        body.put("officers", List.of(
                Map.of("name", "王办案", "certNo", "YB001", "duty", "LEAD"),
                Map.of("name", "张协办", "certNo", "YB002", "duty", "MEMBER")));
        var response = mvc.perform(post("/api/bureau/cases").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(mapper.writeValueAsString(body))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        JsonNode json = mapper.readTree(response.getContentAsByteArray());
        assertEquals(0, json.path("code").asInt(), json.toString());
        return json.path("data").path("id").asLong();
    }

    JsonNode render(String token, long id) throws Exception {
        var response = mvc.perform(get("/api/bureau/cases/" + id + "/documents/render")
                .param("docType", "NOTICE").header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        JsonNode json = mapper.readTree(response.getContentAsByteArray());
        assertEquals(0, json.path("code").asInt(), json.toString());
        return json.path("data");
    }

    @Test void caseTextThatLooksLikeTemplateVariablesSurvivesNormalCreateAndRender() throws Exception {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class));
        var templateBefore = jdbc.queryForMap("select * from doc_template where doc_type='NOTICE'");
        String token = leaderToken();
        String party = "原文{{partyLegalRep}}";
        String summary = "原始记录 $1\\保留{{orgName}}及{{partyName}}和{{unknown}}";
        long id = create(token, party, summary);
        var sourceBefore = jdbc.queryForMap("select * from case_file where id=?", id);

        JsonNode rendered = render(token, id);
        String content = rendered.path("content").asText();
        assertAll(
                () -> assertTrue(content.startsWith(party + "："), "Party's literal text was reinterpreted: " + content),
                () -> assertTrue(content.contains(summary), "Facts were reinterpreted: " + content),
                () -> assertTrue(rendered.path("title").asText().contains((String) sourceBefore.get("case_no"))),
                () -> assertEquals(templateBefore, jdbc.queryForMap("select * from doc_template where doc_type='NOTICE'")),
                () -> assertEquals(sourceBefore, jdbc.queryForMap("select * from case_file where id=?", id)),
                () -> assertEquals(0, jdbc.queryForObject("select count(*) from case_document where case_id=?", Integer.class, id)));
    }

    @Test void ordinaryCaseDataStillRendersWithTheSameSeededTemplate() throws Exception {
        String token = leaderToken();
        long id = create(token, "普通原文机构", "普通原文事实 $2\\资料");
        JsonNode rendered = render(token, id);
        assertTrue(rendered.path("content").asText().startsWith("普通原文机构："));
        assertTrue(rendered.path("content").asText().contains("普通原文事实 $2\\资料"));
        assertFalse(rendered.path("content").asText().contains("{{partyName}}"));
        assertFalse(rendered.path("content").asText().contains("{{orgName}}"));
    }
}
