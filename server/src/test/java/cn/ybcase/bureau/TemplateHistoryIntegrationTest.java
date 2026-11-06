package cn.ybcase.bureau;

import cn.ybcase.bureau.web.FormalizationController;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Existing transaction/row-lock behavior must preserve each predecessor and roll back failed history writes. */
@SpringBootTest
@ActiveProfiles("test")
class TemplateHistoryIntegrationTest {
    @Autowired FormalizationController controller;
    @Autowired JdbcTemplate jdbc;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    private final List<String> fixtures = new ArrayList<>();
    private Authentication admin;

    @BeforeEach void authenticate() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class), "Template tests require an isolated database");
        var actor = new SysUser();
        actor.setUsername("template_admin_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        actor.setRealName("模板历史测试管理员");actor.setPassword("unused-service-fixture");actor.setEnabled(true);
        actor.getRoles().add(roles.findByCode("ADMIN").orElseThrow());actor = users.saveAndFlush(actor);
        admin = new UsernamePasswordAuthenticationToken(actor.getUsername(), "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(admin);
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        for (String type : fixtures) {
            jdbc.update("delete from doc_template_history where doc_type=?", type);
            jdbc.update("delete from doc_template where doc_type=?", type);
        }
    }

    private String fixture() {
        String type = "TPL_TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        fixtures.add(type);
        jdbc.update("insert into doc_template(doc_type,title_tpl,content_tpl) values(?,?,?)", type, "original", "original");
        return type;
    }
    private Map<String, Object> current(String type) {
        return jdbc.queryForMap("select title_tpl,content_tpl,updated_at from doc_template where doc_type=?", type);
    }
    private List<Map<String, Object>> history(String type) {
        return jdbc.queryForList("select title_tpl,content_tpl,saved_by from doc_template_history where doc_type=? order by id", type);
    }
    private void update(String type, String value) {
        controller.updateTemplate(type, Map.of("titleTpl", value, "contentTpl", value), admin);
    }

    @Test void normalUpdatePreservesPreviousTitleContentAndOperator() {
        String type = fixture();
        update(type, "new content");
        assertEquals("new content", current(type).get("content_tpl"));
        assertEquals("new content", current(type).get("title_tpl"));
        assertEquals(List.of(Map.of("title_tpl", "original", "content_tpl", "original", "saved_by", admin.getName())), history(type));
    }

    @Test void concurrentDistinctUpdatesSerializeAndPreserveBothPredecessors() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 4; round++) {
                String type = fixture();
                CyclicBarrier start = new CyclicBarrier(2);
                var first = executor.submit(() -> concurrentUpdate(type, "version A", start));
                var second = executor.submit(() -> concurrentUpdate(type, "version B", start));
                first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
                var versions = history(type);
                assertEquals(2, versions.size());
                assertEquals("original", versions.get(0).get("content_tpl"));
                String intermediate = (String) versions.get(1).get("content_tpl");
                String latest = (String) current(type).get("content_tpl");
                assertEquals(Set.of("version A", "version B"), Set.copyOf(List.of(intermediate, latest)),
                        "Both distinct submitted versions must survive once across predecessor history and current template");
                assertEquals(intermediate, versions.get(1).get("title_tpl"));
                assertEquals(latest, current(type).get("title_tpl"));
            }
        }
    }
    private void concurrentUpdate(String type, String value, CyclicBarrier start) {
        SecurityContextHolder.getContext().setAuthentication(admin);
        try {
            start.await(10, TimeUnit.SECONDS);
            update(type, value);
        } catch (Exception e) { throw new RuntimeException(e); }
        finally { SecurityContextHolder.clearContext(); }
    }

    @Test void failingCurrentUpdateRollsBackItsAlreadyInsertedHistory() {
        String type = fixture();
        Map<String, Object> before = current(type);
        String hook = "tpl_history_fail_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try {
            // Only this fixture's UPDATE runs the hook. It first proves that predecessor history
            // was actually inserted in this transaction, then fails with a check-violation code.
            jdbc.execute("create function " + hook + "() returns trigger language plpgsql as $$ begin "
                    + "if not exists (select 1 from doc_template_history where doc_type=old.doc_type "
                    + "and title_tpl=old.title_tpl and content_tpl=old.content_tpl) then "
                    + "raise exception 'Expected preceding history insert is missing' using errcode='55000'; end if; "
                    + "raise exception 'Forced fixture template update failure after history insert' using errcode='23514'; end; $$");
            jdbc.execute("create trigger " + hook + " before update on doc_template for each row "
                    + "when (old.doc_type='" + type + "') execute function " + hook + "()");
            var failure = assertThrows(DataIntegrityViolationException.class,
                    () -> controller.updateTemplate(type, Map.of("titleTpl", "must roll back", "contentTpl", "valid content"), admin));
            assertTrue(failure.getMessage().contains("Forced fixture template update failure after history insert"),
                    "The failure must come after the fixture's history insertion, not input validation");
        } finally {
            jdbc.execute("drop trigger if exists " + hook + " on doc_template");
            jdbc.execute("drop function if exists " + hook + "()");
        }
        assertEquals(before, current(type), "Failed update must preserve title, content and update time");
        assertTrue(history(type).isEmpty(), "No history row may survive a failed template update");
    }
}
