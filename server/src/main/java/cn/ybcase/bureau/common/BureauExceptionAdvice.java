package cn.ybcase.bureau.common;

import cn.ybcase.core.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice(basePackages = "cn.ybcase")
public class BureauExceptionAdvice {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        return R.fail(e.code, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegal(IllegalArgumentException e) {
        return R.fail(2000, e.getMessage());
    }

    /** @PreAuthorize 拒绝：转 JSON 而非错误页（角色分权矩阵） */
    @ExceptionHandler(AccessDeniedException.class)
    public R<Void> handleDenied(AccessDeniedException e) {
        return R.fail(403, "当前角色无权执行该操作（批准/审核/决定类操作须相应岗位）");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleUpload(MaxUploadSizeExceededException e) {
        return R.fail(2054, "附件超过大小限制（10MB）");
    }

    /** 兜底：未预料异常不再落到默认错误页（避免被安全层转成误导性 401） */
    @ExceptionHandler(Exception.class)
    public R<Void> handleAny(Exception e) {
        log.error("未处理异常", e);
        return R.fail(500, "系统内部错误，请联系管理员（详情见服务端日志）");
    }
}
