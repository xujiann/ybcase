package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.*;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PrecaseAttachmentIntegrationTest {
    @Autowired CaseService cases;
    @Autowired ClueService clues;
    @Autowired OversightService oversight;
    @Autowired PrecaseAttachmentService sourceAttachments;
    @Autowired CaseCauseRepository causes;
    @Autowired JdbcTemplate jdbc;
    @Autowired BureauConfig config;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    private final Map<String, String> original = new LinkedHashMap<>();
    static final byte[] BYTES = "source raw bytes \u0000 医保附件".getBytes(StandardCharsets.UTF_8);
    record Actor(String username, String token, Authentication auth) {}
    Actor actor(String role) {
        var u = new SysUser(); u.setUsername("precase_" + System.nanoTime()); u.setRealName("附件测试" + role);
        u.setPassword("unused-for-jwt-test"); u.getRoles().add(roles.findByCode(role).orElseThrow()); u = users.save(u);
        return new Actor(u.getUsername(), jwt.issue(u.getUsername(), u.getTokenVersion()),
            new UsernamePasswordAuthenticationToken(u.getUsername(), "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    void configure(String key, String value) {
        original.computeIfAbsent(key, k -> jdbc.queryForObject("select cfg_value from sys_config where cfg_key=?", String.class, k));
        assertEquals(1, jdbc.update("update sys_config set cfg_value=? where cfg_key=?", value, key));
        config.onConfigChanged(new ConfigChangedEvent(key));
    }
    @AfterEach void restore() {
        original.forEach((k,v) -> { jdbc.update("update sys_config set cfg_value=? where cfg_key=?",v,k); config.onConfigChanged(new ConfigChangedEvent(k)); });
        original.clear();
    }
    long clue(Actor a) {
        return clues.create(new ClueService.ClueCreateReq("COMPLAINT", "附件核查", "医院", "PROVIDER", LocalDate.now(), a.username()), a.username()).getId();
    }
    CaseService.CaseCreateReq req(Long clueId) {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        return new CaseService.CaseCreateReq(clueId, cause, "NORMAL", "PRECASE-" + System.nanoTime(), "PROVIDER", null,null,null,null,
            "核查后立案", BigDecimal.TEN, List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
            new CaseService.OfficerReq("张协办", "YB002", "MEMBER")), "核查属实", null,null,null);
    }
    MockMultipartFile file() { return new MockMultipartFile("file", "source-"+System.nanoTime()+".bin", "application/octet-stream", BYTES); }
    JsonNode get(String path, Actor a) throws Exception {
        var r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer "+a.token())).andReturn().getResponse();
        var b = mapper.readTree(r.getContentAsString()); assertEquals(0,b.path("code").asInt(),b.toString()); return b.path("data");
    }
    int postCode(String path, String body, Actor a) throws Exception {
        return mapper.readTree(mvc.perform(post(path).header("Authorization","Bearer "+a.token()).contentType("application/json").content(body)).andReturn().getResponse().getContentAsString()).path("code").asInt();
    }
    int uploadCode(String path, Actor a) throws Exception {
        return mapper.readTree(mvc.perform(multipart(path).file(file()).header("Authorization","Bearer "+a.token())).andReturn().getResponse().getContentAsString()).path("code").asInt();
    }
    void exactDownload(long id, Actor a) throws Exception {
        var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/bureau/precase-attachments/{id}/download",id)
            .header("Authorization","Bearer "+a.token())).andReturn().getResponse();
        assertEquals(200,r.getStatus()); assertArrayEquals(BYTES,r.getContentAsByteArray());
    }

    @Test void dbAndFileBytesRemainIdenticalAndAdoptionKeepsIdsSourceStorageAndVoidedHistory() throws Exception {
        Actor owner=actor("HANDLER"), manager=actor("ADMIN");
        Path root=Path.of("data","recovery","precase-test-files").toAbsolutePath().normalize();
        assertFalse(root.toString().toLowerCase().contains("onedrive")); Files.createDirectories(root);
        configure("attachment_dir",root.toString());
        for(String mode:List.of("DB","FILE")) {
            configure("attachment_storage",mode); long clueId=clue(owner);
            long id=sourceAttachments.upload("CLUE",clueId,file(),"OTHER",owner.auth()); exactDownload(id,owner);
            long voided=sourceAttachments.upload("CLUE",clueId,file(),"DOC_SCAN",owner.auth());
            assertEquals(0,postCode("/api/bureau/precase-attachments/"+voided+"/void","{\"reason\":\"误传材料保留痕迹\"}",owner));
            var before=jdbc.queryForMap("select file_path, source_clue_id, uploaded_by, uploaded_at from case_attachment where id=?",id);
            long cid=cases.create(req(clueId),owner.username()).getId();
            exactDownload(id,owner);
            var after=jdbc.queryForMap("select file_path, source_clue_id, uploaded_by, uploaded_at from case_attachment where id=?",id);
            assertEquals(before,after); assertEquals(clueId,((Number)after.get("source_clue_id")).longValue());
            assertEquals(cid,jdbc.queryForObject("select case_id from case_attachment where id=?",Long.class,id));
            assertEquals(cid,jdbc.queryForObject("select case_id from case_attachment where id=?",Long.class,voided));
            assertEquals(2,jdbc.queryForObject("select count(*) from case_attachment where source_clue_id=?",Integer.class,clueId));
            assertEquals(1,get("/api/bureau/cases/"+cid+"/attachments",owner).size());
            assertEquals(2,get("/api/bureau/clues/"+clueId+"/attachments?includeVoided=true",manager).size());
            assertTrue(jdbc.queryForObject("select voided from case_attachment where id=?",Boolean.class,voided));
            assertEquals(2146,uploadCode("/api/bureau/clues/"+clueId+"/attachments",owner));
            assertEquals(2146,postCode("/api/bureau/precase-attachments/"+id+"/void","{\"reason\":\"旧源不可改\"}",owner));
            assertThrows(BizException.class,()->cases.create(req(clueId),owner.username()),"Same clue cannot be filed twice");
            cases.terminate(cid,"违法事实不能成立"); cases.close(cid,"终止归档","admin");
            assertEquals(2031,postCode("/api/bureau/cases/"+cid+"/attachments/"+id+"/void","{\"reason\":\"封卷不可改\"}",manager));
        }
    }

    @Test void inspectionToClueToCasePreservesEachSourceAndPreventsCrossSourceAccess() throws Exception {
        Actor owner=actor("HANDLER"), outsider=actor("HANDLER"), legal=actor("LEGAL");
        configure("attachment_storage","DB"); configure("case_view_scope","SELF");
        long inspectionId=((Number)oversight.createInspection(new OversightService.InspectionReq(null,"医院","PROVIDER","王办案、张协办",LocalDate.now()),owner.username()).get("id")).longValue();
        long inspectionAttachment=sourceAttachments.upload("INSPECTION",inspectionId,file(),"OTHER",owner.auth());
        assertEquals(2080,uploadCode("/api/bureau/inspections/"+inspectionId+"/attachments",outsider));
        assertEquals(403,uploadCode("/api/bureau/inspections/"+inspectionId+"/attachments",legal));
        oversight.completeInspection(inspectionId,"发现涉嫌违法",true);
        long clueId=oversight.inspectionToClue(inspectionId,owner.username()).getId();
        assertEquals(2146,uploadCode("/api/bureau/inspections/"+inspectionId+"/attachments",owner));
        assertEquals(1,get("/api/bureau/clues/"+clueId+"/attachments",owner).size());
        long clueAttachment=sourceAttachments.upload("CLUE",clueId,file(),"OTHER",owner.auth());
        long cid=cases.create(req(clueId),owner.username()).getId();
        assertEquals(2,get("/api/bureau/cases/"+cid+"/attachments",owner).size());
        assertEquals(inspectionId,jdbc.queryForObject("select source_inspection_id from case_attachment where id=?",Long.class,inspectionAttachment));
        assertEquals(clueId,jdbc.queryForObject("select source_clue_id from case_attachment where id=?",Long.class,clueAttachment));
        for(long id:List.of(inspectionAttachment,clueAttachment)) {
            exactDownload(id,owner);
            var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/bureau/precase-attachments/{id}/download",id)
                .header("Authorization","Bearer "+outsider.token())).andReturn().getResponse();
            assertEquals(2080,mapper.readTree(r.getContentAsString()).path("code").asInt());
        }
        long other=cases.create(req(null),owner.username()).getId();
        assertEquals(2134,postCode("/api/bureau/cases/"+other+"/attachments/"+clueAttachment+"/void","{\"reason\":\"跨案关联不可改\"}",owner));
    }

    @Test void concurrentUploadAndFilingEitherAdoptsSuccessfulUploadOrRejectsWithoutOrphans() throws Exception {
        Actor owner=actor("HANDLER"); configure("attachment_storage","DB");
        var pool=Executors.newFixedThreadPool(2);
        try {
            for(int run=0;run<4;run++) {
                long clueId=clue(owner); var start=new CyclicBarrier(2);
                var upload=pool.submit(()->{ start.await(10,TimeUnit.SECONDS); try { return sourceAttachments.upload("CLUE",clueId,file(),"OTHER",owner.auth()); }
                    catch(BizException e){ assertEquals(2146,e.code); return -1L; } });
                var filing=pool.submit(()->{ start.await(10,TimeUnit.SECONDS); return cases.create(req(clueId),owner.username()).getId(); });
                long cid=filing.get(30,TimeUnit.SECONDS), aid=upload.get(30,TimeUnit.SECONDS);
                assertEquals(0,jdbc.queryForObject("select count(*) from case_attachment where source_clue_id=? and case_id is null",Integer.class,clueId));
                assertEquals(aid>0?1:0,jdbc.queryForObject("select count(*) from case_attachment where source_clue_id=? and case_id=?",Integer.class,clueId,cid));
                if(aid>0) exactDownload(aid,owner);
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void historicalDuplicateClueBindingMustCheckEveryReturnedAttachmentCaseScope() throws Exception {
        configure("attachment_storage","DB"); configure("case_view_scope","SELF");
        Actor owner=actor("HANDLER"), otherOwner=actor("HANDLER");
        long clueId=clue(owner);
        long ownAttachment=sourceAttachments.upload("CLUE",clueId,file(),"OTHER",owner.auth());
        long ownCase=cases.create(req(clueId),owner.username()).getId();
        long foreignCase=cases.create(req(null),otherOwner.username()).getId();
        // Simulate inconsistent historical linkage that the current filing API rejects.
        // The first case is visible, but its clue also appears on another owner's case.
        jdbc.update("update case_file set clue_id=? where id=?",clueId,foreignCase);
        String forbiddenName="foreign-sensitive-case-file-"+System.nanoTime()+".bin";
        jdbc.update("""
            insert into case_attachment(case_id,source_clue_id,filename,content_type,size_bytes,data,category,uploaded_by)
            values (?,?,?,'application/octet-stream',?,?,'OTHER',?)""",
            foreignCase,clueId,forbiddenName,BYTES.length,BYTES,otherOwner.username());
        assertEquals(ownCase,jdbc.queryForObject("select case_id from case_attachment where id=?",Long.class,ownAttachment));
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/bureau/clues/{id}/attachments",clueId)
            .header("Authorization","Bearer "+owner.token())).andReturn().getResponse();
        JsonNode body=mapper.readTree(response.getContentAsString());
        assertEquals(2080,body.path("code").asInt(),body.toString());
        assertFalse(response.getContentAsString().contains(forbiddenName),"A visible first case cannot authorize another case's source attachment metadata");
        assertTrue(body.path("data").isMissingNode()||body.path("data").isNull());
    }
}
