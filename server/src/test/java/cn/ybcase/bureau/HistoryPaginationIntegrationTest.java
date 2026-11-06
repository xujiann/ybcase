package cn.ybcase.bureau;

import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.CaseService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HistoryPaginationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired BureauConfig config;
    @Autowired CaseService cases;
    String originalScope;
    final String marker="HP"+UUID.randomUUID().toString().replace("-","").substring(0,12);
    record Actor(long id,String name,String token) {}
    Actor actor(String role) {
        var u=new SysUser();u.setUsername("hp_"+UUID.randomUUID().toString().replace("-",""));u.setRealName("分页测试");u.setPassword("unused");
        u.getRoles().add(roles.findByCode(role).orElseThrow());u=users.save(u);
        return new Actor(u.getId(),u.getUsername(),jwt.issue(u.getUsername(),u.getTokenVersion()));
    }
    void scope(String value) {
        if(originalScope==null) originalScope=jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'",String.class);
        jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'",value);config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
    }
    @AfterEach void restore(){if(originalScope!=null){jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'",originalScope);config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));}}
    long clue(String owner,String handler) {
        return jdbc.queryForObject("insert into case_clue(clue_no,source,content,suspect_name,suspect_type,received_at,deadline_at,handler,created_by) values(?, 'COMPLAINT',?,?,'PROVIDER',current_date,current_date,?,?) returning id",Long.class,
            "HP"+UUID.randomUUID().toString().replace("-","").substring(0,24),marker,marker,handler,owner);
    }
    long inspection(String owner,Long clue) {
        return jdbc.queryForObject("insert into inspection(insp_no,object_name,object_type,officers,planned_at,created_by,clue_id) values(?,?,'PROVIDER','测试甲、测试乙',current_date,?,?) returning id",Long.class,
            "HP"+UUID.randomUUID().toString().replace("-","").substring(0,24),marker,owner,clue);
    }
    long caseRow(String owner,Long clue) {
        return jdbc.queryForObject("insert into case_file(case_no,name,clue_id,cause_id,party_name,party_type,filed_at,deadline_at,owner_user) values(?,?,?,(select min(id) from case_cause),?,'PROVIDER',current_date,current_date,?) returning id",Long.class,
            marker+UUID.randomUUID().toString().substring(0,8),marker,clue,marker,owner);
    }
    long transfer(Long clue,Long caseId) {
        return jdbc.queryForObject("insert into case_transfer(clue_id,case_id,direction,target_org,kind,reason,sent_at) values(?,?,'OUT',?,'ADMIN',?,current_date) returning id",Long.class,clue,caseId,marker,marker);
    }
    long reward(long clue) {
        return jdbc.queryForObject("insert into reward(clue_id,reporter_name,reporter_contact,note) values(?,'赵举报人','13812345678',?) returning id",Long.class,clue,marker);
    }
    JsonNode fetch(String kind,Actor user,Integer page,int size,String q) throws Exception {
        var request=get("/api/bureau/"+kind).header("Authorization","Bearer "+user.token()).param("size",String.valueOf(size)).param("q",q);
        if(page!=null)request.param("page",String.valueOf(page));
        var response=mvc.perform(request).andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());
        var root=mapper.readTree(response.getContentAsString());assertEquals(0,root.path("code").asInt(),root.toString());return root.path("data");
    }
    Set<Long> ids(JsonNode rows) {var result=new HashSet<Long>();rows.forEach(r->assertTrue(result.add(r.path("id").asLong()),"duplicate row"));return result;}
    Set<Long> all(String kind,Actor actor,int expected) throws Exception {
        var collected=new HashSet<Long>();int pages=(expected+2)/3;
        for(int p=1;p<=Math.max(1,pages);p++){
            var data=fetch(kind,actor,p,3,marker);assertEquals(expected,data.path("total").asInt());
            assertEquals(p,data.path("page").asInt());assertTrue(data.path("rows").size()<=3);
            for(long id:ids(data.path("rows")))assertTrue(collected.add(id),"row repeated across pages");
        }
        assertEquals(expected,collected.size());return collected;
    }

    @Test void newerForeignRowsCannotHideOldOwnHistoryAndLegacyStillFiltersBeforeLimit() throws Exception {
        scope("SELF");var own=actor("HANDLER");var foreign=actor("HANDLER");
        long old=inspection(own.name(),null);
        for(int n=0;n<205;n++)inspection(foreign.name(),null);
        assertEquals(Set.of(old),all("inspections",own,1));
        assertEquals(Set.of(old),ids(fetch("inspections",own,null,20,marker)));
        assertEquals(206,fetch("inspections",actor("LEGAL"),1,100,marker).path("total").asInt());
    }

    @Test void everyPageHasExactVisibleTotalsAndRewardRedaction() throws Exception {
        scope("SELF");var own=actor("HANDLER");var other=actor("HANDLER");
        Set<Long> inspections=new HashSet<>(),rewards=new HashSet<>(),transfers=new HashSet<>();
        for(int i=0;i<11;i++){long clue=clue(own.name(),null);inspections.add(inspection(own.name(),null));rewards.add(reward(clue));transfers.add(transfer(clue,null));}
        for(int i=0;i<7;i++){long clue=clue(other.name(),null);inspection(other.name(),null);reward(clue);transfer(clue,null);}
        assertEquals(inspections,all("inspections",own,11));assertEquals(rewards,all("rewards",own,11));assertEquals(transfers,all("transfers",own,11));
        for(int p=1;p<=4;p++)for(var row:fetch("rewards",own,p,3,marker).path("rows")){
            assertEquals("赵**",row.path("reporter_name").asText());assertEquals("***",row.path("reporter_contact").asText());
        }
        assertEquals(0,fetch("rewards",own,1,20,"13812345678").path("total").asInt(),"search must not reveal masked reporter contact");
        for(String role:List.of("LEGAL","LEADER","ADMIN")){
            var privileged=actor(role);assertEquals(18,fetch("rewards",privileged,1,100,marker).path("total").asInt());
            assertEquals("赵举报人",fetch("rewards",privileged,1,100,marker).path("rows").get(0).path("reporter_name").asText());
        }
    }

    @Test void convertedSourcesUseClueThenMinimumCaseScopeAndOfficerAvoidance() throws Exception {
        scope("SELF");var own=actor("HANDLER");var foreign=actor("HANDLER");
        long clue=clue(foreign.name(),null);long converted=inspection(own.name(),clue);
        assertEquals(0,fetch("inspections",own,1,20,marker).path("total").asInt(),"inspection creator loses scope after conversion to another source");
        long first=caseRow(foreign.name(),clue);caseRow(own.name(),clue);
        assertEquals(0,fetch("inspections",own,1,20,marker).path("total").asInt(),"later case must not override earliest linked case");
        long officer=jdbc.queryForObject("insert into case_officer(case_id,name,cert_no,duty,user_id) values(?,'同名无权限','HPOFFICER','MEMBER',?) returning id",Long.class,first,own.id());
        assertEquals(Set.of(converted),all("inspections",own,1));
        jdbc.update("update case_officer set avoided=true where id=?",officer);
        assertEquals(0,fetch("inspections",own,1,20,marker).path("total").asInt());
        jdbc.update("update case_file set owner_user=? where id=?",own.name(),first);
        assertEquals(Set.of(converted),all("inspections",own,1));
    }

    @Test void allCaseScopeDoesNotExposeUnfiledForeignSourcesAndHandlersMatchExactTokens() throws Exception {
        scope("ALL");var own=actor("HANDLER");var foreign=actor("HANDLER");
        long filed=clue(foreign.name(),null);caseRow(foreign.name(),filed);long visible=inspection(foreign.name(),filed);
        inspection(foreign.name(),null);inspection(foreign.name(),clue(foreign.name(),null));
        long exact=inspection(foreign.name(),clue(foreign.name(),"someone；"+own.name()+"、another"));
        inspection(foreign.name(),clue(foreign.name(),"prefix"+own.name()+"suffix"));
        assertEquals(Set.of(visible,exact),all("inspections",own,2));
    }

    @Test void transferWithTwoSourcesRequiresBothAndSourcelessHistoryIsHiddenEvenForAdmin() throws Exception {
        scope("SELF");var own=actor("HANDLER");var foreign=actor("HANDLER");
        long ownClue=clue(own.name(),null),otherClue=clue(foreign.name(),null),ownCase=caseRow(own.name(),null),otherCase=caseRow(foreign.name(),null);
        long both=transfer(ownClue,ownCase),single=transfer(null,ownCase);transfer(ownClue,otherCase);transfer(otherClue,ownCase);transfer(null,null);
        assertEquals(Set.of(both,single),all("transfers",own,2));
        assertEquals(4,fetch("transfers",actor("ADMIN"),1,20,marker).path("total").asInt());
    }

    @Test void authoritativeEnforcerMappingGrantsParticipationButClearedMappingDoesNotGrantNewCases() throws Exception {
        scope("SELF");var own=actor("HANDLER");var foreign=actor("HANDLER");
        String cert="HP"+UUID.randomUUID().toString().replace("-","");
        long enforcer=jdbc.queryForObject("insert into enforcer(name,cert_no,cert_expire_at,enabled,user_id) values('分页测试',?,current_date+365,true,?) returning id",Long.class,cert,own.id());
        long firstClue=clue(foreign.name(),null),first=caseRow(foreign.name(),firstClue);long visible=inspection(foreign.name(),firstClue);
        cases.addOfficer(first,new CaseService.OfficerReq("分页测试",cert,"MEMBER"));
        assertEquals(Set.of(visible),all("inspections",own,1));
        jdbc.update("update enforcer set user_id=null where id=?",enforcer);
        long secondClue=clue(foreign.name(),null),second=caseRow(foreign.name(),secondClue);inspection(foreign.name(),secondClue);
        cases.addOfficer(second,new CaseService.OfficerReq("分页测试",cert,"MEMBER"));
        assertEquals(Set.of(visible),all("inspections",own,1),"cleared authoritative mapping cannot grant scope by same real name");
        assertNull(jdbc.queryForObject("select user_id from case_officer where case_id=?",Long.class,second));
    }

    @Test void queryIsTrimmedBoundAndExtremePaginationPreservesTotal() throws Exception {
        scope("SELF");var own=actor("HANDLER");for(int i=0;i<5;i++)inspection(own.name(),null);
        var low=fetch("inspections",own,-100,-10,"  "+marker+"  ");assertEquals(1,low.path("page").asInt());assertEquals(1,low.path("size").asInt());assertEquals(5,low.path("total").asInt());
        var huge=fetch("inspections",own,Integer.MAX_VALUE,Integer.MAX_VALUE,marker);assertEquals(100,huge.path("size").asInt());assertEquals(5,huge.path("total").asInt());assertEquals(0,huge.path("rows").size());
        assertEquals(0,fetch("inspections",own,1,20,"' OR 1=1 --").path("total").asInt());
    }
}
