package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;
import io.github.Nyameph.nyaentworks.song.mapper.RhymeEntryMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量改词性（{@link RhymeService#updateWordClass}）的口径。
 *
 * <p>钉三件事：① 空勾选直接拒（不能悄悄把整表刷成同一个词性）；
 * ② 枚举外的写法拒绝、<b>不落「其他」</b>（查询侧按枚举分块，落进去等于从页面把这一列弄脏）；
 * ③ 留空 = 清空词性，且<b>真的会把 null 写进 SET</b> —— MyBatis-Plus 默认的
 * {@code NOT_NULL} 更新策略会跳过 null 字段（实现说明 §7.28），所以这一条必须用
 * {@code LambdaUpdateWrapper} 显式 set；测试直接盯住 wrapper 里的参数值，防止哪天有人
 * 「顺手」把它改回 {@code updateById}（那种改法的症状是「页面说改了、库里没变」，静默）。
 */
public class RhymeWordClassTest {

    private final RhymeEntryMapper mapper = mock(RhymeEntryMapper.class);
    private final RhymeService service = new RhymeService(mapper);

    /**
     * {@code LambdaUpdateWrapper} 的列名靠 MyBatis-Plus 的 lambda 缓存反查，
     * 那个缓存平时由容器在扫描 mapper 时填。这里不连库，得手工把 {@link RhymeEntry}
     * 的表信息装进去，否则 {@code set(RhymeEntry::getWordClass, ...)} 会抛
     * 「can not find lambda cache for this entity」（与 Spring 环境无关，纯离线跑）。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), RhymeEntry.class);
    }

    @Test
    public void updateWordClass_emptyIdsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateWordClass(new RhymeService.WordClassRequest(List.of(), "名词")));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateWordClass(new RhymeService.WordClassRequest(null, "名词")));
        assertThrows(IllegalArgumentException.class, () -> service.updateWordClass(null));
    }

    @Test
    public void updateWordClass_outOfEnumRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.updateWordClass(
                        new RhymeService.WordClassRequest(List.of(1L), "名词-人")));
        // 报错要把合法枚举列出来，否则人只能猜
        assertTrue(e.getMessage().contains("名词"), e.getMessage());
        assertTrue(e.getMessage().contains("留空"), e.getMessage());
    }

    @Test
    public void updateWordClass_writesTheGivenClass() {
        when(mapper.update(isNull(), any())).thenReturn(3);
        int n = service.updateWordClass(
                new RhymeService.WordClassRequest(List.of(1L, 2L, 3L), " 动词 "));

        assertEquals(3, n);
        LambdaUpdateWrapper<RhymeEntry> wrapper = captureWrapper();
        assertEquals("动词", onlyValue(wrapper)); // 两侧空白已 trim
        assertTrue(wrapper.getSqlSet().contains("word_class"), wrapper.getSqlSet());
    }

    /** 留空 = 清空词性：NULL 必须真的进 SET 参数，否则 @TableField 的 NOT_NULL 策略会把它吞掉 */
    @Test
    public void updateWordClass_blankClearsTheColumn() {
        when(mapper.update(isNull(), any())).thenReturn(1);
        service.updateWordClass(new RhymeService.WordClassRequest(List.of(9L), "  "));

        LambdaUpdateWrapper<RhymeEntry> wrapper = captureWrapper();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(null),
                "清空词性时 SET 里必须带 null：" + wrapper.getParamNameValuePairs());
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<RhymeEntry> captureWrapper() {
        ArgumentCaptor<LambdaUpdateWrapper<RhymeEntry>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<RhymeEntry> wrapper = captor.getValue();
        assertNotNull(wrapper);
        return wrapper;
    }

    /** wrapper 里唯一的那个 SET 参数值（词性） */
    private static Object onlyValue(LambdaUpdateWrapper<RhymeEntry> wrapper) {
        List<Object> values = wrapper.getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof String || v == null).toList();
        assertEquals(1, values.size(), "参数表里只该有词性这一个值：" + values);
        return values.getFirst();
    }
}
