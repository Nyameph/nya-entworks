package io.github.Nyameph.nyaentworks.common.fileop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;
import io.github.Nyameph.nyaentworks.common.fileop.service.FileOpLogService;

import java.time.LocalDateTime;

/**
 * 操作记录（改动流水）的查询与清理。
 *
 * <p><b>刻意不加 {@code @ConditionalOn*}</b>：这份流水跨三个模块，模块全裁掉的构建里
 * 它照样要能看 —— 它不是「可被裁掉的业务模块」，前端侧也不进 {@code /api/modules}。
 */
@Tag(name = "操作记录")
@RestController
@RequestMapping("/api/file-op-log")
@RequiredArgsConstructor
public class FileOpLogController {

    private final FileOpLogService service;

    @Operation(summary = "改动流水分页查询")
    @GetMapping
    public ApiResult<FileOpLogService.LogPage> list(
            @RequestParam(required = false) FileOpModule module,
            @RequestParam(required = false) FileOpType op,
            @RequestParam(required = false) FileOpLevel level,
            @RequestParam(required = false) FileOpSource source,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResult.ok(service.page(module, op, level, source, batchId, keyword,
                from, to, page, size));
    }

    @Operation(summary = "清理 N 天前的记录（下限 7 天，返回删除条数）")
    @PostMapping("/cleanup")
    public ApiResult<Integer> cleanup(@RequestParam(defaultValue = "30") int days) {
        return ApiResult.ok(service.cleanup(days));
    }
}
