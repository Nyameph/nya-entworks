package io.github.Nyameph.nyaentworks.common.media;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;

/**
 * 媒体流（实现说明 2.11）。歌曲与喊麦共用的一个端点。
 *
 * <p><b>不返回 {@code ApiResult}</b> —— 这是唯一一个不走统一包装的端点，因为
 * {@code <video src>} 要的是原始字节流。失败时 {@code GlobalExceptionHandler} 仍会
 * 把它包成 {@code ApiResult}，浏览器会把那段 JSON 当成解不了的媒体、报
 * {@code MEDIA_ERR_SRC_NOT_SUPPORTED}；所以前端在挂 {@code src} 之前应当先调各自的
 * {@code /play-info} 确认文件在不在，播放失败时提示要参照那一步的结果。
 *
 * <p>返回 {@code ResponseEntity<Resource>}，{@code Range}/206 由 Spring MVC 处理，
 * 这是能拖进度条的前提。别照抄漫画的图片端点（返回 {@code byte[]}）。
 */
@Tag(name = "媒体流")
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaStreamService mediaService;

    @Operation(summary = "流式返回音视频文件，支持 Range。路径必须落在某个模块受管的根下，"
            + "或者带着该路径的临时预览令牌（原曲表单导入前试听）")
    @GetMapping("/stream")
    public ResponseEntity<Resource> stream(@RequestParam String path,
            @RequestParam(required = false) String preview) {
        return mediaService.stream(path, preview);
    }

    public record OpenLocalRequest(String path) {
    }

    @Operation(summary = "用本机播放器打开 —— 显卡解不了 HEVC 时的逃生口。同一套路径校验")
    @PostMapping("/open-local")
    public ApiResult<Void> openLocal(@RequestBody OpenLocalRequest request) {
        mediaService.openInLocalPlayer(request == null ? null : request.path());
        return ApiResult.ok();
    }
}
