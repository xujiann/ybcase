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
    public R<Void> handleBiz(BizException e, jakarta.servlet.http.HttpServletRequest req) {
        req.setAttribute(BureauAuditFilter.BIZ_CODE_ATTR, e.code);
        return R.fail(e.code, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegal(IllegalArgumentException e) {
        return R.fail(2000, e.getMessage());
    }

    /** @PreAuthorize 拒绝：转 JSON 而非错误页（角色分权矩阵） */
    @ExceptionHandler(AccessDeniedException.class)
    public R<Void> handleDenied(AccessDeniedException e, jakarta.servlet.http.HttpServletRequest req) {
        req.setAttribute(BureauAuditFilter.BIZ_CODE_ATTR, 403);
        return R.fail(403, "当前角色无权执行该操作（批准/审核/决定类操作须相应岗位）");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleUpload(MaxUploadSizeExceededException e) {
        return R.fail(2054, "附件超过服务端接收上限，请压缩后重传或改用外置存储模式");
    }

    /** 请求本身不合法（少传必填参数、类型不符、JSON 畸形）：给可读的业务提示而非 500 */
    @ExceptionHandler({
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class})
    public R<Void> handleBadRequest(Exception e) {
        log.warn("请求参数不合法: {}", e.getMessage());
        return R.fail(2100, "请求参数不完整或格式不正确：" + shortMsg(e));
    }

    /**
     * 日期/数值格式解析失败统一转业务码。
     * 刻意不含 NullPointerException/ClassCastException——那类异常多半是服务端缺陷，
     * 一旦伪装成"参数不合法"就再也不会被当成 bug 修（取值安全由 ReqValues 在入口保证）。
     */
    @ExceptionHandler({java.time.format.DateTimeParseException.class, NumberFormatException.class})
    public R<Void> handleBadValue(RuntimeException e) {
        log.warn("请求字段取值不合法: {}", e.getMessage());
        return R.fail(2100, "提交的字段取值不合法（日期须为 yyyy-MM-dd，数值字段须为数字）");
    }

    /** 数据库约束/取值冲突（重复键、外键、非空、非法日期字面量） */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public R<Void> handleData(org.springframework.dao.DataAccessException e) {
        log.warn("数据访问异常", e);
        if (e instanceof org.springframework.dao.DuplicateKeyException)
            return R.fail(2101, "该记录已存在，请勿重复提交");
        return R.fail(2101, "数据校验未通过：请检查关联对象是否存在、必填项是否完整、日期格式是否正确");
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) return "";
        int cut = m.indexOf(':');
        String s = cut > 0 && cut < 80 ? m.substring(0, cut) : m;
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    /** 兜底：未预料异常不再落到默认错误页（避免被安全层转成误导性 401） */
    @ExceptionHandler(Exception.class)
    public R<Void> handleAny(Exception e, jakarta.servlet.http.HttpServletRequest req) {
        log.error("未处理异常", e);
        req.setAttribute(BureauAuditFilter.BIZ_CODE_ATTR, 500);
        return R.fail(500, "系统内部错误，请联系管理员（详情见服务端日志）");
    }
}
