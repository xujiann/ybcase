package cn.ybcase.bureau;

import cn.ybcase.bureau.service.ClueService;
import cn.ybcase.bureau.service.OversightService;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Terminal approval/completion must not silently round money or reinterpret non-boolean facts. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class OversightInputIntegrityIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired ClueService clues;
    @Autowired OversightService oversight;
    @Autowired jakarta.persistence.EntityManager entityManager;

    record Actor(String username, String token) {}

    @BeforeEach void isolate() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class));
        SecurityContextHolder.clearContext();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    Actor actor(String role) {
        var user = new SysUser();
        user.setUsername("oversight_input_" + UUID.randomUUID().toString().replace("-", ""));
        user.setRealName("监督输入回归");user.setPassword("unused-jwt-fixture");
        user.getRoles().add(roles.findByCode(role).orElseThrow());
        user = users.saveAndFlush(user);
        return new Actor(user.getUsername(), jwt.issue(user.getUsername(), user.getTokenVersion()));
    }

    int postCode(Actor actor, String path, Object body) throws Exception {
        var response = mvc.perform(post("/api/bureau" + path)
                .header("Authorization", "Bearer " + actor.token()).contentType("application/json")
                .content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        return mapper.readTree(response.getContentAsString()).path("code").asInt();
    }

    long reward(Actor leader) {
        long clueId = clues.create(new ClueService.ClueCreateReq("COMPLAINT", "合成奖励来源", "合成机构",
                "PROVIDER", LocalDate.now(), leader.username()), leader.username()).getId();
        jdbc.update("update case_clue set status='FILED' where id=?", clueId);
        entityManager.clear();
        oversight.createReward(new OversightService.RewardReq(clueId, "合成举报人", "合成联系方式", null));
        return jdbc.queryForObject("select id from reward where clue_id=?", Long.class, clueId);
    }

    long inspection(Actor handler) {
        return ((Number) oversight.createInspection(new OversightService.InspectionReq(null,
                "合成检查机构", "PROVIDER", "检查甲、检查乙", LocalDate.now()), handler.username()).get("id")).longValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.005", "9999999999.991"})
    void subCentRewardAmountCannotConsumeTheOnlyApproval(String amount) throws Exception {
        var leader = actor("LEADER");long id = reward(leader);
        var before = jdbc.queryForMap("select * from reward where id=?", id);
        assertEquals(2064, postCode(leader, "/rewards/" + id + "/approve", Map.of("amount", new BigDecimal(amount))),
                "An approval must not round a fractional-cent amount into an irreversible monetary fact");
        assertEquals(before, jdbc.queryForMap("select * from reward where id=?", id));
        assertEquals(0, postCode(leader, "/rewards/" + id + "/approve", Map.of("amount", new BigDecimal("0.01"))));
        assertEquals(new BigDecimal("0.01"), jdbc.queryForObject("select amount from reward where id=?", BigDecimal.class, id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000000000", "999999999999.99"})
    void rewardAmountBeyondItsActualNumeric12ColumnIsRejectedBeforeSql(String amount) throws Exception {
        var leader = actor("LEADER");long id = reward(leader);
        var before = jdbc.queryForMap("select * from reward where id=?", id);
        assertEquals(2064, postCode(leader, "/rewards/" + id + "/approve", Map.of("amount", new BigDecimal(amount))));
        assertEquals(before, jdbc.queryForMap("select * from reward where id=?", id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "10.2300", "9999999999.99"})
    void exactCentRewardsWithinActualColumnRangeKeepTheirAmountThroughPayment(String amount) throws Exception {
        var leader = actor("LEADER");long id = reward(leader);
        BigDecimal expected = new BigDecimal(amount).setScale(2);
        assertEquals(0, postCode(leader, "/rewards/" + id + "/approve", Map.of("amount", new BigDecimal(amount))));
        assertEquals(expected, jdbc.queryForObject("select amount from reward where id=?", BigDecimal.class, id));
        assertEquals(0, postCode(leader, "/rewards/" + id + "/pay", Map.of()));
        assertEquals(expected, jdbc.queryForObject("select amount from reward where id=?", BigDecimal.class, id));
        assertNotNull(jdbc.queryForObject("select paid_at from reward where id=?", LocalDate.class, id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"检查甲、检查甲", "检查甲， 检查甲 , ", "、 、检查甲", "、，,"})
    void inspectionNeedsTwoDistinctNonblankInspectors(String officers) throws Exception {
        var handler = actor("HANDLER");
        assertEquals(2063, postCode(handler, "/inspections", Map.of(
                "objectName", "人数校验机构", "objectType", "PROVIDER",
                "officers", officers, "plannedAt", LocalDate.now().toString())));
        assertEquals(0, jdbc.queryForObject("select count(*) from inspection where created_by=?", Integer.class, handler.username()));
        assertTrue(inspection(handler) > 0);
    }

    static Stream<Object> nonBooleanFlags() {
        return Stream.of("true", "false", 1, 0, List.of(true), Map.of("value", true));
    }

    static Stream<Object> nonStringResults() {
        return Stream.of(1, true, List.of("检查结果"), Map.of("value", "检查结果"));
    }

    @ParameterizedTest
    @MethodSource("nonStringResults")
    void nonStringInspectionResultCannotConsumeCompletion(Object result) throws Exception {
        var handler = actor("HANDLER");long id = inspection(handler);
        var before = jdbc.queryForMap("select * from inspection where id=?", id);
        assertEquals(2063, postCode(handler, "/inspections/" + id + "/complete",
                Map.of("result", result, "violationFound", true)));
        assertEquals(before, jdbc.queryForMap("select * from inspection where id=?", id));
        assertEquals(0, postCode(handler, "/inspections/" + id + "/complete",
                Map.of("result", "违法：合成检查结果", "violationFound", true)));
        assertEquals("违法：合成检查结果", jdbc.queryForObject("select result from inspection where id=?", String.class, id));
    }

    @ParameterizedTest
    @MethodSource("nonBooleanFlags")
    void nonBooleanViolationFlagCannotIrreversiblyCompleteInspection(Object flag) throws Exception {
        var handler = actor("HANDLER");long id = inspection(handler);
        var before = jdbc.queryForMap("select * from inspection where id=?", id);
        assertEquals(2063, postCode(handler, "/inspections/" + id + "/complete",
                Map.of("result", "违法：合成检查结果", "violationFound", flag)),
                "A malformed flag must not be silently stored as no violation found");
        assertEquals(before, jdbc.queryForMap("select * from inspection where id=?", id));
        assertEquals(0, postCode(handler, "/inspections/" + id + "/complete",
                Map.of("result", "违法：合成检查结果", "violationFound", true)));
        assertEquals(true, jdbc.queryForObject("select violation_found from inspection where id=?", Boolean.class, id));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void actualBooleanViolationFlagsRetainTheirMeaning(boolean flag) throws Exception {
        var handler = actor("HANDLER");long id = inspection(handler);
        assertEquals(0, postCode(handler, "/inspections/" + id + "/complete", Map.of("result", "合成检查结果", "violationFound", flag)));
        assertEquals(flag, jdbc.queryForObject("select violation_found from inspection where id=?", Boolean.class, id));
        assertNotNull(jdbc.queryForObject("select done_at from inspection where id=?", LocalDate.class, id));
    }

    static Stream<Map<String, Object>> legacyAbsentFlags() {
        Map<String, Object> explicitNull = new HashMap<>();
        explicitNull.put("result", "原合同默认无违法");explicitNull.put("violationFound", null);
        return Stream.of(Map.of("result", "原合同默认无违法"), explicitNull);
    }

    @ParameterizedTest
    @MethodSource("legacyAbsentFlags")
    void omittedAndExplicitNullFlagsRetainTheExistingFalseDefault(Map<String, Object> body) throws Exception {
        var handler = actor("HANDLER");long id = inspection(handler);
        assertEquals(0, postCode(handler, "/inspections/" + id + "/complete", body));
        assertEquals(false, jdbc.queryForObject("select violation_found from inspection where id=?", Boolean.class, id));
        assertNotNull(jdbc.queryForObject("select done_at from inspection where id=?", LocalDate.class, id));
    }
}
