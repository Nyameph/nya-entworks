package io.github.Nyameph.nyaentworks.task.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.task.consts.AsyncTaskStatus;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskRegistry;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;

import java.util.List;

/**
 * 异步任务的统一查询入口。三个模块的任务都在这里看。
 * <p>各业务端点只负责<b>提交</b>（返回任务 id），进度与结果一律来这里取。
 */
@Tag(name = "异步任务")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class AsyncTaskController {

    private final AsyncTaskService taskService;
    private final AsyncTaskRegistry registry;

    @Operation(summary = "任务列表，新的在前。可按模块与状态筛选")
    @GetMapping
    public ApiResult<List<AsyncTaskService.TaskView>> list(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) AsyncTaskStatus status) {
        return ApiResult.ok(taskService.list(module, status));
    }

    @Operation(summary = "单个任务的进度与结果。前端提交后轮询这个")
    @GetMapping("/{id}")
    public ApiResult<AsyncTaskService.TaskView> get(@PathVariable Long id) {
        return ApiResult.ok(taskService.get(id));
    }

    @Operation(summary = "重新执行：从头再跑一遍，另起一条任务，原任务的日志保留")
    @PostMapping("/{id}/rerun")
    public ApiResult<Long> rerun(@PathVariable Long id) {
        return ApiResult.ok(taskService.rerun(id));
    }

    @Operation(summary = "删除一条任务记录。运行中/排队中的不能删")
    @DeleteMapping("/{id}")
    public ApiResult<Integer> delete(@PathVariable Long id) {
        return ApiResult.ok(taskService.delete(id));
    }

    @Operation(summary = "批量删除所有已完成（DONE）任务")
    @DeleteMapping("/completed")
    public ApiResult<Integer> deleteCompleted() {
        return ApiResult.ok(taskService.deleteCompleted());
    }

    @Operation(summary = "按模块的排队 / 运行条数")
    @GetMapping("/summary")
    public ApiResult<List<AsyncTaskService.ModuleStat>> summary() {
        return ApiResult.ok(taskService.summary());
    }

    @Operation(summary = "已注册的任务类型，给筛选下拉用")
    @GetMapping("/types")
    public ApiResult<List<TypeItem>> types() {
        return ApiResult.ok(registry.all().stream()
                .map(h -> new TypeItem(h.type(), h.module(), h.taskName()))
                .toList());
    }

    public record TypeItem(String type, String module, String taskName) {
    }
}
