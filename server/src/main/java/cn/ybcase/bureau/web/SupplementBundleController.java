package cn.ybcase.bureau.web;
import cn.ybcase.bureau.service.SupplementBundleService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;
@RestController @RequiredArgsConstructor
@RequestMapping("/api/bureau/cases/{caseId}/supplement-bundles")
public class SupplementBundleController {
 private final SupplementBundleService service;
 @GetMapping public R<?> list(@PathVariable long caseId,Authentication a){return R.ok(service.list(caseId,a.getName()));}
 @PostMapping @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')") public R<?> create(@PathVariable long caseId,@RequestBody Map<String,Object>b,Authentication a){return R.ok(Map.of("id",service.create(caseId,b,a.getName())));}
 @PostMapping("/{id}/files") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')") public R<?> upload(@PathVariable long caseId,@PathVariable long id,@RequestParam MultipartFile file,Authentication a)throws Exception{return R.ok(Map.of("id",service.upload(caseId,id,file,a.getName())));}
 @PostMapping("/{id}/submit") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')") public R<?> submit(@PathVariable long caseId,@PathVariable long id,Authentication a){service.submit(caseId,id,a.getName());return R.ok();}
 @PostMapping("/{id}/review") @PreAuthorize("hasAnyRole('LEGAL','LEADER')") public R<?> review(@PathVariable long caseId,@PathVariable long id,@RequestBody Map<String,Object>b,Authentication a){if(!(b.get("approve") instanceof Boolean approval))throw new cn.ybcase.bureau.common.BizException(2193,"请选择批准或退回");service.review(caseId,id,approval,text(b,"opinion"),a.getName());return R.ok();}
 @PostMapping("/{id}/withdraw") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')") public R<?> withdraw(@PathVariable long caseId,@PathVariable long id,@RequestBody Map<String,Object>b,Authentication a){service.withdraw(caseId,id,text(b,"opinion"),a.getName());return R.ok();}
 @PostMapping("/{id}/documents") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')") public R<?> document(@PathVariable long caseId,@PathVariable long id,@RequestBody Map<String,Object>b,Authentication a){return R.ok(Map.of("id",service.document(caseId,id,b,a.getName())));}
 @PostMapping("/documents/{documentId}/sign") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')") public R<?> sign(@PathVariable long caseId,@PathVariable long documentId,Authentication a){return R.ok(service.sign(caseId,documentId,a.getName()));}
 @PostMapping("/documents/{documentId}/deliver") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')") public R<?> deliver(@PathVariable long caseId,@PathVariable long documentId,@RequestBody Map<String,Object>b,Authentication a){return R.ok(Map.of("id",service.recordDelivery(caseId,documentId,b,a.getName())));}
 @PostMapping("/documents/{documentId}/consent") @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')") public R<?> consent(@PathVariable long caseId,@PathVariable long documentId,@RequestBody Map<String,Object>b,Authentication a){service.consent(caseId,documentId,b,a.getName());return R.ok();}
 @GetMapping("/files/{fileId}") public ResponseEntity<byte[]> file(@PathVariable long caseId,@PathVariable long fileId,Authentication a){return response(service.file(caseId,fileId,a.getName()));}
 @GetMapping("/{id}/export") public ResponseEntity<byte[]> export(@PathVariable long caseId,@PathVariable long id,Authentication a)throws Exception{return response(service.export(caseId,id,a.getName()));}
 private static ResponseEntity<byte[]> response(SupplementBundleService.FileData f){return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-SHA256",f.sha256()).header("X-Content-Type-Options","nosniff").header("Content-Disposition",ContentDisposition.attachment().filename(f.filename(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).contentType(MediaType.APPLICATION_OCTET_STREAM).body(f.bytes());}
 private static String text(Map<String,Object>b,String key){if(!(b.get(key) instanceof String value))throw new cn.ybcase.bureau.common.BizException(2193,"字段须为文本");return value;}
}
