package cn.ybcase.bureau.web;

import cn.ybcase.bureau.service.ElectronicDeliveryService;
import cn.ybcase.core.common.R;
import cn.ybcase.bureau.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ElectronicDeliveryController {
    private final ElectronicDeliveryService service;
    @GetMapping("/api/bureau/cases/{caseId}/electronic-deliveries")
    public R<?> list(Authentication auth,@PathVariable long caseId) {return R.ok(service.list(auth.getName(),caseId));}
    @PostMapping("/api/bureau/cases/{caseId}/electronic-deliveries")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<?> create(Authentication auth,@PathVariable long caseId,@RequestBody JsonNode body) {return R.ok(java.util.Map.of("id",service.create(auth.getName(),caseId,positive(body.path("documentId")),text(body,"requestKey"))));}
    @PostMapping("/api/bureau/cases/{caseId}/electronic-deliveries/supplements") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<?> createSupplement(Authentication auth,@PathVariable long caseId,@RequestBody JsonNode body){return R.ok(java.util.Map.of("id",service.createSupplement(auth.getName(),caseId,positive(body.path("documentId")),text(body,"requestKey"))));}
    @PostMapping("/api/bureau/cases/{caseId}/electronic-deliveries/{id}/replace") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<?> replace(Authentication auth,@PathVariable long caseId,@PathVariable long id,@RequestBody JsonNode body){if(!body.path("providerChecked").isBoolean())throw new BizException(2193,"请明确供应商结果已人工核对与否");return R.ok(java.util.Map.of("id",service.replace(auth.getName(),caseId,id,text(body,"requestKey"),text(body,"reason"),body.path("providerChecked").booleanValue())));}
    @GetMapping("/api/bureau/cases/{caseId}/electronic-deliveries/events/{eventId}/evidence")
    public org.springframework.http.ResponseEntity<byte[]> evidence(Authentication auth,@PathVariable long caseId,@PathVariable long eventId){var f=service.receiptEvidence(auth.getName(),caseId,eventId);return org.springframework.http.ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-SHA256",f.sha256()).header("Content-Disposition","attachment; filename=\""+f.filename()+"\"").contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM).body(f.bytes());}
    @PostMapping("/api/bureau/cases/{caseId}/electronic-deliveries/{id}/send")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<?> send(Authentication auth,@PathVariable long caseId,@PathVariable long id) {return R.ok(service.send(auth.getName(),caseId,id));}
    @PostMapping("/api/bureau/cases/{caseId}/electronic-deliveries/expire")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<?> expire(Authentication auth,@PathVariable long caseId) {return R.ok(java.util.Map.of("expired",service.expire(auth.getName(),caseId)));}
    @PostMapping("/api/integrations/delivery/receipts") @PreAuthorize("hasRole('INTEGRATION')")
    public R<?> receipt(Authentication auth,@RequestBody JsonNode body) {
        return R.ok(service.receipt(auth.getName(),new ElectronicDeliveryService.Receipt(positive(body.path("deliveryId")),text(body,"providerId"),text(body,"eventId"),text(body,"status"),text(body,"occurredAt"),text(body,"receiptNo"),text(body,"evidenceBase64"),text(body,"evidenceSha256"),text(body,"evidenceContentType"))));
    }
    private static long positive(JsonNode node) {if(!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<1)throw new BizException(2193,"编号须为正整数");return node.longValue();}
    private static String text(JsonNode body,String key) {JsonNode value=body.path(key);if(value.isMissingNode()||value.isNull())return null;if(!value.isTextual())throw new BizException(2193,"字段格式无效");return value.textValue();}
}
