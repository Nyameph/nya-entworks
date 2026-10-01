package io.github.Nyameph.nyaentworks.common.settings;

import io.github.Nyameph.nyaentworks.common.settings.SettingsCatalog.Spec;
import io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 本机路径与常用开关的覆盖文件（{@code config/nya-entworks.yaml}）的读写。
 *
 * <p><b>它只是个覆盖层</b>，由 {@code application.yaml} 的
 * {@code spring.config.import: optional:file:./config/nya-entworks.yaml} 引入 ——
 * 导入的属性<b>优先于</b>导入者，所以文件里写了的键压过 {@code application.yaml} 的同名键，
 * 没写的键原地不动。于是「恢复出厂」＝把这一项从文件里删掉，而不是往文件里写一个「默认值」。
 *
 * <p><b>文件里只留「偏离出厂值」的项</b>（{@link #toWrite}）：出厂值就是各 {@code *Properties}
 * 的 Java 字段默认值，值等于它的项不往文件里写。于是「文件里有没有这个键」与「这一项改过没有」
 * 是同一件事 —— 页面上的「已改」徽章认的就是它。这条规则同时负责把老版本攒下的存量清掉：
 * 2026-09-25 之前每次保存都把整份快照写下去（每个键都写着、大多一个值没改），
 * 后果是配置页一打开满屏「已改」。清掉这类条目<b>不动任何生效的值</b>。
 *
 * <p><b>为什么不用数据库存</b>：{@code MangaEhLocalDb} 之类在<b>构造函数</b>里就把路径快照走了，
 * 库方案的覆盖动作必须赶在所有 Bean 创建之前发生，脆弱且难验证；属性源方案由 Spring 的启动顺序
 * 天然保证正确。
 *
 * <p><b>写文件的顺序</b>：校验 → 备份旧文件 → 写同目录临时文件 → 原子替换。
 * 任何一步失败，旧文件都完好。生成的文本会先用 Spring 自己的 YAML 解析器（{@link YamlPropertySourceLoader}，
 * 与启动时读它的那套完全一样）解一遍当闸门 —— 解析不过就不落盘，
 * 免得下次启动因为一个缩进错误直接起不来。
 */
@Service
@RequiredArgsConstructor
public class SettingsService {

    /** 备份后缀。单份、覆盖式：留的是「上一次保存前」的状态，够回滚一次手误 */
    private static final String BACKUP_SUFFIX = ".bak";

    private final SettingsCatalog catalog;

    /** 只为解占位符：文件里写着 {@code ${java.io.tmpdir}/x} 时要跟展开后的生效值比 */
    private final Environment environment;

    /** 页面顶部的提示条（缺了什么、哪些功能因此关着），与文件读写无关，另起一个组件算 */
    private final SettingsNotices notices;

    /** 覆盖文件的绝对路径。按<b>工作目录</b>解析 —— 与 {@code static-locations} 同一条规矩 */
    public Path filePath() {
        return Paths.get(SettingsCatalog.DIR_NAME, SettingsCatalog.FILE_NAME)
                .toAbsolutePath().normalize();
    }

    public String filePathText() {
        return filePath().toString();
    }

    /**
     * 密钥文件的绝对路径，只<b>显示</b>给人看（页面底部的密钥卡）。
     * <p>本类<b>从不写它</b>，只在算「重启后会生效的值」时<b>读</b>它 —— 它是那个阶梯的
     * 第二层（见 {@link #restartValue}）。读它不等于把它的内容下发：只按 {@code specs()} 里
     * 那些键去查，密钥卡上那三个键不在 {@code specs()} 里。剩下的三个密钥由
     * {@code SettingsCatalog#secretStatus()} 只读地报到页面上，页面不给编辑入口。
     * 写它要靠人直接编辑那个文件。
     * <p><b>例外</b>：{@code manga.eh-scan.cookie} 2026-09-25 起是普通设置项（口径 a），
     * 由本类写进覆盖文件 —— 所以它<b>不该</b>再留在密钥文件里（同一个键两处都有时
     * 覆盖层赢，改密钥文件那份不生效）。详见 {@code docs/配置页设计.md} §14。
     */
    public String secretFilePath() {
        return secretPath().toString();
    }

    /** 密钥文件的路径。抽成方法是为了测试能指向别处（与 {@link #filePath()} 同一套做法） */
    Path secretPath() {
        return Paths.get(SettingsCatalog.SECRET_FILE).toAbsolutePath().normalize();
    }

    public String backupPathText() {
        return filePath() + BACKUP_SUFFIX;
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 文件里写着的键 → 值（<b>全键</b>，含 {@code nya-entworks.} 前缀）。
     * <p>用 Spring 自己的加载器而不是直接调 SnakeYAML：拿到的键就是启动时真正生效的那些名字，
     * 「文件里写没写这一项」的判定与 Spring 的视图完全一致，不会有第二套解析口径。
     */
    private Map<String, Object> loadFlat() {
        return loadFlat(filePath(), "（YAML 语法错了？可以直接删掉这个文件，回到出厂配置）");
    }

    /**
     * 密钥文件里写着的键 → 值（<b>只读</b>）。{@link #restartValue} 那个阶梯的第二层用它。
     *
     * <p>文件不存在是<b>正常</b>的（新克隆就没有它，那三个密钥空着不影响启动）→ 空表。
     * 但**存在却读不动**要报出来：与覆盖层同一条规矩，静默当成「没有这一层」会让页面
     * 少画一个本来生效着的值，而人只会以为是值丢了。
     */
    private Map<String, Object> loadSecretFlat() {
        return loadFlat(secretPath(), "（YAML 语法错了？这个文件是密钥，别删，改回合法 YAML 即可）");
    }

    private Map<String, Object> loadFlat(Path file, String hint) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load(file.getFileName().toString(), new FileSystemResource(file));
            Map<String, Object> flat = new LinkedHashMap<>();
            for (PropertySource<?> source : sources) {
                if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> en) {
                    for (String name : en.getPropertyNames()) {
                        flat.put(name, en.getProperty(name));
                    }
                }
            }
            return flat;
        } catch (Exception e) {
            // 读不动就报出来。静默当成「没有覆盖」会让用户以为改动生效了
            throw new IllegalStateException("读取 " + file + " 失败：" + e.getMessage() + hint, e);
        }
    }

    /**
     * 某一个键在覆盖文件里的值，未覆盖返回 {@code null}。
     *
     * <p>先过一遍占位符：文件里可以写 {@code ${java.io.tmpdir}/xxx} 这种表达式，
     * Spring 绑定时会把它展开成真实路径。不展开就直接跟「现在生效的值」比，
     * 每一项带占位符的设置都会被判成「改了还没重启」—— 一个没人改过的项上挂着红色徽章，
     * 比不显示更糟。
     */
    private String fileValue(Map<String, Object> flat, String key) {
        String raw = rawFileValue(flat, key);
        if (raw == null) {
            return null;
        }
        try {
            return environment.resolvePlaceholders(raw);
        } catch (RuntimeException e) {
            // 解不动就当字面量（比如用户写了个不像占位符的 ${）。拿去比不出错就行
            return raw;
        }
    }

    /** 文件里那一行的原样文本（占位符<b>不</b>展开）。整份重写时用它保住用户写的表达式 */
    private static String rawFileValue(Map<String, Object> flat, String key) {
        Object v = flat.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 「<b>重启之后</b>这个键会生效的值」—— 页面上输入框画的就是它，也是「待重启」的比对方。
     *
     * <p>按 {@code 覆盖层 > 密钥文件 > 出厂值} 三层叠出来，与 {@code application.yaml} 里那条
     * import 列表同一个顺序 —— 只不过最下面那层是 {@code *Properties} 的 Java 字段默认值
     * （{@code application.yaml} 里有意没有 {@code nya-entworks:} 段，所以出厂值那一层就是它）。
     *
     * <p>为什么不画「现在生效的值」：那个值要重启才会变，而覆盖层是<b>立刻</b>落盘的
     * —— 画它的话，用户刚删掉的一项会立刻跳回来（2026-09-25 报的「删除本地 eh 库后点保存，
     * 删除的内容又恢复了」），而页面再提交一次就把它真的写回文件（静默复活）。
     *
     * <p>为什么密钥文件也算一层：覆盖层里没写、可这一项本来就有值时，那个值只可能是密钥文件
     * 给的 —— e-hentai 的 cookie 还没从密钥文件搬进覆盖层时正是这个状态。画空再加一句
     * 「待重启」会让「先看一眼值在」这一步无从下手（{@code docs/配置页设计.md} §14.1）。
     *
     * @param inOverlay 覆盖层里那一份（没有这一项就 {@code null}）
     * @param inSecret  密钥文件里那一份（同上）
     */
    private String restartValue(Spec spec, String inOverlay, String inSecret) {
        if (inOverlay != null) {
            return inOverlay;
        }
        if (inSecret != null) {
            return inSecret;
        }
        return StringUtils.defaultString(spec.factoryValue());
    }

    public View view() {
        Map<String, Object> flat = loadFlat();
        Map<String, Object> secretFlat = loadSecretFlat();
        Map<String, List<Field>> byGroup = new LinkedHashMap<>();
        Map<String, String> groupModule = new LinkedHashMap<>();
        Map<String, Field> toggles = new LinkedHashMap<>();
        // 提示条（SettingsNotices）判的是「重启后会生效的值」，所以要把它连同「哪些还没生效」
        // 一起递过去 —— 判据在那边只有一份，这里只负责算（见 SettingsNotices 类注释）
        Map<String, String> effectiveByKey = new LinkedHashMap<>();
        Set<String> staleKeys = new LinkedHashSet<>();
        int pendingCount = 0;
        for (Spec spec : catalog.specs()) {
            String current = StringUtils.defaultString(spec.current());
            String inFile = fileValue(flat, spec.key());
            // 「已改」＝ 覆盖文件里写着这一项，<b>而且值偏离了出厂值</b>。
            // 保存时也是这个口径（值等于出厂值的条目会被去掉，见 {@link #toWrite}），
            // 两处对齐 —— 于是老版本留下的「整份快照」（每个键都写着、值却一个没改）
            // 不必先保存一次才把满屏的「已改」清掉。
            boolean overridden = inFile != null
                    && !sameValue(spec.type(), inFile, spec.factoryValue());
            // 「重启后会生效的值」：覆盖层 > 密钥文件 > 出厂值（见 #restartValue）
            String effective = restartValue(spec, inFile, fileValue(secretFlat, spec.key()));
            // 「待重启」＝ 重启后生效值会变，也就是「画在输入框里的那个值 ≠ 现在生效的值」。
            // Properties Bean 是启动时绑定的，运行中改文件对当前进程一个值都不生效。
            // 覆盖层里**没有**这一项时也要判：那说明现在生效的这个值是「进程启动时那份覆盖层」
            // 或者密钥文件给的，重启后才会变成文件里说的那样 —— 旧口径（只认「文件里那一份」）
            // 漏掉的正是这一半：删掉一项后 pending 为 false，页面上既不说「待重启」，
            // 又照着旧值把它画回来（2026-09-25 报的第一个问题）。
            boolean pending = !sameValue(spec.type(), effective, current);
            effectiveByKey.put(spec.key(), StringUtils.defaultString(effective));
            if (pending) {
                pendingCount++;
                staleKeys.add(spec.key());
            }
            // 输入框里画「重启后会生效的那一份」，不是「现在生效的那一份」：
            // 「什么时候轮到哪一层」要重启才算数，画生效值会让刚删掉/刚填的项立刻跳回去
            // （2026-09-25 报的第二个问题），而再保存一次就把那个值写进文件 —— 真的丢 / 真的复活。
            String shown = effective;
            Field field = new Field(spec.key(), spec.label(), spec.desc(), spec.type(),
                    spec.min(), spec.max(), spec.options(), shown,
                    overridden, pending, spec.required(), spec.relativeAllowed(),
                    spec.restartRequired(), spec.onMissing(), spec.module(),
                    spec.moduleSwitch(), spec.dependsOn());
            if (spec.moduleSwitch()) {
                // 模块总开关不进卡片 —— 前端拿它画大标题那一行。顺序就是 specs() 里的顺序
                toggles.put(spec.module(), field);
            } else {
                byGroup.computeIfAbsent(spec.group(), k -> new ArrayList<>()).add(field);
                groupModule.putIfAbsent(spec.group(), spec.module());
            }
        }
        List<ModuleView> modules = new ArrayList<>();
        toggles.forEach((id, toggle) ->
                modules.add(new ModuleView(id, toggle.label(), toggle.desc(), toggle)));
        List<Group> groups = new ArrayList<>();
        byGroup.forEach((title, fields) ->
                groups.add(new Group(title, groupModule.get(title), fields)));
        Path file = filePath();
        return new View(file.toString(), Files.isRegularFile(file),
                file + BACKUP_SUFFIX, Files.isRegularFile(Paths.get(file + BACKUP_SUFFIX)),
                modules, groups, pendingCount, notices.notices(effectiveByKey, staleKeys),
                catalog.secretStatus(),
                secretFilePath());
    }

    /** 比「值一样吗」时按类型归一：布尔写 True、数字写 1 都不该被判成「改了」 */
    private static boolean sameValue(String type, String a, String b) {
        if (a == null || b == null) {
            return Objects.equals(a, b);
        }
        if (SettingsCatalog.INT.equals(type)) {
            try {
                return Long.parseLong(a.trim()) == Long.parseLong(b.trim());
            } catch (NumberFormatException e) {
                return a.equals(b);
            }
        }
        if (SettingsCatalog.BOOL.equals(type)) {
            return Boolean.parseBoolean(a.trim()) == Boolean.parseBoolean(b.trim());
        }
        return a.equals(b);
    }

    // ------------------------------------------------------------------
    // 预检与保存
    // ------------------------------------------------------------------

    /**
     * 预演：算出「保存后文件里会有哪些项、与现在差在哪」，并跑一遍校验。
     * <p>与保存共用同一套计算 —— 预演说会报错，保存就一定会报错，不会出现「预演通过、保存失败」。
     *
     * @param values 键 → 提交值。值为 {@code null} 表示「把这一项从文件里删掉」（恢复出厂）
     */
    public Plan plan(Map<String, String> values) {
        Map<String, Object> flat = loadFlat();
        List<Change> changes = new ArrayList<>();
        for (Spec spec : catalog.specs()) {
            if (!values.containsKey(spec.key())) {
                continue;
            }
            String from = fileValue(flat, spec.key());
            String submitted = values.get(spec.key());
            String to = toWrite(spec, submitted, flat);
            if (to == null) {
                if (from != null) {
                    // submitted == null → 用户点的「恢复出厂」；否则是「值就等于出厂值，不占文件里一行」。
                    // 后者只是把老版本整份重写攒下的空行清掉，生效值一个都不变，
                    // 所以前端把它单独摆一句、不混进「会改动哪几项」的表里
                    changes.add(new Change(spec.key(), spec.label(), from, null, submitted != null));
                }
                continue;
            }
            if (from == null || !sameValue(spec.type(), from, submitted)) {
                changes.add(new Change(spec.key(), spec.label(), from,
                        submitted == null ? null : submitted.trim(), false));
            }
        }
        return new Plan(changes, validate(values, flat), warnings(values, flat));
    }

    private List<Message> validate(Map<String, String> values, Map<String, Object> flat) {
        List<Message> errors = new ArrayList<>();
        for (Spec spec : catalog.specs()) {
            // 只校验「本次提交里有这一项」的。文件里本来就有、这次没动的项不重复报 ——
            // 它已经在生效了，说明启动时它是合法的
            if (!values.containsKey(spec.key())) {
                continue;
            }
            String v = values.get(spec.key());
            if (v == null) {
                continue;
            }
            v = v.trim();
            if (v.isEmpty()) {
                if (spec.required()) {
                    errors.add(new Message(spec.key(), spec.label(), "不能留空"));
                }
                continue;
            }
            switch (spec.type()) {
                case SettingsCatalog.INT -> {
                    long n;
                    try {
                        n = Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        errors.add(new Message(spec.key(), spec.label(), "要填整数，现在是「" + v + "」"));
                        continue;
                    }
                    if (spec.options() != null && !spec.options().isEmpty()
                            && !spec.options().contains(v)) {
                        errors.add(new Message(spec.key(), spec.label(),
                                "只能填 " + String.join(" / ", spec.options())));
                    } else if (spec.min() != null && n < spec.min()) {
                        errors.add(new Message(spec.key(), spec.label(), "不能小于 " + spec.min()));
                    } else if (spec.max() != null && n > spec.max()) {
                        errors.add(new Message(spec.key(), spec.label(), "不能大于 " + spec.max()));
                    }
                }
                case SettingsCatalog.BOOL -> {
                    if (!"true".equals(v) && !"false".equals(v)) {
                        errors.add(new Message(spec.key(), spec.label(), "只能填 true 或 false"));
                    }
                }
                case SettingsCatalog.PATH_DIR, SettingsCatalog.PATH_FILE -> {
                    if (!spec.relativeAllowed() && !Paths.get(v).isAbsolute()) {
                        errors.add(new Message(spec.key(), spec.label(),
                                "要填绝对路径（带盘符，如 F:\\...）"));
                    }
                }
                default -> {
                    // TEXT：非空已在上面的 required 判过，没有别的规则
                }
            }
        }
        return errors;
    }

    /**
     * 提醒（不拦保存）。判据是「改完之后会怎样」，所以拿提交值算；
     * 提交值参与不了这些检查的项（布尔、数字）自然一条都不报。
     */
    private List<Message> warnings(Map<String, String> values, Map<String, Object> flat) {
        List<Message> warnings = new ArrayList<>();
        for (Spec spec : catalog.specs()) {
            String v = values.containsKey(spec.key())
                    ? values.get(spec.key()) : fileValue(flat, spec.key());
            if (StringUtils.isBlank(v)) {
                continue;
            }
            // 界面上的「改完会怎样」比「现在怎样」有用，但只对本次提交的项说 ——
            // 没动的项已经在生效，重复提醒只是噪音
            if (!values.containsKey(spec.key())) {
                continue;
            }
            v = v.trim();
            if (SettingsCatalog.PATH_DIR.equals(spec.type())) {
                if (!new File(v).isDirectory()) {
                    warnings.add(new Message(spec.key(), spec.label(),
                            "目录不在磁盘上。" + StringUtils.defaultString(spec.onMissing(),
                                    "相关页面会是空的")));
                } else if ("manga.archive-dir".equals(shortKey(spec.key()))) {
                    warnings.addAll(checkArchivePartitions(v, spec));
                }
            } else if (SettingsCatalog.PATH_FILE.equals(spec.type()) && !new File(v).isFile()) {
                warnings.add(new Message(spec.key(), spec.label(),
                        "文件不在磁盘上。" + StringUtils.defaultString(spec.onMissing(),
                                "相关功能不可用")));
            }
        }
        return warnings;
    }

    /** 归档根下的四个评分分区要齐全，缺一档同步会整个拒跑（见 {@code MangaArchiveService.sync}） */
    private List<Message> checkArchivePartitions(String root, Spec spec) {
        List<Message> warnings = new ArrayList<>();
        Set<Integer> present = MangaScoreDir.scoreDirs(root).keySet();
        List<String> missing = new ArrayList<>();
        for (int score : MangaScoreDir.SCORES) {
            if (!present.contains(score)) {
                missing.add(String.valueOf(score));
            }
        }
        if (!missing.isEmpty()) {
            warnings.add(new Message(spec.key(), spec.label(),
                    "缺评分分区：" + String.join("、", missing)
                            + " 分。分区目录形如 9-百读不厌，手工在归档根下建好 —— 缺一档同步会整个拒跑"));
        }
        return warnings;
    }

    private static String shortKey(String key) {
        return key.startsWith(SettingsCatalog.PREFIX)
                ? key.substring(SettingsCatalog.PREFIX.length()) : key;
    }

    /**
     * 保存。先生成文本并过闸门，再备份、原子替换。
     *
     * @return 改了几项
     */
    public SaveResult save(Map<String, String> values) {
        Plan plan = plan(values);
        if (!plan.errors().isEmpty()) {
            throw new IllegalArgumentException("有 " + plan.errors().size() + " 项填得不对："
                    + plan.errors().get(0).label() + " " + plan.errors().get(0).text());
        }
        Map<String, Object> flat = loadFlat();
        Rendered rendered = render(values, flat);
        gate(rendered);
        String text = rendered.text();
        Path file = filePath();
        try {
            Files.createDirectories(file.getParent());
            // 备份放在生成文本之后：连自己都生不出合法文本的调用不该动旧文件
            if (Files.isRegularFile(file)) {
                Files.copy(file, Paths.get(file + BACKUP_SUFFIX),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // 极少数文件系统不支持原子替换，退一步也要落盘
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写 " + file + " 失败：" + e.getMessage(), e);
        }
        // 「改动了几项」与「顺手清掉几项与出厂值相同的」分开数：后者一个生效值都没动，
        // 混在一起报「已保存 36 项」只会让人以为出了什么大事
        int changed = 0;
        int cleaned = 0;
        for (Change change : plan.changes()) {
            if (change.cleanup()) {
                cleaned++;
            } else {
                changed++;
            }
        }
        return new SaveResult(changed, cleaned, file.toString(),
                file + BACKUP_SUFFIX, pendingCountAfter(values, flat));
    }

    /**
     * 语法闸门：把刚生成的文本丢回 Spring 自己那套 YAML 解析器读一遍，
     * 并核对<b>解析出来的键与打算写出去的键完全一致</b>。
     *
     * <p>写坏配置的代价不是「这次保存失败」，而是<b>下次启动直接起不来</b> ——
     * 那时人已在别处，只看得到一条 BindException。所以宁可在这里拦下、不落盘。
     * 用 {@link YamlPropertySourceLoader} 而不是直接调 SnakeYAML：与启动时读它的那套完全一样，
     * 闸门认了就等于启动时也认。
     *
     * <p>比对键集合是为了抓住「生成器写漏了一段」这类错 —— 光看能不能解析，
     * 少写一半内容照样解析得过。
     */
    private void gate(Rendered rendered) {
        Map<String, Object> parsed;
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("generated",
                    new ByteArrayResource(rendered.text().getBytes(StandardCharsets.UTF_8)));
            parsed = new LinkedHashMap<>();
            for (PropertySource<?> source : sources) {
                if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> en) {
                    for (String name : en.getPropertyNames()) {
                        parsed.put(name, en.getProperty(name));
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("生成的配置不是合法 YAML，已放弃保存（旧文件未动）："
                    + e.getMessage(), e);
        }
        if (!new LinkedHashSet<>(parsed.keySet())
                .equals(new LinkedHashSet<>(rendered.keys()))) {
            throw new IllegalStateException("生成的配置读回来的键与打算写的不一致"
                    + "（写 " + rendered.keys() + "，读到 " + parsed.keySet()
                    + "），已放弃保存（旧文件未动）—— 这是生成器的 bug，请报出来");
        }
    }

    /**
     * 保存后有几项**重启后生效值会变**（供前端提示；与 {@link #view()} 的 pending 同一口径）。
     *
     * <p>按保存**之后**的覆盖层算：写下去的项就是提交值，没写下去的项要往下找一层
     * （密钥文件，再没有就是出厂值）。「恢复出厂」的项因此也算得进来 ——
     * 它正是最典型的「一项改动还没生效」：文件里删掉了，可进程里那个值还在。
     */
    private int pendingCountAfter(Map<String, String> values, Map<String, Object> flat) {
        Map<String, Object> secretFlat = loadSecretFlat();
        int n = 0;
        for (Spec spec : catalog.specs()) {
            if (!values.containsKey(spec.key())) {
                continue;
            }
            String submitted = values.get(spec.key());
            // toWrite 非 null ＝ 这一项会写进文件，写下去的就是提交值（页面提交的是展开后的值，
            // 文件里那种 ${…} 表达式早被页面画成展开值了）；null ＝ 文件里没有这一项。
            // 提交 null（恢复出厂）时 toWrite 必然 null，所以下面那个 trim 不会再碰上 null
            String inFile = toWrite(spec, submitted, flat) == null ? null : submitted.trim();
            String effective = restartValue(spec, inFile, fileValue(secretFlat, spec.key()));
            if (!sameValue(spec.type(), effective, StringUtils.defaultString(spec.current()))) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 生成 YAML 文本
    // ------------------------------------------------------------------

    /**
     * 这一项保存后写进文件里的文本；{@code null} ＝ <b>不写这一项</b>。
     *
     * <p>{@code null} 有两种来路，落到文件上是同一件事（键不在文件里，值回到出厂值那一层）：
     * <ul>
     *   <li>提交的是 {@code null} —— 页面上点了「恢复出厂」；</li>
     *   <li>提交的值<b>等于出厂值</b> —— 不在文件里白占一行。于是「文件里有没有这个键」
     *       与「这一项改过没有」才是一回事，页面上的「已改」也就重新有信息量。
     *       老版本每次保存都把整份快照写下去（每个键都写着、大多一个值没改），
     *       攒出的那些条目会在下一次保存时被这条规则顺手清掉 —— 生效值一个都不动：
     *       键不在文件里时生效的正是出厂值，而它本来就和出厂值一样。</li>
     * </ul>
     *
     * <p><b>模块总开关是唯一的例外</b>：它的<b>键在不在</b>就是「这个构建里有没有这个模块」
     * （{@code SettingsCatalog#moduleExists}），所以哪怕值等于出厂值（{@code false}）
     * 也必须写下去 —— 否则「把模块关掉」在下次保存时会变成「把这个模块删掉」，
     * 连那个关着的开关一起从页面上消失（页面上唯一的自救入口）。
     */
    private String toWrite(Spec spec, String submitted, Map<String, Object> flat) {
        if (submitted == null) {
            return null;
        }
        String v = submitted.isEmpty() ? "" : submitted.trim();
        if (!spec.moduleSwitch() && sameValue(spec.type(), v, spec.factoryValue())) {
            return null;
        }
        // 这一项没改（提交值与文件里的等价）就照抄文件里那一行的原样文本 ——
        // 文件里可能写着 ${java.io.tmpdir}/xxx 这种表达式，页面显示的是展开后的路径，
        // 原样写回去等于把表达式换成了这一台机器此刻的字面量
        String raw = rawFileValue(flat, spec.key());
        if (raw != null && sameValue(spec.type(), fileValue(flat, spec.key()), v)) {
            return raw;
        }
        return v;
    }

    /**
     * 由「原文件 + 本次提交」合成新文件。
     *
     * <p>文件里<b>没有</b>列进 {@link SettingsCatalog} 的键（用户手写的调参项）原样保留 ——
     * 整份重写不该顺手抹掉页面管不着的东西。
     *
     * <p>标量一律用单引号：YAML 单引号里除 {@code ''} 外没有转义，
     * {@code F:\NetdiskDownload\#待看} 的反斜杠与 {@code #} 都不必特殊照顾。
     * 数字与布尔裸写 —— 写成 {@code '7890'} 虽然也能绑上，但读文件的人会以为它是个字符串。
     */
    private Rendered render(Map<String, String> values, Map<String, Object> flat) {
        // 全键 → 目录项。既决定标量怎么渲染，也提供上面那几行说明
        Map<String, Spec> byKey = new LinkedHashMap<>();
        for (Spec spec : catalog.specs()) {
            byKey.put(spec.key(), spec);
        }

        Map<String, Object> tree = new LinkedHashMap<>();
        // 先放已知项（顺序 = 页面顺序），再放用户手写的其它键，顺序都稳定，diff 才好看
        for (Spec spec : catalog.specs()) {
            if (!values.containsKey(spec.key())) {
                continue;
            }
            String v = toWrite(spec, values.get(spec.key()), flat);
            if (v == null) {
                continue;   // 恢复出厂，或者值本来就等于出厂值：不写进文件
            }
            put(tree, shortKey(spec.key()), v);
        }
        for (Map.Entry<String, Object> e : flat.entrySet()) {
            String shortK = shortKey(e.getKey());
            if (!e.getKey().startsWith(SettingsCatalog.PREFIX) || byKey.containsKey(e.getKey())
                    || occupied(tree, shortK)) {
                continue;
            }
            put(tree, shortK, e.getValue());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 「配置」页（左下角齿轮）生成的文件：**只有偏离出厂值的项才在这里**。\n");
        sb.append("# 于是「文件里有没有这个键」就等于「这一项改过没有」—— 页面上的「已改」认的就是它。\n");
        sb.append("# 下面每一项上面的说明也是页面自动写的 —— 想改说明去改 SettingsCatalog，手改这里会丢。\n");
        sb.append("#\n");
        sb.append("# 这只是**覆盖层**：写了的键压过下一层的同名键，没写的键原地不动。\n");
        sb.append("# 值的来路（从低到高）：各 *Properties 的 Java 字段默认值 → application.yaml →\n");
        sb.append("# " + SettingsCatalog.SECRET_FILE + " → 这个文件（后者压前者）。\n");
        sb.append("# 所以「恢复出厂」＝把那一项从这里删掉（页面上有按钮），值便落回下一层 —— 一般就是出厂值。\n");
        sb.append("# 文件整个删掉也合法（application.yaml 里那行 import 是 optional 的），等于全部恢复出厂。\n");
        sb.append("#\n");
        sb.append("# 例外：三个模块总开关一律写在这里，哪怕值就是出厂的 false ——\n");
        sb.append("# 它们的**键在不在**就是「这个构建里有没有这个模块」（打包裁剪的介质就是这份文件）。\n");
        sb.append("#\n");
        sb.append("# 改完要重启后端才生效 —— 这些值在启动时就绑定好了；\n");
        sb.append("# 配置页的「保存并重启后端」会代劳（只在桌面壳里出现）。\n");
        sb.append("#\n");
        sb.append("# 密钥里只有 manga.eh-scan.cookie 在这个文件里（它必须与站点根成对换，"
                + "2026-09-25 起收进页面）；\n");
        sb.append("# 网易云 cookie 与两个在线 AI 密钥仍不在这个文件里，在 "
                + SettingsCatalog.SECRET_FILE + "。\n");
        sb.append("# 页面管不到的键（你自己加的调参项）原样保留。\n");
        List<String> keys = new ArrayList<>();
        if (tree.isEmpty()) {
            // 一个覆盖项都没有：只留上面那段说明。写个空的 nya-entworks: 也合法，
            // 但读起来像「写坏了」，不如干脆不写
            sb.append("#\n# 现在没有任何覆盖项 —— 每一项都用的出厂值（各 *Properties 的 Java 字段默认值）。\n");
            return new Rendered(sb.toString(), keys);
        }
        sb.append('\n').append(SettingsCatalog.PREFIX, 0, SettingsCatalog.PREFIX.length() - 1).append(":\n");
        writeNode(sb, tree, 1, byKey, SettingsCatalog.PREFIX, keys);
        return new Rendered(sb.toString(), keys);
    }

    private void writeNode(StringBuilder sb, Map<String, Object> node, int depth,
                           Map<String, Spec> byKey, String keyPrefix, List<String> keys) {
        String indent = "  ".repeat(depth);
        for (Map.Entry<String, Object> e : node.entrySet()) {
            String key = keyPrefix + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> child) {
                // 中间节点本身不是设置项（页面上是分组），没有说明可写
                sb.append(indent).append(e.getKey()).append(":\n");
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) child;
                writeNode(sb, typed, depth + 1, byKey, key + ".", keys);
            } else {
                Spec spec = byKey.get(key);
                if (spec != null) {
                    writeComment(sb, indent, spec);
                }
                sb.append(indent).append(e.getKey()).append(": ")
                        .append(scalar(v, spec == null ? null : spec.type())).append('\n');
                keys.add(key);
            }
        }
    }

    /**
     * 一项上面那几行说明。内容是页面上的同一份（标签 + 说明 + 缺了会怎样），
     * 所以文件在「页面打不开」的时候（后端起不来）自己就能读懂 ——
     * 那正是最需要读它的时候。
     */
    private static void writeComment(StringBuilder sb, String indent, Spec spec) {
        sb.append(indent).append("# ").append(spec.label()).append('\n');
        wrap(sb, indent, spec.desc());
        if (spec.onMissing() != null) {
            wrap(sb, indent, "缺了会怎样：" + spec.onMissing());
        }
        if (spec.restartRequired()) {
            wrap(sb, indent, "改完必须重启后端 —— 这个值在启动时就被读走固化了");
        }
    }

    /** 按显示宽度（中文算 2 列）折行成 {@code # } 注释，纯粹为了对齐好读 */
    private static void wrap(StringBuilder sb, String indent, String para) {
        if (para == null || para.isEmpty()) {
            return;
        }
        StringBuilder line = new StringBuilder();
        int width = 0;
        for (int i = 0; i < para.length(); i++) {
            char c = para.charAt(i);
            int w = c >= 0x2014 ? 2 : 1;
            if (width + w > 76) {
                sb.append(indent).append("# ").append(line).append('\n');
                line.setLength(0);
                width = 0;
            }
            line.append(c);
            width += w;
        }
        if (line.length() > 0) {
            sb.append(indent).append("# ").append(line).append('\n');
        }
    }

    /** 已知项按目录里声明的类型渲染，用户手写的按键值本身的类型渲染 */
    private static String scalar(Object value, String type) {
        if (SettingsCatalog.INT.equals(type)) {
            return String.valueOf(value);
        }
        if (SettingsCatalog.BOOL.equals(type)) {
            return String.valueOf(value);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Collection<?> c) {
            return "[" + c.stream()
                    .map(item -> quote(String.valueOf(item)))
                    .collect(java.util.stream.Collectors.joining(", ")) + "]";
        }
        return quote(value.toString());
    }

    /**
     * 单引号包裹。YAML 单引号里除 {@code ''} 外没有转义 ——
     * {@code F:\NetdiskDownload\#待看} 的反斜杠与井号都不必特殊照顾，这是选单引号而不是双引号的原因。
     */
    private static String quote(String v) {
        return "'" + v.replace("'", "''") + "'";
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> tree, String shortKey, Object value) {
        String[] parts = shortKey.split("\\.");
        Map<String, Object> node = tree;
        for (int i = 0; i < parts.length - 1; i++) {
            node = (Map<String, Object>) node.computeIfAbsent(parts[i], k -> new LinkedHashMap<>());
        }
        node.put(parts[parts.length - 1], value);
    }

    /**
     * 这条键的路径上是否已被占住 —— 包括「整条键已经在了」和
     * 「路径中间那一段已被写成了一个标量」（比如用户手写了 {@code manga.eh-scan: 全部}，
     * 而目录里 {@code manga.eh-scan.ai-model} 要往它下面挂）。
     * <p>后者不能硬塞：塞进去要么覆盖掉手写的那一行，要么在渲染时把标量当 Map 走、
     * 报一个看不懂的 ClassCastException。跳过更合适 —— 用户手写了一个和页面结构冲突的东西，
     * 让页面的那份赢。
     */
    private static boolean occupied(Map<String, Object> tree, String shortKey) {
        String[] parts = shortKey.split("\\.");
        Map<String, Object> node = tree;
        for (String part : parts) {
            Object child = node.get(part);
            if (child instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) m;
                node = typed;
                continue;
            }
            return child != null || node.containsKey(part);
        }
        return true;
    }

    /**
     * 生成结果。
     *
     * @param keys 这份文本里应当出现的全键。{@link #gate} 拿它跟「解析回来读到的键」对账，
     *             抓「生成器写漏了一段」这类错
     */
    private record Rendered(String text, List<String> keys) {
    }

    // ------------------------------------------------------------------
    // 对外的数据结构（前端只画不判，判定与元数据都在这里算好）
    // ------------------------------------------------------------------

    /**
     * @param notices 页面顶部的提示条（{@link SettingsNotices}）——
     *                「按配置页上这份配置启动，因为缺了什么所以哪些功能关着」。
     *                判的是<b>重启后会生效的值</b>（与输入框同一个阶梯），所以保存完立刻就有说法、
     *                不用重启；「这一项还没生效」由 {@link SettingsNotices} 自己补一句
     * @param pendingCount 有几项改了还没重启（＝{@code Field#pending} 的条数）
     * @param secrets        密钥的状态（只看不改）—— 见 {@link SettingsCatalog#secretStatus()}。
     *                       与 {@code groups} 里的字段是两条互不重叠的路：那一条可改、写本页的文件，
     *                       这一条只读、指向 {@link SettingsCatalog#SECRET_FILE}
     * @param secretFilePath 密钥文件的位置，页面上直接显示给人看（前端不硬编码路径）
     */
    public record View(String filePath, boolean fileExists, String backupPath, boolean backupExists,
                       List<ModuleView> modules, List<Group> groups, int pendingCount,
                       List<SettingsNotices.Notice> notices,
                       List<SettingsCatalog.SecretStatus> secrets, String secretFilePath) {
    }

    /**
     * 一个模块的大标题 + 它那一行的总开关。
     *
     * @param id     模块 id（{@code manga} / {@code song} / {@code shout}），
     *               与 {@code static/js/modules.js} 里注册项的 id 同值 —— 前端拿它对上导航
     * @param title  大标题（漫画 / 歌曲 / 喊麦）
     * @param desc   标题下的一句话说明
     * @param toggle 总开关那一项。<b>它的 {@code key} 就是保存时要回传的那个键</b> ——
     *               开关不随卡片走，但仍是一个正经的设置项，提交与落盘都走同一条路
     */
    public record ModuleView(String id, String title, String desc, Field toggle) {
    }

    /**
     * 一张卡片。
     *
     * @param module 本组所属模块，{@code null} 表示通用组（「AI 端点（本机）」这种跨模块的）。
     *               组名本身就是按模块分的（「歌曲 · 基础」），所以从组里第一项推出来即可 ——
     *               不另存一份，少一个能写歪的地方
     */
    public record Group(String title, String module, List<Field> fields) {
    }

    /**
     * 一项。
     *
     * @param value     画在输入框里的值：覆盖文件里写了这一项就画文件里那一份，否则画现在生效的值。
     *                  <b>不是</b>「现在生效的值」—— 生效值要重启才变，画它的话用户刚填的路径
     *                  一保存就从页面上跳回旧的（2026-09-25 报的第二个问题）。
     *                  两者的差别只在「写了还没重启」那一档，那时 {@code pending} 会说明
     * @param overridden 覆盖文件里写了这一项、且值偏离了出厂值（＝用户改过）。
     *                  等于出厂值的条目在保存时会被清掉（{@link #toWrite}），所以两者迟早一致，
     *                  这里提前对齐 —— 老版本攒下的整份快照不必先保存一次才不显示「已改」
     * @param pending   覆盖文件里的值与现在生效的值不同，要重启后端才生效
     * @param module    所属模块；通用项为 {@code null}
     * @param moduleSwitch 是不是模块总开关 —— 前端据此把它从卡片里提到大标题那一行
     * @param dependsOn 依赖的键；它<b>当前</b>为 {@code false} 时本行在页面上隐藏
     */
    public record Field(String key, String label, String desc, String type,
                        Integer min, Integer max, List<String> options, String value,
                        boolean overridden, boolean pending, boolean required,
                        boolean relativeAllowed, boolean restartRequired, String onMissing,
                        String module, boolean moduleSwitch, String dependsOn) {
    }

    /**
     * 预演结果。<b>预演与保存走同一套计算</b>，所以它说没问题、保存就不会失败。
     *
     * @param changes 文件里会变动的行，{@code to} 为 {@code null} 表示这一行会被删掉
     */
    public record Plan(List<Change> changes, List<Message> errors, List<Message> warnings) {
    }

    /**
     * 文件里某一行的变动。
     *
     * @param from    现在文件里写的值；{@code null} 表示这一项现在不在文件里
     * @param to      保存后写进去的值；{@code null} 表示这一行会被删掉
     * @param cleanup 这一行被删只是因为<b>值本来就等于出厂值</b>（老版本整份重写攒下的），
     *                不是用户点的「恢复出厂」。<b>生效值一个都不变</b>，
     *                所以前端把它单独摆一句，不混进「会改动哪几项」的表里
     */
    public record Change(String key, String label, String from, String to, boolean cleanup) {
    }

    public record Message(String key, String label, String text) {
    }

    /**
     * @param changed 真正改动的项数（不含 {@link Change#cleanup()} 那些）
     * @param cleaned 顺手清掉的项数（与出厂值相同的旧条目，生效值一个没动）
     */
    public record SaveResult(int changed, int cleaned, String filePath, String backupPath,
                             int pendingCount) {
    }
}
