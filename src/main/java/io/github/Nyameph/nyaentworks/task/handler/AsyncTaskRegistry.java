package io.github.Nyameph.nyaentworks.task.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code type → handler} 的注册表，由 Spring 把所有 {@link AsyncTaskHandler} bean 注进来。
 * <p>不用手写注册代码：加一类任务 = 加一个 {@code @Component} 实现类。
 */
@Component
public class AsyncTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskRegistry.class);

    private final Map<String, AsyncTaskHandler> handlers;

    /**
     * 用 {@link ObjectProvider} 而不是直接注 {@code List<AsyncTaskHandler>}：
     * 一个 handler 都没有时，后者会让 Spring 判定依赖不满足、整个应用起不来。
     */
    public AsyncTaskRegistry(ObjectProvider<AsyncTaskHandler> handlerProvider) {
        Map<String, AsyncTaskHandler> map = new LinkedHashMap<>();
        for (AsyncTaskHandler handler : handlerProvider) {
            AsyncTaskHandler exists = map.put(handler.type(), handler);
            if (exists != null) {
                // 启动即失败，而不是运行期随机挑一个。type 撞车时被挑中的那个是随机的，
                // 表现为「同一个按钮有时跑归档同步、有时跑散漫扫描」，这种现象极难查
                throw new IllegalStateException("异步任务类型重复：" + handler.type()
                        + "，冲突的实现类 " + exists.getClass().getName()
                        + " 与 " + handler.getClass().getName());
            }
        }
        this.handlers = map;
        log.info("异步任务 handler 注册完成，共 {} 类：{}", map.size(), map.keySet());
    }

    /** 取 handler，没有就抛出给人看的原因（历史任务的 type 已经被删掉时会走到这里） */
    public AsyncTaskHandler require(String type) {
        AsyncTaskHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("没有注册这类任务：" + type
                    + "。它可能是旧版本留下的任务记录，当前代码里已经没有对应实现了");
        }
        return handler;
    }

    /** 全部已注册类型，按模块 + 类型排序。给任务页的筛选下拉用 */
    public List<AsyncTaskHandler> all() {
        return handlers.values().stream()
                .sorted(Comparator.comparing(AsyncTaskHandler::module)
                        .thenComparing(AsyncTaskHandler::type))
                .toList();
    }
}
