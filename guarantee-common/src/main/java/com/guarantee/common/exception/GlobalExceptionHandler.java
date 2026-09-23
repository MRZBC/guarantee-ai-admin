package com.guarantee.common.exception;

import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把各类异常统一收敛为 {@link Result}，避免异常细节泄漏到前端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 权限不足的统一话术。
     *
     * <p>刻意**不带具体权限码**：这里是全站所有 {@code @PreAuthorize} 拒绝的唯一出口，
     * 不同接口需要的权限各不相同，写死一个码反而会误导用户去找那个权限。
     * 需要精确话术的场景（例如 AI 工具）由各自的守卫先给出（{@code AiPermissionGuard}），
     * 走不到这里。</p>
     */
    private static final String ACCESS_DENIED_MESSAGE = "你当前没有该操作的权限，请联系管理员";

    /** 业务异常：可预期，只记 warn。 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException ex, HttpServletRequest request) {
        log.warn("业务异常 uri={} code={} message={}", request.getRequestURI(), ex.getCode(), ex.getMessage());
        return Result.fail(ex.getCode(), ex.getMessage());
    }

    /** @Valid 校验失败（RequestBody）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.BAD_REQUEST, message.isEmpty() ? ResultCode.BAD_REQUEST.message() : message);
    }

    /** 表单绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.BAD_REQUEST, message.isEmpty() ? ResultCode.BAD_REQUEST.message() : message);
    }

    /** 方法参数级校验失败（@Validated + @RequestParam）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParameter(MissingServletRequestParameterException ex) {
        return Result.fail(ResultCode.BAD_REQUEST, "缺少必要参数: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return Result.fail(ResultCode.BAD_REQUEST, "参数类型不正确: " + ex.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException ex) {
        return Result.fail(ResultCode.BAD_REQUEST, "请求体格式不正确");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("唯一约束冲突: {}", ex.getMostSpecificCause().getMessage());
        return Result.fail(ResultCode.DUPLICATE_KEY);
    }

    /**
     * 访问了不存在的路径（没有对应的 Controller，也没有对应的静态资源）。
     *
     * <p>必须单独处理：否则会被下面的兜底分支当成"系统内部错误"返回 500，
     * 把「路径写错了」误报成「服务端故障」。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("路径不存在: {} {}", request.getMethod(), request.getRequestURI());
        return Result.fail(ResultCode.NOT_FOUND, "路径不存在: " + request.getRequestURI());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException ex, HttpServletRequest request) {
        log.warn("无对应处理器: {} {}", request.getMethod(), request.getRequestURI());
        return Result.fail(ResultCode.NOT_FOUND, "路径不存在: " + request.getRequestURI());
    }

    /**
     * 权限不足：方法级鉴权（{@code @PreAuthorize}）拒绝。
     *
     * <p><b>为什么必须单独处理</b>：{@code @PreAuthorize} 拒绝时抛的是
     * {@link AccessDeniedException}（Spring Security 6.3+ 实际抛其子类
     * {@code AuthorizationDeniedException}）。不显式处理时它会落进下面的兜底分支，
     * 被当成"未处理异常"返回 {@code code=500 系统内部错误}：
     * 用户明明是"权限不足"，看到的却是"系统内部错误"——既不属实、也不可操作，
     * 前端的 403 文案（"没有权限访问该资源"）永远命中不了，
     * 而且每次权限拒绝都会打一条 ERROR 堆栈，把日志刷得像真出了故障。</p>
     *
     * <p><b>记 warn 不记 error</b>：权限拒绝是预期内的正常结果（用户点了不该点的入口，
     * 或直接请求了没有权限的接口），不是服务端故障，不该产生错误告警。</p>
     *
     * <p><b>覆盖范围</b>：只覆盖"已登录但权限不足"。未登录（无 token / token 失效）
     * 由 JWT 过滤器在进入 Controller 之前就返回 401，不会走到这里。</p>
     */
    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("权限不足 uri={} {} message={}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return Result.fail(ResultCode.FORBIDDEN, ACCESS_DENIED_MESSAGE);
    }

    /** 兜底：非预期异常必须记录堆栈。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception ex, HttpServletRequest request) {
        log.error("未处理异常 uri={}", request.getRequestURI(), ex);
        return Result.fail(ResultCode.INTERNAL_ERROR);
    }

    private static String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
