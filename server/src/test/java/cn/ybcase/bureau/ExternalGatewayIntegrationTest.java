package cn.ybcase.bureau;

import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ExternalGatewayIntegrationTest {
    private static final AtomicReference<String> MONITOR = new AtomicReference<>("合成疑点");
    private static final AtomicReference<String> AMOUNT = new AtomicReference<>("23.45");
    private static final AtomicInteger SIGN = new AtomicInteger();
    private static final AtomicInteger RECEIPT = new AtomicInteger();
    private static final AtomicInteger VERIFY = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer GATEWAY = gateway();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired BureauConfig config;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        for (String name : List.of("monitor", "treasury", "signature")) {
            registry.add("ybcase.integration." + name + ".enabled", () -> "true");
            registry.add("ybcase.integration." + name + ".url", () -> "http://127.0.0.1:" + GATEWAY.getAddress().getPort());
            registry.add("ybcase.integration." + name + ".token", () -> "fixture-token");
        }
    }

    @AfterAll static void stop() { GATEWAY.stop(0); }

    @Test void monitorDeduplicatesAndRefusesChangedExternalItem() throws Exception {
        var bureau = actor("BUREAU", 1);
        var district = actor("DISTRICT", 2);
        String old = jdbc.queryForObject("select cfg_value from sys_config where cfg_key='monitor_feed_enabled'", String.class);
        setMonitor(true);
        try {
            assertEquals(403, postAs(district, "/clues/fetch-monitor", Map.of()).path("code").asInt());
            String path = "/clues/fetch-monitor";
            var first = postAs(bureau, path, Map.of());
            assertEquals(1, first.path("data").path("fetched").asInt());
            var retry = postAs(bureau, path, Map.of());
            assertEquals(0, retry.path("data").path("fetched").asInt());
            assertEquals(1, retry.path("data").path("skipped").asInt());
            MONITOR.set("被重新解释的疑点");
            assertEquals(2162, postAs(bureau, path, Map.of()).path("code").asInt());
            assertEquals(1, jdbc.queryForObject("select count(*) from external_monitor_clue where external_id='monitor-fixture-1'", Integer.class));
        } finally {
            MONITOR.set("合成疑点");
            setMonitor(Boolean.parseBoolean(old));
        }
    }

    @Test void treasuryChecksButDoesNotChangeExecutionAndCaSigningIsBoundToActor() throws Exception {
        var bureau = actor("BUREAU", 1);
        var district = actor("DISTRICT", 2);
        long id = jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,status,owner_dept_id,owner_user)
                values('GW-CASE-1','网关测试案件',(select min(id) from case_cause),'合成对象','PROVIDER',
                    current_date,current_date,'DELIVERED',1,?) returning id
                """, Long.class, bureau.name());
        long execution = jdbc.queryForObject("""
                insert into case_execution(case_id,kind,amount,paid_at,method,receipt_no)
                values(?,'FINE',23.45,date '2026-10-15','BANK','GW-RECEIPT-1') returning id
                """, Long.class, id);
        String receipt = "/cases/" + id + "/executions/" + execution + "/receipt-check";
        assertEquals(2080, postAs(district, receipt, Map.of()).path("code").asInt());
        assertEquals(0, RECEIPT.get());
        assertEquals("MATCH", postAs(bureau, receipt, Map.of()).path("data").path("status").asText());
        AMOUNT.set("23.45000000000000001");
        var difference = postAs(bureau, receipt, Map.of()).path("data");
        assertEquals("DIFFERENT", difference.path("status").asText());
        assertFalse(difference.path("amountMatches").asBoolean());
        assertEquals("23.45", jdbc.queryForObject("select amount::text from case_execution where id=?", String.class, execution));

        long doc = jdbc.queryForObject("""
                insert into case_document(case_id,doc_type,title,content,made_at)
                values(?,'OTHER','测试文书','签章校验内容',current_date) returning id
                """, Long.class, id);
        String document = "/cases/" + id + "/documents/" + doc;
        assertEquals(403, postAs(bureau, document + "/sign", Map.of("signer", "another-user")).path("code").asInt());
        assertEquals(0, SIGN.get());
        assertEquals("CA_GATEWAY", postAs(bureau, document + "/sign", Map.of()).path("data").path("provider").asText());
        assertEquals(0, postAs(bureau, document + "/sign", Map.of()).path("code").asInt());
        assertEquals(1, SIGN.get());
        var verified = read(mvc.perform(get("/api/bureau" + document + "/verify")
                .header("Authorization", "Bearer " + bureau.token())).andReturn().getResponse().getContentAsByteArray());
        assertTrue(verified.path("data").get(0).path("valid").asBoolean());
        assertEquals(1, VERIFY.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from doc_signature where document_id=?", Integer.class, doc));
    }

    private void setMonitor(boolean value) {
        jdbc.update("update sys_config set cfg_value=? where cfg_key='monitor_feed_enabled'", Boolean.toString(value));
        config.onConfigChanged(new ConfigChangedEvent("monitor_feed_enabled"));
    }

    private record Actor(String name, String token) {}

    private Actor actor(String category, long dept) {
        var user = new SysUser();
        user.setUsername("gateway_" + System.nanoTime());
        user.setRealName("网关联调人员");
        user.setPassword("unused");
        user.setAccountCategory(category);
        user.setDeptId(dept);
        user.getRoles().add(roles.findByCode("LEADER").orElseThrow());
        user = users.saveAndFlush(user);
        return new Actor(user.getUsername(), jwt.issue(user.getUsername(), user.getTokenVersion()));
    }

    private JsonNode postAs(Actor actor, String path, Object body) throws Exception {
        return read(mvc.perform(post("/api/bureau" + path).header("Authorization", "Bearer " + actor.token())
                .contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode read(byte[] raw) throws IOException { return mapper.readTree(raw); }

    private static HttpServer gateway() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/clues/pull", ex -> send(ex, Map.of("items", List.of(Map.of(
                    "externalId", "monitor-fixture-1", "suspectName", "合成对象", "suspectType", "PROVIDER", "content", MONITOR.get())))));
            server.createContext("/receipts/lookup", ex -> {
                RECEIPT.incrementAndGet();
                send(ex, Map.of("status", "FOUND", "receiptNo", "GW-RECEIPT-1", "paid", true,
                        "amount", new java.math.BigDecimal(AMOUNT.get()), "paidDate", "2026-10-15"));
            });
            server.createContext("/signatures/sign", ex -> {
                SIGN.incrementAndGet();
                var input = JSON.readTree(ex.getRequestBody());
                if (ex.getRequestHeaders().getFirst("Idempotency-Key") == null) {
                    ex.sendResponseHeaders(400, -1);
                    ex.close();
                    return;
                }
                send(ex, Map.of("contentHash", input.path("contentHash").asText(), "signer", input.path("signer").asText(),
                        "signature", "fixture-ca-signature"));
            });
            server.createContext("/signatures/verify", ex -> {
                VERIFY.incrementAndGet();
                send(ex, Map.of("valid", true));
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void send(HttpExchange exchange, Object result) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())
                    || !"Bearer fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            byte[] data = JSON.writeValueAsString(result).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
        }
    }
}
