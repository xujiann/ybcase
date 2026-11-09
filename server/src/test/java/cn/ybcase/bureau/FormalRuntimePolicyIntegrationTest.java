package cn.ybcase.bureau;

import cn.ybcase.bureau.spi.MockSignatureProvider;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = "ybcase.runtime.mode=FORMAL")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FormalRuntimePolicyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;

    @BeforeEach void isolated() {
        assertTrue(jdbc.queryForObject("select current_database()", String.class)
                .matches("ybcase_authowner_test_20261028_[0-9a-f]{8}"));
    }
    record Fixture(String user, String token, long caseId, long documentId) {}
    Fixture fixture() {
        String user = "formal_" + UUID.randomUUID().toString().replace("-", "");
        FixtureUsers.ensure(jdbc, user, "HANDLER");
        long caseId = jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,status,owner_dept_id,owner_user)
                values(?,'正式策略合成案件',(select min(id) from case_cause),'合成机构','PROVIDER',current_date,current_date,'INVESTIGATING',1,?) returning id
                """, Long.class, "FORMAL-" + UUID.randomUUID(), user);
        long doc = jdbc.queryForObject("""
                insert into case_document(case_id,doc_type,title,content,made_at,maker,signed)
                values(?,'INSPECTION_RECORD','合成文书','保留原始文书',current_date,?,false) returning id
                """, Long.class, caseId, user);
        return new Fixture(user, jwt.issue(user, 0), caseId, doc);
    }
    JsonNode result(org.springframework.test.web.servlet.ResultActions request) throws Exception {
        return mapper.readTree(request.andReturn().getResponse().getContentAsByteArray());
    }

    @Test void formalModeWithoutCARejectsNewMockSignBeforeAnySignatureOrSignedFlagChange() throws Exception {
        var f = fixture();
        var original = jdbc.queryForMap("select * from case_document where id=?", f.documentId());
        var response = result(mvc.perform(post("/api/bureau/cases/" + f.caseId() + "/documents/" + f.documentId() + "/sign")
                .header("Authorization", "Bearer " + f.token()).contentType("application/json")
                .content(mapper.writeValueAsBytes(Map.of("signer", f.user(), "signerRole", "OFFICER")))));
        assertEquals(2193, response.path("code").asInt());
        assertEquals(original, jdbc.queryForMap("select * from case_document where id=?", f.documentId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from doc_signature where document_id=?", Integer.class, f.documentId()));
    }

    @Test void historicalMockIsVerifiableButNeverPromotedToFormalEvidence() throws Exception {
        var f = fixture();
        var signature = new MockSignatureProvider().sign("保留原始文书", f.user());
        jdbc.update("""
                insert into doc_signature(case_id,document_id,signer,signer_role,content_hash,signature,provider,operator)
                values(?,?,?,'OFFICER',?,?,?,?)
                """, f.caseId(), f.documentId(), f.user(), signature.contentHash(), signature.signature(), signature.provider(), f.user());
        var before = jdbc.queryForMap("select * from doc_signature where document_id=?", f.documentId());
        var response = result(mvc.perform(get("/api/bureau/cases/" + f.caseId() + "/documents/" + f.documentId() + "/verify")
                .header("Authorization", "Bearer " + f.token())));
        assertEquals(0, response.path("code").asInt(-1));
        var item = response.path("data").get(0);
        assertTrue(item.path("valid").asBoolean());
        assertTrue(item.path("simulation").asBoolean());
        assertFalse(item.path("formalAccepted").asBoolean(true));
        assertEquals(before, jdbc.queryForMap("select * from doc_signature where document_id=?", f.documentId()));
    }

    @Test void policyDiagnosticsAreExplicitAndDoNotContainAddressesOrCredentials() throws Exception {
        var f = fixture();
        var response = result(mvc.perform(get("/api/bureau/signatures/policy")
                .header("Authorization", "Bearer " + f.token())));
        assertEquals("FORMAL", response.path("data").path("mode").asText());
        assertTrue(response.path("data").path("formal").asBoolean());
        assertEquals(3, response.path("data").size());
    }
}
