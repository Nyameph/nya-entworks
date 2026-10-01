package io.github.Nyameph.nyaentworks.song.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps;
import io.github.Nyameph.nyaentworks.common.lyric.LyricTextReader;
import io.github.Nyameph.nyaentworks.song.service.SongGroupService.SongGroup;

/**
 * 歌曲组内的歌词读取（实现说明 2.10 / 5.8）。
 *
 * <p>编码嗅探与解析本身在 {@link LyricTextReader}（歌曲线、喊麦、原曲模板三处共用），
 * 这里只负责<b>路径校验</b>：入参是组内的歌词<b>文件名</b>，必须是扫出来的那几个之一，
 * 再拼出完整路径交给 {@code LyricTextReader}。直接拿入参 {@code resolve} 就等于让
 * 调用方指定任意相对路径（{@code ../../..}），与图片端点那句「读本机任意文件的口子」同理。
 */
@Service
@RequiredArgsConstructor
public class SongLyricService {

    private final SongGroupService groupService;

    /**
     * 取一组里某个歌词文件。
     *
     * @param fileName 组内的歌词文件名。必须是组里确实有的那个 —— 不是拿它去
     *                 {@code resolve} 任意路径，见 {@link GroupFileOps#requireLyricFile}
     */
    public LyricTextReader.Lyric read(String partitionName, String mainName, String fileName) {
        SongGroup group = groupService.require(partitionName, mainName);
        String name = GroupFileOps.requireLyricFile(group.lyricFiles(), fileName);
        return LyricTextReader.read(group.dir().resolve(name), name);
    }
}
