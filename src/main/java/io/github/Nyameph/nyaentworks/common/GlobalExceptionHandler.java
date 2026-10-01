package io.github.Nyameph.nyaentworks.common;

import org.apache.catalina.connector.ClientAbortException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 把异常转成 {@link ApiResult}，让页面能显示原因而不是一个 500 白屏。
 * <p>这在本项目里格外要紧：绝大多数失败都是环境问题（目录不在、MySQL 没起、
 * 表没建），原因写在异常消息里，直接透给前端最省事。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数与状态问题属于「用户操作顺序不对」，消息本身就是给人看的，原样透出 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> handleBadRequest(RuntimeException e) {
        log.warn("操作被拒绝: {}", e.getMessage());
        return ApiResult.fail(e.getMessage());
    }

    /**
     * 静态资源 404（浏览器自动请求 favicon、误输路径）是预期噪音：不记 error。
     * <p>不加这个，浏览器每次打开页面都会触发 {@code NoResourceFoundException}，
     * 被 {@link #handleOther} 接住打一条完整堆栈 —— 刷屏且吓人。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleNoResource(NoResourceFoundException e) {
        log.debug("静态资源不存在: {}", e.getResourcePath());
    }

    /**
     * 客户端中途断连（拖进度条 / 切歌 / 关页面都会让浏览器中止请求）不是错误。
     * <p>不加这个，媒体端点（视频/音频流）在用户拖动进度条时抛出的
     * {@link ClientAbortException} / {@link AsyncRequestNotUsableException} 会被
     * {@link #handleOther} 接住，然后：
     * <ol>
     *   <li>记一条吓人的完整堆栈（其实连接没了，纯噪音）；</li>
     *   <li>尝试把 {@link ApiResult} 写回响应，但响应 Content-Type 已被媒体端点设成
     *       {@code video/mp4}，没有对应 converter，再刷出第二层
     *       {@code HttpMessageNotWritableException}。</li>
     * </ol>
     * 所以这里直接静默返回：不记 error、不写 body（void 返回不会触发序列化）。
     */
    @ExceptionHandler({ClientAbortException.class, AsyncRequestNotUsableException.class})
    public void handleClientAbort(Exception e) {
        log.debug("客户端断开连接，忽略: {}", e.getMessage());
    }

    /** 其余异常带上类名，光看 message 往往看不出是什么坏了（如 NPE 的 message 为 null） */
    @ExceptionHandler(Throwable.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<Void> handleOther(Throwable e) {
        log.error("请求处理失败", e);
        String message = StringUtils.defaultIfBlank(e.getMessage(), e.toString());
        return ApiResult.fail(e.getClass().getSimpleName() + ": " + message);
    }
}
