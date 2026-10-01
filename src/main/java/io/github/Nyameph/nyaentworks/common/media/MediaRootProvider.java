package io.github.Nyameph.nyaentworks.common.media;

import java.util.List;

/**
 * 「哪些目录下的媒体可以读」——由各模块自己申报（实现说明 2.11）。
 *
 * <p>{@link MediaStreamService} 的路径校验是「读本机任意文件」的唯一闸门，受管根是它的
 * 一半（另一半是扩展名白名单）。以前这张清单硬编码在 service 里，于是每加一个模块都要去
 * 改那个类，而漏一个根的症状是「这个模块的音频播不了」，不像安全漏洞那样显眼。
 *
 * <p>改成由各模块实现本接口申报自己的根：歌曲、喊麦各实现一个。Spring 会把所有实现注入
 * {@link MediaStreamService}，一个都没有时是空表（此时任何路径都不受管）。
 */
public interface MediaRootProvider {

    /**
     * 本模块受管的根目录（绝对路径）。
     * <p>一个模块往往不止一个根：已归档、待打分、模板、仅原曲各是一个。
     * 允许返回空串（配置没填）—— service 会跳过空值。
     */
    List<String> mediaRoots();
}
