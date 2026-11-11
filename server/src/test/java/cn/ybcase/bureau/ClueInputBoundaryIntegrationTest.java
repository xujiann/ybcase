package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.ClueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ClueInputBoundaryIntegrationTest {
    @Autowired ClueService clues;
    @Autowired JdbcTemplate jdbc;

    private void rejectsWithoutNumber(ClueService.ClueCreateReq request) {
        long count = jdbc.queryForObject("select count(*) from case_clue", Long.class);
        String numbers = jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(s) order by kind,year)::text,'[]') from biz_seq s", String.class);
        assertEquals(2020, assertThrows(BizException.class, () -> clues.create(request, "banban")).code);
        assertEquals(count, jdbc.queryForObject("select count(*) from case_clue", Long.class));
        assertEquals(numbers, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(s) order by kind,year)::text,'[]') from biz_seq s", String.class));
    }

    @Test void requiredClueFieldsRejectEmptyValuesBeforeAllocatingANumber() {
        rejectsWithoutNumber(null);
        for (String value : Arrays.asList(null, "", "  ")) {
            rejectsWithoutNumber(new ClueService.ClueCreateReq(value,"内容","医院","PROVIDER",LocalDate.now(),null));
            rejectsWithoutNumber(new ClueService.ClueCreateReq("COMPLAINT","内容",value,"PROVIDER",LocalDate.now(),null));
            rejectsWithoutNumber(new ClueService.ClueCreateReq("COMPLAINT","内容","医院",value,LocalDate.now(),null));
        }
    }

    @Test void databaseTextLimitsAreReportedAsBusinessValidation() {
        rejectsWithoutNumber(new ClueService.ClueCreateReq("X".repeat(17),"内容","医院","PROVIDER",null,null));
        rejectsWithoutNumber(new ClueService.ClueCreateReq("COMPLAINT","内容","院".repeat(129),"PROVIDER",null,null));
        rejectsWithoutNumber(new ClueService.ClueCreateReq("COMPLAINT","内容","医院","X".repeat(17),null,null));
        rejectsWithoutNumber(new ClueService.ClueCreateReq("COMPLAINT","内容","医院","PROVIDER",null,"甲".repeat(65)));
    }

    @Test void invalidImportRowsRejectTheWholeBatchBeforeCreatingAnything() {
        long count = jdbc.queryForObject("select count(*) from case_clue", Long.class);
        String numbers = jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(s) order by kind,year)::text,'[]') from biz_seq s", String.class);
        var rows = List.of(Map.of("content","第一条","suspectName","有效医院"),
                Map.of("content","第二条","suspectName"," "));
        assertEquals(2020,assertThrows(BizException.class,()->clues.importMonitor(rows,"banban")).code);
        assertEquals(count,jdbc.queryForObject("select count(*) from case_clue",Long.class));
        assertEquals(numbers,jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(s) order by kind,year)::text,'[]') from biz_seq s",String.class));
    }

    @Test void existingSourcesAndUnicodeDatabaseBoundariesRemainSupported() {
        String name = "\uD83C\uDFE5".repeat(128);
        for(String source:List.of("INSPECTION","COMPLAINT","TRANSFER","ASSIGNED","MONITOR","TRACE_CODE")) {
            var clue=clues.create(new ClueService.ClueCreateReq(source,"有效内容",name,"OTHER",null,null),"banban");
            assertEquals(source,clue.getSource());
            assertEquals(name,clue.getSuspectName());
            assertEquals(LocalDate.now(),clue.getReceivedAt());
        }
        var result=clues.importMonitor(List.of(Map.of("content","默认主体类别","suspectName","医院")),"banban");
        assertEquals(1,result.get("imported"));
    }
}
