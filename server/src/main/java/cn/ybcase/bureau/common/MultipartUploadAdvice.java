package cn.ybcase.bureau.common;

import cn.ybcase.core.common.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

/** Multipart parsing can fail before a controller is selected, so package-scoped advice cannot handle it. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MultipartUploadAdvice {
    @ExceptionHandler(MultipartException.class)
    public R<Void> handleMultipart(MultipartException failure, HttpServletRequest request) {
        request.setAttribute(BureauAuditFilter.BIZ_CODE_ATTR, 2054);
        return R.fail(2054, "上传请求超过服务端接收限制或格式不正确，请检查文件大小及文件名等信息后重传");
    }
}
