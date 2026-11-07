package cn.ybcase.bureau.web;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.*;
import cn.ybcase.bureau.spi.IntegrationGateway;
import cn.ybcase.core.common.R;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/bureau/integrations")
@RequiredArgsConstructor
public class IntegrationOperationsController {
    private final TreasuryBatchService batches;
    private final IntegrationGateway gateway;
    private final MaterialAccess access;
    private final JdbcTemplate jdbc;
    @GetMapping("/treasury/sources")
    public R<?> sources(Authentication auth,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q) {return R.ok(batches.sources(auth.getName(),page,size,q));}
    @GetMapping("/treasury/batches") public R<?> list(Authentication auth,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return R.ok(batches.batches(auth.getName(),page,size));}
    @GetMapping("/treasury/batches/{id}") public R<?> detail(Authentication auth,@PathVariable long id) {return R.ok(batches.detail(auth.getName(),id));}
    @GetMapping("/treasury/items/{id}") public R<?> itemDetail(Authentication auth,@PathVariable long id) {return R.ok(batches.itemDetail(auth.getName(),id));}
    @PostMapping("/treasury/batches")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<?> create(Authentication auth,@RequestBody JsonNode body) {
        List<TreasuryBatchService.Entry> rows=new ArrayList<>();
        JsonNode entries=body.path("executions");
        if(entries.isArray()) for(var entry:entries) rows.add(new TreasuryBatchService.Entry(text(entry,"source"),positive(entry.path("id"))));
        else if(body.path("executionIds").isArray()) for(var id:body.path("executionIds")) rows.add(new TreasuryBatchService.Entry("ORIGINAL",positive(id)));
        return R.ok(Map.of("id",batches.create(auth.getName(),text(body,"requestKey"),rows)));
    }
    private static long positive(JsonNode node) {if(!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()<1) throw new BizException(2193,"编号须为正整数");return node.longValue();}
    private static String text(JsonNode body,String key) {var value=body.path(key);if(value.isMissingNode()||value.isNull())return null;if(!value.isTextual())throw new BizException(2193,"字段格式无效");return value.textValue();}
    @PostMapping("/treasury/batches/{id}/run") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<?> run(Authentication auth,@PathVariable long id) {return R.ok(batches.run(auth.getName(),id));}
    @PostMapping("/treasury/items/{id}/retry") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<?> retry(Authentication auth,@PathVariable long id) {batches.retry(auth.getName(),id);return R.ok();}
    @PostMapping("/treasury/items/{id}/assign") @PreAuthorize("hasAnyRole('LEGAL','LEADER','ADMIN')")
    public R<?> assign(Authentication auth,@PathVariable long id,@RequestBody JsonNode body) {batches.assign(auth.getName(),id,text(body,"username"));return R.ok();}
    @PostMapping("/treasury/items/{id}/investigate") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<?> investigate(Authentication auth,@PathVariable long id,@RequestBody JsonNode body) {batches.investigate(auth.getName(),id,text(body,"note"),text(body,"sourceReference"));return R.ok();}
    @PostMapping("/treasury/items/{id}/review") @PreAuthorize("hasAnyRole('LEGAL','LEADER')")
    public R<?> review(Authentication auth,@PathVariable long id,@RequestBody JsonNode body) {if(!body.path("approve").isBoolean())throw new BizException(2193,"复核结论格式无效");batches.review(auth.getName(),id,body.path("approve").booleanValue(),text(body,"reason"));return R.ok();}
    @GetMapping("/readiness") @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public R<?> readiness() {
        access.requireCurrentReadRoles("ADMIN");
        var result=new LinkedHashMap<String,Object>();
        for(String target:List.of("monitor","signature","treasury","delivery")) result.put(target,gateway.readiness(target));
        result.put("deliveryCallbackConfigured",gateway.callbackConfigured());
        result.put("deliveryTimeoutScanEnabled",gateway.timeoutScanEnabled());
        result.put("operations",jdbc.queryForList("select id,target,operation,actor,source_id,status,error_code,created_at from integration_operation order by id desc limit 50"));
        return R.ok(result);
    }
    @PostMapping("/probe/{target}") @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<?> probe(Authentication auth,@PathVariable String target) {
        if(!Set.of("monitor","signature","treasury","delivery").contains(target)) throw new BizException(2193,"网关类型无效");
        access.requireCurrentRoles("ADMIN");
        String status="FAILED",code=null;
        try {var response=gateway.post(target,"health/check",Map.of("probe",true),null);if(!response.path("ready").isBoolean() || !response.path("ready").booleanValue()) throw IntegrationGateway.invalidResponse();status="READY";}
        catch(BizException ex) {code=Integer.toString(ex.code);}
        jdbc.update("insert into integration_operation(target,operation,actor,status,error_code) values(?,'PROBE',?,?,?)",target,auth.getName(),status,code);
        return R.ok(Map.of("status",status));
    }
}
