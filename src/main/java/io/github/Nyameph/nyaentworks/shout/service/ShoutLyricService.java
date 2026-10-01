package io.github.Nyameph.nyaentworks.shout.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps;
import io.github.Nyameph.nyaentworks.common.lyric.LyricTextReader;
import io.github.Nyameph.nyaentworks.shout.service.ShoutGroupService.ShoutGroup;

/**
 * 喊麦组内的歌词读取。
 *
 * <p>编码嗅探与解析本身在 {@link LyricTextReader}（歌曲线、喊麦、原曲模板三处共用），
 * 这里只负责<b>路径校验</b>：入参是组内的歌词<b>文件名</b>，必须是扫出来的那几个之一，
 * 再拼出完整路径交给 {@code LyricTextReader}。直接拿入参 {@code resolve} 就等于让调用方
 * 指定任意相对路径（{@code ../../..}）。
 */
@Service
@RequiredArgsConstructor
public class ShoutLyricService {

    private final ShoutGroupService groupService;

    /** 取一组里某个歌词文件；{@code fileName} 为空则取第一个 */
    public LyricTextReader.Lyric read(String partitionName, String mainName, String fileName) {
        ShoutGroup group = groupService.require(partitionName, mainName);
        String name = GroupFileOps.requireLyricFile(group.lyricFiles(), fileName);
        return LyricTextReader.read(group.dir().resolve(name), name);
    }
}
