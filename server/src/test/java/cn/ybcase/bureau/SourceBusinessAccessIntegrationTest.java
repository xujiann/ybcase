package cn.ybcase.bureau;

import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.ClueService;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class SourceBusinessAccessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired CaseService cases;
    @Autowired ClueService clues;
    @Autowired CaseCauseRepository causes;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    record Actor(String name,String token) {}
    Actor actor(String role) {
        var user=new SysUser();user.setUsername("source_acl_"+System.nanoTime());user.setRealName("来源权限测试");user.setPassword("unused-jwt-fixture");
        user.getRoles().add(roles.findByCode(role).orElseThrow());user=users.saveAndFlush(user);
        return new Actor(user.getUsername(),jwt.issue(user.getUsername(),user.getTokenVersion()));
    }
    long clue(Actor owner,String assigned) { return clues.create(new ClueService.ClueCreateReq("COMPLAINT","虚构权限核查","测试医院","PROVIDER",LocalDate.now(),assigned),owner.name()).getId(); }
    long fresh(Actor owner) {
        long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();
        return cases.create(new CaseService.CaseCreateReq(null,cause,"NORMAL","SOURCE-"+System.nanoTime(),"PROVIDER",null,null,null,null,"权限核查",BigDecimal.TEN,
            List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),owner.name()).getId();
    }
    int postCode(Actor actor,String path,Object body) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/bureau"+path).header("Authorization","Bearer "+actor.token()).contentType("application/json").content(mapper.writeValueAsString(body)))
            .andReturn().getResponse().getContentAsString()).path("code").asInt();
    }
    @Test void legalCannotUseFormalizationOrClueWriteEntrypoints() throws Exception {
        Actor owner=actor("HANDLER"),legal=actor("LEGAL");long id=fresh(owner),clue=clue(owner,owner.name());
        long doc=jdbc.queryForObject("insert into case_document(case_id,doc_type,title,content,made_at,maker) values(?,'FINAL_REPORT','权限','虚构材料',current_date,?) returning id",Long.class,id,owner.name());
        Map<String,Object> before=jdbc.queryForMap("select status,excluded_days,deadline_at from case_clue where id=?",clue);
        var writes=new LinkedHashMap<String,Object>();
        writes.put("/cases/"+id+"/documents/"+doc+"/sign",Map.of());
        writes.put("/cases/"+id+"/doc-deliveries",Map.of("method","DIRECT"));
        writes.put("/cases/"+id+"/agreement-actions",Map.of("action","INTERVIEW"));
        writes.put("/agreement-actions/999999/reply",Map.of("result","越权"));
        writes.put("/transfers/999999/receipt",Map.of("receiptNo","越权"));
        writes.put("/approvals",Map.of("kind","CLUE_EXTEND","clueId",clue,"reason","越权"));
        writes.put("/clues",Map.of("content","越权线索"));writes.put("/clues/fetch-monitor",Map.of());
        writes.put("/clues/import",List.of(Map.of("content","越权导入")));
        writes.put("/cases/"+id+"/meetings/999999/sign",Map.of("signNames","越权签名"));
        writes.put("/clues/"+clue+"/reject",Map.of("verifyResult","越权"));
        writes.put("/clues/"+clue+"/exclusions",Map.of("days",2,"reason","越权"));
        for(var write:writes.entrySet())assertEquals(403,postCode(legal,write.getKey(),write.getValue()),write.getKey());
        assertEquals(before,jdbc.queryForMap("select status,excluded_days,deadline_at from case_clue where id=?",clue));
        assertFalse(jdbc.queryForObject("select signed from case_document where id=?",Boolean.class,doc));
        assertEquals(0,jdbc.queryForObject("select count(*) from doc_signature where document_id=?",Integer.class,doc));
    }
    @Test void foreignClueMutationsAndExtensionApplicationAreRejectedWithoutChangingSource() throws Exception {
        Actor owner=actor("HANDLER"),other=actor("HANDLER");long id=clue(owner,owner.name());
        var before=jdbc.queryForMap("select * from case_clue where id=?",id);
        assertEquals(2080,postCode(other,"/clues/"+id+"/reject",Map.of("verifyResult","他人")));
        assertEquals(2080,postCode(other,"/clues/"+id+"/exclusions",Map.of("days",3,"reason","他人")));
        assertEquals(2080,postCode(other,"/approvals",Map.of("kind","CLUE_EXTEND","clueId",id,"reason","他人")));
        assertEquals(before,jdbc.queryForMap("select * from case_clue where id=?",id));
        assertEquals(0,jdbc.queryForObject("select count(*) from biz_approval where clue_id=?",Integer.class,id));
    }
    @Test void assignedUsernameMayHandleClueAndLeaderMayHandleAcrossOwners() throws Exception {
        Actor owner=actor("HANDLER"),assigned=actor("HANDLER"),leader=actor("LEADER");
        long id=clue(owner,"somebody，"+assigned.name()+"；another");
        assertEquals(0,postCode(assigned,"/clues/"+id+"/exclusions",Map.of("days",2,"reason","鉴定")));
        assertEquals(0,postCode(assigned,"/approvals",Map.of("kind","CLUE_EXTEND","clueId",id,"reason","申请延期")));
        assertEquals(0,postCode(leader,"/clues/"+id+"/reject",Map.of("verifyResult","核查不予立案")));
        assertEquals(2021,postCode(owner,"/clues/"+id+"/exclusions",Map.of("days",1,"reason","已办结")));
    }
    @Test void clueOnlyTransferReceiptRequiresSourceOwnerAndCannotBeOverwritten() throws Exception {
        Actor owner=actor("HANDLER"),other=actor("HANDLER");long id=clue(owner,owner.name());
        jdbc.update("update case_clue set status='TRANSFERRED' where id=?",id);
        long transfer=jdbc.queryForObject("insert into case_transfer(clue_id,direction,kind,target_org,reason,sent_at) values(?,'OUT','ADMIN','测试机构','移送',current_date) returning id",Long.class,id);
        assertEquals(2080,postCode(other,"/transfers/"+transfer+"/receipt",Map.of("receiptNo","他人")));
        assertNull(jdbc.queryForObject("select receipt_no from case_transfer where id=?",String.class,transfer));
        assertEquals(0,postCode(owner,"/transfers/"+transfer+"/receipt",Map.of("receiptNo","虚构回执001")));
        assertEquals(2059,postCode(owner,"/transfers/"+transfer+"/receipt",Map.of("receiptNo","覆盖")));
        assertEquals("虚构回执001",jdbc.queryForObject("select receipt_no from case_transfer where id=?",String.class,transfer));
    }
    @Test void unboundLegacyTransferFailsClosed() throws Exception {
        Actor handler=actor("HANDLER");
        long transfer=jdbc.queryForObject("insert into case_transfer(direction,kind,target_org,reason,sent_at) values('OUT','ADMIN','测试机构','历史无来源',current_date) returning id",Long.class);
        assertEquals(2059,postCode(handler,"/transfers/"+transfer+"/receipt",Map.of("receiptNo","不能越过来源")));
    }
    @Test void clueOnlyReceiptCannotBypassArchivedAdoptedCase() throws Exception {
        Actor owner=actor("HANDLER");long clue=clue(owner,owner.name()),id=fresh(owner);
        jdbc.update("update case_file set clue_id=?,status='CLOSED',archive_no='SOURCE-SEALED' where id=?",clue,id);
        long transfer=jdbc.queryForObject("insert into case_transfer(clue_id,direction,kind,target_org,reason,sent_at) values(?,'OUT','ADMIN','机关','已归案来源',current_date) returning id",Long.class,clue);
        assertEquals(2031,postCode(owner,"/transfers/"+transfer+"/receipt",Map.of("receiptNo","不能绕封卷")));
        assertNull(jdbc.queryForObject("select receipt_no from case_transfer where id=?",String.class,transfer));
    }
    @Test void concurrentExclusionsRetainBothIncrements() throws Exception {
        Actor owner=actor("HANDLER");long id=clue(owner,owner.name());
        LocalDate before=jdbc.queryForObject("select deadline_at from case_clue where id=?",LocalDate.class,id);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->clues.addExclusion(id,2,"检验A",owner.name()));
            var b=pool.submit(()->clues.addExclusion(id,3,"检验B",owner.name()));
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        }
        assertEquals(5,jdbc.queryForObject("select excluded_days from case_clue where id=?",Integer.class,id));
        assertEquals(before.plusDays(5),jdbc.queryForObject("select deadline_at from case_clue where id=?",LocalDate.class,id));
    }
}
