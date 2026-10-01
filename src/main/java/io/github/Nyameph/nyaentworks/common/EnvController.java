package io.github.Nyameph.nyaentworks.common;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 环境自检接口，供左下角常驻的红点使用。
 */
@Tag(name = "环境自检")
@RestController
@RequestMapping("/api/env")
@RequiredArgsConstructor
public class EnvController {

    private final EnvCheckService envCheckService;

    @Operation(summary = "检查 MySQL、建表、归档目录、NConvert、词典是否就绪")
    @GetMapping("/check")
    public ApiResult<EnvCheckService.EnvReport> check() {
        return ApiResult.ok(envCheckService.check());
    }
}
