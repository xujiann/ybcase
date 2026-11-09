package cn.ybcase.bureau.web;
import cn.ybcase.bureau.service.InterviewChangeService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping({"/api/bureau/interview-changes","/api/agency/interview-changes"})
@PreAuthorize("hasAnyRole('HANDLER','LEGAL','LEADER','ADMIN','AGENCY')")
@RequiredArgsConstructor
public class InterviewChangeController {
 private final InterviewChangeService changes;
 public record Review(Boolean approve,String opinion){}
 public record Cancel(String opinion){}
 @GetMapping("/records") public R<Map<String,Object>> records(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,Authentication a){return R.ok(changes.records(a.getName(),page,size));}
 @GetMapping("/pending") public R<Map<String,Object>> pending(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,Authentication a){return R.ok(changes.pending(a.getName(),page,size));}
 @GetMapping("/{id}") public R<List<Map<String,Object>>> history(@PathVariable long id,Authentication a){return R.ok(changes.history(id,a.getName()));}
 @PostMapping("/{id}") public R<Map<String,Long>> request(@PathVariable long id,@RequestBody InterviewChangeService.Request b,Authentication a){return R.ok(Map.of("id",changes.request(id,b,a.getName())));}
 @PostMapping("/requests/{id}/review") public R<Void> review(@PathVariable long id,@RequestBody Review b,Authentication a){
  if(b==null||b.approve()==null)throw new cn.ybcase.bureau.common.BizException(2196,"请选择复核结论");changes.decide(id,b.approve(),b.opinion(),a.getName());return R.ok();}
 @PostMapping("/requests/{id}/cancel") public R<Void> cancel(@PathVariable long id,@RequestBody Cancel b,Authentication a){changes.cancel(id,b==null?null:b.opinion(),a.getName());return R.ok();}
}
