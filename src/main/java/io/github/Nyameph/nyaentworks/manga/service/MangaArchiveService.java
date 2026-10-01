package io.github.Nyameph.nyaentworks.manga.service;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.Nyameph.nyaentworks.manga.consts.MangaArchiveNameSource;
import io.github.Nyameph.nyaentworks.manga.consts.MangaArchiveNameType;
import io.github.Nyameph.nyaentworks.manga.consts.MangaArchiveUnitStatus;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDataFileType;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDataStatus;
import io.github.Nyameph.nyaentworks.manga.consts.MangaScoreSource;
import io.github.Nyameph.nyaentworks.manga.consts.MangaTagTargetType;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.entity.MangaArchiveName;
import io.github.Nyameph.nyaentworks.manga.entity.MangaArchiveUnit;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaArchiveNameMapper;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaArchiveUnitMapper;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaDataMapper;
import io.github.Nyameph.nyaentworks.manga.util.MangaCbzUtil;
import io.github.Nyameph.nyaentworks.manga.util.MangaFolderName;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser.ArchiveFolderInfo;
import io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir;
import io.github.Nyameph.nyaentworks.manga.util.MangaTextUtil;
import io.github.Nyameph.nyaentworks.common.file.RecycleBin;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 归档目录（社团/作者/标签）的同步与查询。
 * <p><b>文件系统是权威</b>：{@code F:\MangaGroup\<评分分区>} 下的一级目录名
 * {@code [社团 (作者甲、作者乙)]【标签1 标签2】} 是数据源，库是可查询的镜像，
 * 外加两样文件系统存不下的东西 —— 标签关联与重名冲突记录。
 * <p>因此改了目录就要重跑 {@link #sync}；库里的行不会反向改动磁盘。
 */
@Service
public class MangaArchiveService {

    private final MangaArchiveUnitMapper unitMapper;
    private final MangaArchiveNameMapper nameMapper;
    private final MangaDataMapper mangaDataMapper;
    private final MangaDictService dictService;
    private final MangaTagService mangaTagService;
    /**
     * 构造期有环（{@code newService → archiveService}，见 {@link #unitMangas}）：
     * 用 @Lazy 让 Spring 注入代理，首次实际调用时才完成实例化，否则两个 bean 互相
     * 引对方构造器，应用起不来。
     */
    private final MangaNewService newService;
    /** 归档根本身（四个评分分区的父目录）来自配置，不再写死在代码里 */
    private final MangaProperties properties;

    public MangaArchiveService(MangaArchiveUnitMapper unitMapper,
                               MangaArchiveNameMapper nameMapper,
                               MangaDataMapper mangaDataMapper,
                               MangaDictService dictService,
                               MangaTagService mangaTagService,
                               @Lazy MangaNewService newService,
                               MangaProperties properties) {
        this.unitMapper = unitMapper;
        this.nameMapper = nameMapper;
        this.mangaDataMapper = mangaDataMapper;
        this.dictService = dictService;
        this.mangaTagService = mangaTagService;
        this.newService = newService;
        this.properties = properties;
    }

    /**
     * 归档目标：某个社团名/作者名应当归到哪个目录、该目录属于哪个评分档。
     *
     * @param score 归档目录所在分区的评分，仅用于展示「会归到 X 分」，
     *              不再参与归档闸门判定
     */
    public record ArchiveTarget(Long unitId, String folderPath, int score) {
    }

    /** 同一个名字落在多个归档目录上 */
    public record NameConflict(String name, List<MangaArchiveUnit> units) {
    }

    /**
     * 一次目录迁移：库中的 unit 被判定为搬到了当前扫描到的目录上。
     *
     * @param merged   {@code false} 表示改名（目标目录是新的，沿用原 unit 行）；
     *                 {@code true} 表示合并（目标目录已有 unit，本行已被删除）
     * @param toUnitId 承载的 unit id。改名时即原 unit 自身的 id
     */
    public record UnitMigration(Long fromUnitId, String fromFolderPath,
                                Long toUnitId, String toFolderPath, boolean merged) {
    }

    /**
     * 同步报告。
     *
     * @param unparsedFolders 目录名不是 {@code [社团 (作者)]【标签】} 形态，未入库
     *                        （对应改造前的「不规范：」打印）
     * @param conflicts       重名冲突（对应改造前的「😭 群组或作者重复」打印）
     * @param migrations      改名与合并的明细。<b>合并会删行</b>，务必逐条核对
     */
    public record SyncResult(int inserted, int updated, int renamed, int merged, int missing,
                             List<String> unparsedFolders, List<NameConflict> conflicts,
                             List<UnitMigration> migrations) {
    }

    /**
     * 归档漫画扫描报告。
     *
     * @param scanned  磁盘上解析到的漫画目录数
     * @param inserted 新插入的 {@code manga_data} 行数
     * @param updated  已存在的行被更新数
     * @param missing  目录已不在磁盘、被标为失踪的 ARCHIVED 行数
     */
    public record ScanMangaResult(int scanned, int inserted, int updated, int missing) {
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    /**
     * 扫描归档根目录，把社团/作者/标签同步入库。
     * <p>幂等：目录没变时重跑只产生 {@code updated}，不会重复插入。
     * <p>目录搬迁的处理值得留意：先按 {@code folderPath} 找 unit，找不到时再按
     * 「库中 unit 的别名集合是本目录的子集」找 —— 命中即视为那个 unit 搬到了这里，
     * 而不是新建一个再把旧的标 MISSING，后者会让原 unit 上的
     * {@code manga_tag_ref} 断掉。子集而非相等，是为了认出多个目录合并成一个的情形
     * （{@code [(甲)]} 与 {@code [(乙)]} 合成 {@code [社团 (甲、乙)]}）。
     * 详见 {@link #findMigratableUnits}。
     * <p>本目录是新目录时，首个候选改名过来；本目录已有 unit 时，候选全部并入它。
     * <b>合并会删掉被并 unit 的行</b>，故每次迁移都记入
     * {@link SyncResult#migrations()} 供核对。
     *
     * @param rootScoreMap 归档根目录 → 评分，通常传
     *                     {@link MangaScoreDir#rootScoreMap(String)}
     *                     （归档根从配置读，分区名按磁盘现扫）
     */
    @Transactional(rollbackFor = Exception.class)
    public synchronized SyncResult sync(Map<String, Integer> rootScoreMap) {
        if (rootScoreMap == null || rootScoreMap.isEmpty()) {
            // 空表在改造后有两种来路：没传，或**归档根在磁盘上不存在 / 下面没有评分分区**。
            // 后一种是外挂盘没挂上，报清楚比只说「不能为空」有用 —— 缺根时同步会把该根下的
            // 归档目录全判成失踪，所以这里必须拦住
            throw new IllegalArgumentException("归档根 " + properties.getArchiveDir()
                    + " 不存在，或其下没有评分分区目录（外挂盘没挂上？此时跑同步会把归档目录全判成失踪）");
        }
        // 四档必须齐全。分区表改成「按磁盘现扫」之后，缺一档不再是「根路径不存在」，
        // 而是**那一档从表里消失** —— 于是它下面的 unit 既扫不到（本方法只遍历表里有的根），
        // 又进不了 markMissing 的匹配范围（那边按 rootPath in 表里的键查），
        // 结果是「目录没了、库里还 ACTIVE」，静默漏判失踪。
        // 所以照旧整个拒跑 —— 与改造前缺根时 listSubDirs 抛错中止是同一种结果，只是更早、说得更清楚。
        List<Integer> missingScores = new ArrayList<>();
        for (int score : MangaScoreDir.SCORES) {
            if (!rootScoreMap.containsValue(score)) {
                missingScores.add(score);
            }
        }
        if (!missingScores.isEmpty()) {
            throw new IllegalStateException("归档根下缺评分分区：" + missingScores
                    + "（外挂盘没挂上，或分区目录被改名了？先别跑同步，否则那一档下的归档目录不会被扫到）");
        }
        List<String> unparsedFolders = new ArrayList<>();
        List<UnitMigration> migrations = new ArrayList<>();
        int inserted = 0;
        int updated = 0;
        int renamed = 0;
        int merged = 0;
        // 本次扫描覆盖到的 unit，用于反推哪些已失踪
        Set<Long> seenUnitIds = new HashSet<>();

        for (Map.Entry<String, Integer> entry : rootScoreMap.entrySet()) {
            String rootPath = entry.getKey();
            int score = entry.getValue();
            for (File dir : listSubDirs(rootPath)) {
                ArchiveFolderInfo info = MangaNameParser.parseArchiveFolderName(dir.getName());
                if (info == null) {
                    unparsedFolders.add(dir.getAbsolutePath());
                    continue;
                }
                Set<String> nameKeys = nameKeysOf(info);
                if (nameKeys.isEmpty()) {
                    unparsedFolders.add(dir.getAbsolutePath());
                    continue;
                }

                String folderPath = dir.getAbsolutePath();
                MangaArchiveUnit unit = unitMapper.selectOne(Wrappers.<MangaArchiveUnit>lambdaQuery()
                        .eq(MangaArchiveUnit::getFolderPath, folderPath));
                // 别名被本目录涵盖、且旧路径已不在磁盘上的 unit，视为搬了过来
                List<MangaArchiveUnit> movedIn = findMigratableUnits(nameKeys, seenUnitIds);

                boolean isRename = false;
                if (unit == null && !movedIn.isEmpty()) {
                    // 本目录是新目录：首个候选改名过来，充当承载行
                    unit = movedIn.removeFirst();
                    isRename = true;
                    migrations.add(new UnitMigration(unit.getId(), unit.getFolderPath(),
                            unit.getId(), folderPath, false));
                }

                if (unit == null) {
                    unit = new MangaArchiveUnit();
                    fillUnit(unit, folderPath, dir.getName(), rootPath, score, info);
                    unitMapper.insert(unit);
                    inserted++;
                } else {
                    fillUnit(unit, folderPath, dir.getName(), rootPath, score, info);
                    unitMapper.updateById(unit);
                    if (isRename) {
                        renamed++;
                    } else {
                        updated++;
                    }
                }
                seenUnitIds.add(unit.getId());

                // 余下候选并入承载行。标签不迁移：下面的 replaceRefs 会用本目录的标签整组覆盖
                for (MangaArchiveUnit from : movedIn) {
                    migrations.add(new UnitMigration(from.getId(), from.getFolderPath(),
                            unit.getId(), folderPath, true));
                    mergeUnit(from, unit.getId());
                    merged++;
                }

                // 别名与标签整组替换：量级小，无需算增量
                replaceNames(unit.getId(), info);
                mangaTagService.replaceRefs(MangaTagTargetType.ARCHIVE_UNIT, unit.getId(), info.tags());
            }
        }

        int missing = markMissing(rootScoreMap.keySet(), seenUnitIds);
        return new SyncResult(inserted, updated, renamed, merged, missing,
                unparsedFolders, findConflicts(), migrations);
    }

    /**
     * 一级子项：目录 + cbz 文件（cbz 也是一本漫画单元）。枚举本身用
     * {@link MangaNewService#listSubDirs}（本模块统一的枚举入口），这里只补一条它没有的把关：
     * 归档根配错 / 外挂盘没挂时<b>立刻报错</b>，而不是静默扫出空列表 ——
     * 「列表是空的」和「盘没挂」在界面上长得一样。
     */
    private static List<File> listSubDirs(String rootPath) {
        if (!FileUtil.isDirectory(Paths.get(rootPath).toFile())) {
            throw new IllegalArgumentException("归档根目录不存在: " + rootPath);
        }
        return MangaNewService.listSubDirs(rootPath);
    }

    private static void fillUnit(MangaArchiveUnit unit, String folderPath, String folderName,
                                 String rootPath, int score, ArchiveFolderInfo info) {
        unit.setFolderPath(folderPath);
        unit.setFolderName(folderName);
        unit.setRootPath(rootPath);
        unit.setScore(score);
        unit.setGroupName(info.groupName());
        unit.setArtistNames(info.artistNames());
        unit.setStatus(MangaArchiveUnitStatus.ACTIVE);
    }

    /**
     * 找出「别名被本目录涵盖」的既有 unit，即被搬到本目录来的那些。
     * <p>判定条件是 unit 的别名集合 ⊆ 本目录的别名集合。取子集而非相等，
     * 是为了认出多个目录合并成一个：{@code [(甲)]} 与 {@code [(乙)]} 合成
     * {@code [社团 (甲、乙)]} 时，两个旧 unit 都是新目录的子集。相等只是子集的特例，
     * 单纯改名照旧命中。
     * <p>反过来「本目录别名是 unit 的子集」不算搬迁 —— 那是一个目录被拆开，
     * 拆出的每一份都会把原 unit 认领走，谁也说不清标签该跟谁，当新目录处理更安全。
     * <p>两重收窄，都是为了不把「两个真实存在的目录」误判成一次搬迁：
     * <ol>
     *     <li>已在本次扫描中用过的 unit 不参与匹配；</li>
     *     <li><b>候选的旧路径在磁盘上仍存在时不认</b> —— 那是个还没扫到的真实目录。
     *         这是本方法唯一的防误判主力，也让扫描顺序不影响结果。</li>
     * </ol>
     * <p>原先还有「候选唯一才认」一条，多对一合并正需要多候选，故去掉。
     * 因此重名冲突现在可能被当成合并处理，这也是每次迁移都要记入
     * {@link SyncResult#migrations()} 的原因。
     *
     * @return 按 id 升序的候选，调用方取首个当承载行。无候选时为空列表
     */
    private List<MangaArchiveUnit> findMigratableUnits(Set<String> nameKeys, Set<Long> seenUnitIds) {
        List<MangaArchiveName> hits = nameMapper.selectList(Wrappers.<MangaArchiveName>lambdaQuery()
                .in(MangaArchiveName::getName, nameKeys));
        Set<Long> candidateUnitIds = hits.stream()
                .map(MangaArchiveName::getUnitId)
                .filter(id -> !seenUnitIds.contains(id))
                .collect(Collectors.toSet());
        if (candidateUnitIds.isEmpty()) {
            return new ArrayList<>();
        }
        // hits 只含与 nameKeys 相交的别名，据此判不出子集关系，须按 unit 取全量别名。
        // 只比目录名派生的那些：手工别名目录名里本来就没有，算进来会让任何带手工别名的
        // unit 永远不成子集，改名与合并就再也认不出来了
        List<MangaArchiveUnit> result = new ArrayList<>();
        for (Long id : candidateUnitIds.stream().sorted().toList()) {
            Set<String> keys = new HashSet<>(listNameKeys(id, MangaArchiveNameSource.FOLDER));
            // 空别名集合是任何集合的子集，会被任意目录吸收，故排除
            if (keys.isEmpty() || !nameKeys.containsAll(keys)) {
                continue;
            }
            MangaArchiveUnit unit = unitMapper.selectById(id);
            // 正在扫描的这个目录自己也可能在候选里，它的路径存在于磁盘上，一并被这里挡掉
            if (unit == null || FileUtil.isDirectory(unit.getFolderPath())) {
                continue;
            }
            result.add(unit);
        }
        return result;
    }

    /**
     * 把 {@code from} 并入 {@code toUnitId}：漫画归属改指向承载 unit，随后删掉
     * {@code from} 的标签关联、别名与自身。
     * <p>删行而非标 MISSING，是因为该目录的内容已经确认搬到了承载目录下，
     * 留着只会让 {@link #findConflicts()} 把两边的同名别名报成重名冲突。
     * <p>标签不迁移：承载目录的标签由调用方紧随其后的
     * {@code replaceRefs} 按其目录名整组覆盖。
     */
    private void mergeUnit(MangaArchiveUnit from, Long toUnitId) {
        // 只 set 这一列。传实体走的是 NOT_NULL 策略，而 matchedRule 是原生 int，
        // 会带着默认值 0 一起进 SET 子句，把已解析的规则抹掉
        mangaDataMapper.update(null, Wrappers.<MangaData>lambdaUpdate()
                .set(MangaData::getArchiveUnitId, toUnitId)
                .eq(MangaData::getArchiveUnitId, from.getId()));

        mangaTagService.deleteRefs(MangaTagTargetType.ARCHIVE_UNIT, from.getId());
        // 手工别名跟着内容搬到承载 unit：它们目录名里没有，删掉就再也找不回来了。
        // 目录名派生的那些不搬 —— 承载目录的目录名会派生出它自己那一套
        Set<String> targetKeys = new HashSet<>(listNameKeys(toUnitId, null));
        for (MangaArchiveName name : nameMapper.selectList(Wrappers.<MangaArchiveName>lambdaQuery()
                .eq(MangaArchiveName::getUnitId, from.getId())
                .eq(MangaArchiveName::getNameSource, MangaArchiveNameSource.MANUAL))) {
            if (targetKeys.add(name.getName())) {
                name.setUnitId(toUnitId);
                nameMapper.updateById(name);
            }
        }
        nameMapper.delete(Wrappers.<MangaArchiveName>lambdaQuery()
                .eq(MangaArchiveName::getUnitId, from.getId()));
        unitMapper.deleteById(from.getId());
    }

    /** @param source 为 null 时不限来源 */
    private List<String> listNameKeys(Long unitId, MangaArchiveNameSource source) {
        return nameMapper.selectList(Wrappers.<MangaArchiveName>lambdaQuery()
                        .eq(MangaArchiveName::getUnitId, unitId)
                        .eq(source != null, MangaArchiveName::getNameSource, source))
                .stream()
                .map(MangaArchiveName::getName)
                .toList();
    }

    /**
     * 该 unit 的<b>目录名派生</b>别名整组替换。
     * <p>只删 {@link MangaArchiveNameSource#FOLDER} 的行 —— {@code MANUAL} 那些是
     * 「这个作者的作品也放这个目录」这类目录名表达不了的关联，随目录名重建会把它们
     * 静默删掉，且无处找回。详见 {@link MangaArchiveNameSource}。
     */
    private void replaceNames(Long unitId, ArchiveFolderInfo info) {
        nameMapper.delete(Wrappers.<MangaArchiveName>lambdaQuery()
                .eq(MangaArchiveName::getUnitId, unitId)
                .eq(MangaArchiveName::getNameSource, MangaArchiveNameSource.FOLDER));
        // 手工别名与目录名撞车时，目录名那份优先：唯一键是 (unit, name, type)，
        // 先把重复的手工行让出来，否则插入会撞唯一键
        Set<String> folderKeys = nameKeysOf(info);
        if (!folderKeys.isEmpty()) {
            nameMapper.delete(Wrappers.<MangaArchiveName>lambdaQuery()
                    .eq(MangaArchiveName::getUnitId, unitId)
                    .in(MangaArchiveName::getName, folderKeys));
        }
        insertNames(unitId, info.groupName(), MangaArchiveNameType.GROUP,
                MangaArchiveNameSource.FOLDER);
        insertNames(unitId, info.artistNames(), MangaArchiveNameType.ARTIST,
                MangaArchiveNameSource.FOLDER);
    }

    /** 按 {@code 、} 拆分后逐个入库，键与解析侧 {@code splitGroupArtistToCompare} 同源 */
    private void insertNames(Long unitId, String rawNames, MangaArchiveNameType type,
                             MangaArchiveNameSource source) {
        if (StringUtils.isBlank(rawNames)) {
            return;
        }
        Set<String> done = new HashSet<>();
        for (String raw : rawNames.split("、")) {
            String key = MangaTextUtil.normalizeNameKey(raw);
            if (key == null || !done.add(key)) {
                continue;
            }
            MangaArchiveName name = new MangaArchiveName();
            name.setUnitId(unitId);
            name.setName(key);
            name.setRawName(raw.trim());
            name.setNameType(type);
            name.setNameSource(source);
            nameMapper.insert(name);
        }
    }

    /**
     * 本次扫描范围内、但未出现的 unit 标为 MISSING。
     * <p>只删状态不删行：目录可能只是被临时移走，删了行就把它的标签关联一起丢了。
     */
    private int markMissing(Collection<String> scannedRoots, Set<Long> seenUnitIds) {
        List<MangaArchiveUnit> stale = unitMapper.selectList(Wrappers.<MangaArchiveUnit>lambdaQuery()
                        .in(MangaArchiveUnit::getRootPath, scannedRoots)
                        .eq(MangaArchiveUnit::getStatus, MangaArchiveUnitStatus.ACTIVE))
                .stream()
                .filter(u -> !seenUnitIds.contains(u.getId()))
                .toList();
        for (MangaArchiveUnit unit : stale) {
            unit.setStatus(MangaArchiveUnitStatus.MISSING);
            unitMapper.updateById(unit);
        }
        return stale.size();
    }

    /** 目录名里的全部别名归一化键 */
    private static Set<String> nameKeysOf(ArchiveFolderInfo info) {
        Set<String> keys = new LinkedHashSet<>();
        collectKeys(keys, info.groupName());
        collectKeys(keys, info.artistNames());
        return keys;
    }

    private static void collectKeys(Set<String> keys, String rawNames) {
        if (StringUtils.isBlank(rawNames)) {
            return;
        }
        for (String raw : rawNames.split("、")) {
            String key = MangaTextUtil.normalizeNameKey(raw);
            if (key != null) {
                keys.add(key);
            }
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 归档查找表。
     *
     * @param targets 社团名/作者名 → 归档目标。一个名字落在多个 unit 上时，
     *                已按 {@code rootScoreMap} 的优先级取了其一
     */
    public record NameTargets(Map<String, ArchiveTarget> targets) {

        public ArchiveTarget get(String nameKey) {
            return nameKey == null ? null : targets.get(nameKey);
        }

        public boolean isEmpty() {
            return targets.isEmpty();
        }
    }

    /**
     * 社团名/作者名 → 归档目标 的扁平映射，供归档扫描逐本漫画查表。
     * <p>只含 {@link MangaArchiveUnitStatus#ACTIVE} 的 unit。
     * <p>一个名字对应多个 unit（重名冲突）时，按 {@code rootScoreMap} 的迭代顺序
     * 取靠前的那个 —— 与改造前「遍历 {@code LinkedHashMap}，第一个命中即用」的行为一致。
     *
     * @param rootScoreMap 决定重名时的优先级，键的顺序即优先级从高到低
     */
    public NameTargets loadNameTargets(Map<String, Integer> rootScoreMap) {
        List<MangaArchiveUnit> units = unitMapper.selectList(Wrappers.<MangaArchiveUnit>lambdaQuery()
                .eq(MangaArchiveUnit::getStatus, MangaArchiveUnitStatus.ACTIVE));
        if (units.isEmpty()) {
            return new NameTargets(Map.of());
        }
        Map<Long, MangaArchiveUnit> unitById = units.stream()
                .collect(Collectors.toMap(MangaArchiveUnit::getId, u -> u));

        // 归档根的优先级：靠前的根优先。不在 rootScoreMap 里的根排到最后
        List<String> rootOrder = rootScoreMap == null ? List.of() : new ArrayList<>(rootScoreMap.keySet());

        Map<String, ArchiveTarget> result = new HashMap<>();
        Map<String, Integer> chosenRootRank = new HashMap<>();
        for (MangaArchiveName name : nameMapper.selectList(Wrappers.<MangaArchiveName>lambdaQuery()
                .in(MangaArchiveName::getUnitId, unitById.keySet()))) {
            MangaArchiveUnit unit = unitById.get(name.getUnitId());
            if (unit == null) {
                continue;
            }
            int rank = rootOrder.indexOf(unit.getRootPath());
            if (rank < 0) {
                rank = rootOrder.size();
            }
            ArchiveTarget existing = result.get(name.getName());
            if (existing != null && chosenRootRank.get(name.getName()) <= rank) {
                continue;
            }
            chosenRootRank.put(name.getName(), rank);
            result.put(name.getName(), new ArchiveTarget(unit.getId(),
                    unit.getFolderPath(), unit.getScore()));
        }
        return new NameTargets(result);
    }

    /**
     * 一本漫画的归档匹配结果。
     *
     * @param target       命中的归档目录；{@code null} 表示这本的作者还没有归档目录，
     *                     即「未归档」
     * @param matchedNames 命中 {@code target} 的那些社团名/作者名原文，供页面说明
     *                     「凭哪个名字归到这里」
     */
    public record ArchiveMatch(ArchiveTarget target, List<String> matchedNames) {

        /**
         * 能不能归档。
         * <p>只在服务端用，前端不读它 —— Jackson 3 只序列化记录组件，派生方法不出现在
         * JSON 里。页面上「归档还是未归档」看的就是 {@code target} 这个组件本身。
         */
        public boolean archivable() {
            return target != null;
        }
    }

    /**
     * 一本漫画该归到哪个归档目录。
     * <p>逻辑从 {@code MangaNameParserTestUtil.scanAndArchiveMangas} 的主循环搬来 ——
     * 那里是「扫描散漫并归档」，新漫画的「存储」要走同一套判定，各写一遍必然走岔。
     * <p>社团名与作者名指向不同目录时不再拒绝，按归档根优先级（评分高者）取其一；
     * 原先把「存在多个映射目录」当拒绝理由，已去掉 —— 页面上「归到哪」不必再由人决定。
     * <p>原先还有一条「高分映射入低分」（归档目录分区评分低于本次评分则拒绝，
     * 对应旧 {@code illgal = true}），已去掉：归进低分区不影响漫画单独打的分
     * （{@code score_source = SELF} 原样保留），「高分映射入低分」不再是阻碍。
     *
     * @param groupName 社团名原文，可空
     * @param artist    作者名原文，多作者含 {@code 、} 分隔符，可空
     * @param scanScore 本次给这本漫画的评分。不再参与任何判定，保留该参数只是
     *                  调用方习惯传、且归档匹配本来也只看名字
     * @param targets   查找表，由 {@link #loadNameTargets} 取一次后整批复用 ——
     *                  逐本重查会把一次扫描变成上千次全表查询
     */
    public ArchiveMatch matchArchive(String groupName, String artist, int scanScore,
                                     NameTargets targets) {
        List<String> candidates = new ArrayList<>(MangaNameParser.splitGroupArtistToCompare(artist));
        candidates.addAll(MangaNameParser.splitGroupArtistToCompare(groupName));

        ArchiveTarget chosen = null;
        List<String> matchedNames = new ArrayList<>();
        for (String candidate : candidates) {
            // 库里的键多做了一步 NFC 归一，这里对齐，否则分解形式的假名查不到
            String nameKey = MangaTextUtil.normalizeNameKey(candidate);
            ArchiveTarget target = targets == null ? null : targets.get(nameKey);
            if (target == null) {
                continue;
            }
            if (chosen == null) {
                chosen = target;
                matchedNames.add(candidate);
            } else if (!chosen.folderPath().equals(target.folderPath())) {
                // 社团名与作者名指向不同目录：不拒绝，归档根优先级高者赢（评分高者）。
                // score 是 int（由所属根目录推导），直接比大小
                if (target.score() > chosen.score()) {
                    chosen = target;
                    matchedNames.clear();
                    matchedNames.add(candidate);
                }
            } else {
                matchedNames.add(candidate);
            }
        }

        if (chosen == null) {
            return new ArchiveMatch(null, List.of());
        }
        // 原先把「高分映射入低分」也当一条拒绝理由，已去掉：归档目录所在分区低不低
        // 不影响归不归 —— 归档后漫画单独打的分（score_source = SELF）原样保留，
        // 不因归进低分区而被改写。见 matchArchive 的类注释
        return new ArchiveMatch(chosen, List.copyOf(matchedNames));
    }

    /**
     * 重名冲突：同一个名字（社团名与作者名共享命名空间）落在多个归档目录上。
     * <p>替代改造前 {@code readArchiveGroupArtists} / {@code checkWriters} 里
     * 只打印不留存的「😭 群组或作者重复」。
     */
    public List<NameConflict> findConflicts() {
        List<MangaArchiveName> all = nameMapper.selectList(Wrappers.<MangaArchiveName>lambdaQuery()
                .orderByAsc(MangaArchiveName::getName));
        Map<String, Set<Long>> unitIdsByName = new LinkedHashMap<>();
        for (MangaArchiveName name : all) {
            unitIdsByName.computeIfAbsent(name.getName(), k -> new LinkedHashSet<>())
                    .add(name.getUnitId());
        }
        List<NameConflict> conflicts = new ArrayList<>();
        unitIdsByName.forEach((name, unitIds) -> {
            if (unitIds.size() > 1) {
                List<MangaArchiveUnit> units = unitMapper.selectByIds(unitIds);
                conflicts.add(new NameConflict(name, units));
            }
        });
        return conflicts;
    }

    /**
     * 按目录全路径找 unit id。
     * <p>合集存储那边用得上：把合集目录搬进归档根后跑一次 {@link #sync}，
     * 再靠这个方法拿到刚建出来的 unit id，好让合集里的漫画绑上它。
     *
     * @return unit id；库里没有这个路径时返回 {@code null}
     */
    public Long findUnitIdByFolderPath(String folderPath) {
        if (StringUtils.isBlank(folderPath)) {
            return null;
        }
        MangaArchiveUnit unit = unitMapper.selectOne(Wrappers.<MangaArchiveUnit>lambdaQuery()
                .eq(MangaArchiveUnit::getFolderPath, folderPath));
        return unit == null ? null : unit.getId();
    }

    /** 归档目录的标签 */
    public List<String> listTags(Long unitId) {
        return mangaTagService.listTagNames(MangaTagTargetType.ARCHIVE_UNIT, unitId);
    }

    /**
     * 给漫画列表批量注入回显标签：独立标签（MANGA_DATA）优先，没有独立标签就用
     * 父级标签（归档目录 ARCHIVE_UNIT）。回显只给名字（{@code List<String>}），
     * 编辑仍走 {@link MangaTagService#listTags} 取带 namespace 的 {@code TagItem}。
     *
     * <p>父级标签来自 {@code archiveMatch.target.unitId} —— 归档匹配算的是「这本该归到哪个
     * 目录」，该目录的标签就是漫画没打独立标签时应继承显示的。已归档漫画的归属目录与
     * 匹配结果一致，未归档/新漫画命中归档作者时同样能拿到父级标签。
     *
     * @param mangas        现扫出的漫画列表（{@code NewManga.tags} 恒为 null）
     * @param defaultUnitId 兜底的父级归档目录 id：归档页点开目录时 unitId 已知，传它更稳；
     *                      其余传 {@code null}，走 {@code archiveMatch.target.unitId}
     * @return 注入标签后的新列表，不动入参
     */
    public List<MangaNewService.NewManga> withDisplayTags(List<MangaNewService.NewManga> mangas,
                                                          Long defaultUnitId) {
        if (mangas == null || mangas.isEmpty()) {
            return List.of();
        }
        // 独立标签：mangaId → 标签名
        List<Long> ids = mangas.stream().map(MangaNewService.NewManga::mangaId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, List<String>> own = ids.isEmpty() ? Map.of()
                : mangaTagService.listTagNamesBatch(MangaTagTargetType.MANGA_DATA, ids);
        // 父级标签：归档目录 id 去重后一次查
        Set<Long> unitIds = new HashSet<>();
        for (MangaNewService.NewManga m : mangas) {
            Long unitId = defaultUnitId != null ? defaultUnitId : unitIdOf(m);
            if (unitId != null) {
                unitIds.add(unitId);
            }
        }
        Map<Long, List<String>> parent = unitIds.isEmpty() ? Map.of()
                : mangaTagService.listTagNamesBatch(MangaTagTargetType.ARCHIVE_UNIT, unitIds);
        // 独立优先、父级兜底，逐个注入
        List<MangaNewService.NewManga> out = new ArrayList<>(mangas.size());
        for (MangaNewService.NewManga m : mangas) {
            List<String> tags = m.mangaId() == null ? null : own.get(m.mangaId());
            if (tags == null || tags.isEmpty()) {
                Long unitId = defaultUnitId != null ? defaultUnitId : unitIdOf(m);
                tags = unitId == null ? null : parent.get(unitId);
            }
            out.add(m.withTags(tags == null ? List.of() : tags));
        }
        return out;
    }

    /** 一本漫画的父级归档目录 id：从归档匹配结果里取 target.unitId */
    private static Long unitIdOf(MangaNewService.NewManga m) {
        if (m.archiveMatch() == null || m.archiveMatch().target() == null) {
            return null;
        }
        return m.archiveMatch().target().unitId();
    }

    /**
     * 一个标签在某个归档目录下的出现本数（「高频标签」列用）。
     *
     * @param tagName 标签名
     * @param count   该目录下带这个标签（独立标签）的漫画本数
     */
    public record TagFreq(String tagName, long count) {
    }

    /**
     * 列表页用的一行。
     *
     * @param mangaCount   归属本目录的漫画数，由 {@code manga_data.archive_unit_id} 聚合而来。
     *                     {@code MangaDataService} 还没有任何写入点，所以现阶段恒为 0
     *                     —— 这是「归档目录的本数由绑定的漫画统计而来」这条决议的落点，
     *                     不是现场数目录
     * @param averageScore 本目录下漫画的平均分，按 {@code AVG(COALESCE(score, 目录分))} 算 ——
     *                     有单独评分（SELF）的用其分，没有的按目录所在分区评分计。
     *                     没扫过漫画时是 0，跑完 {@link #scanArchivedMangas} 即真
     * @param topTags      本目录下漫画的独立标签按出现本数取前 3，见 {@link TagFreq}。
     *                     没打标签时是空列表
     * @param conflicted   该目录的某个别名同时落在别的目录上，即重名冲突的一方
     * @param folderExists 目录当下在不在磁盘上。与 {@code status} 分开：status 是上次同步的
     *                     结论，这个是此刻的事实，两者不一致说明该重新同步了
     */
    public record UnitView(MangaArchiveUnit unit, List<String> tags, List<NameEdit> names,
                           long mangaCount, double averageScore, List<TagFreq> topTags,
                           boolean conflicted, boolean folderExists) {
    }

    /**
     * 归档目录列表，各筛选条件之间是「与」的关系，为空表示不限。
     *
     * @param keyword      按社团名/作者名搜。入参会走
     *                     {@link MangaTextUtil#normalizeNameKey} 归一后再比
     *                     {@code manga_archive_name.name}，否则搜日文假名会因分解形式搜不到；
     *                     同时也拿原串比目录名，便于按目录名找
     * @param tagName      只留打了该标签的
     * @param conflictOnly 只留重名冲突的一方
     */
    public List<UnitView> listUnits(MangaArchiveUnitStatus status, Integer score,
                                    String keyword, String tagName, boolean conflictOnly) {
        List<MangaArchiveUnit> units = unitMapper.selectList(Wrappers.<MangaArchiveUnit>lambdaQuery()
                .eq(status != null, MangaArchiveUnit::getStatus, status)
                .eq(score != null, MangaArchiveUnit::getScore, score)
                .orderByDesc(MangaArchiveUnit::getScore)
                .orderByAsc(MangaArchiveUnit::getFolderName));
        if (units.isEmpty()) {
            return List.of();
        }

        Set<Long> conflictedIds = findConflicts().stream()
                .flatMap(c -> c.units().stream())
                .map(MangaArchiveUnit::getId)
                .collect(Collectors.toSet());
        Set<Long> taggedIds = StringUtils.isBlank(tagName) ? null
                : new HashSet<>(mangaTagService.listTargetIds(MangaTagTargetType.ARCHIVE_UNIT, tagName));
        Set<Long> keywordIds = StringUtils.isBlank(keyword) ? null : searchByName(keyword);

        List<MangaArchiveUnit> matched = units.stream()
                .filter(u -> !conflictOnly || conflictedIds.contains(u.getId()))
                .filter(u -> taggedIds == null || taggedIds.contains(u.getId()))
                .filter(u -> keywordIds == null || keywordIds.contains(u.getId())
                        || StringUtils.containsIgnoreCase(u.getFolderName(), keyword.trim()))
                .toList();
        if (matched.isEmpty()) {
            return List.of();
        }

        List<Long> ids = matched.stream().map(MangaArchiveUnit::getId).toList();
        Map<Long, List<String>> tagsByUnit =
                mangaTagService.listTagNamesBatch(MangaTagTargetType.ARCHIVE_UNIT, ids);
        Map<Long, List<NameEdit>> namesByUnit = new LinkedHashMap<>();
        for (MangaArchiveName name : nameMapper.selectList(Wrappers.<MangaArchiveName>lambdaQuery()
                .in(MangaArchiveName::getUnitId, ids)
                .orderByAsc(MangaArchiveName::getNameType)
                .orderByAsc(MangaArchiveName::getId))) {
            namesByUnit.computeIfAbsent(name.getUnitId(), k -> new ArrayList<>())
                    .add(new NameEdit(name.getRawName(), name.getNameType(),
                            name.getNameSource() == null
                                    ? MangaArchiveNameSource.FOLDER : name.getNameSource()));
        }
        Map<Long, Long> mangaCounts = countMangaByUnit(ids);
        Map<Long, Integer> unitScoreById = matched.stream()
                .collect(Collectors.toMap(MangaArchiveUnit::getId, MangaArchiveUnit::getScore));
        Map<Long, Double> avgScores = averageScoreByUnit(ids, unitScoreById);
        Map<Long, List<TagFreq>> topTags = topTagsByUnit(ids);

        return matched.stream()
                .map(u -> new UnitView(u,
                        tagsByUnit.getOrDefault(u.getId(), List.of()),
                        namesByUnit.getOrDefault(u.getId(), List.of()),
                        mangaCounts.getOrDefault(u.getId(), 0L),
                        avgScores.getOrDefault(u.getId(), 0.0),
                        topTags.getOrDefault(u.getId(), List.of()),
                        conflictedIds.contains(u.getId()),
                        FileUtil.isDirectory(u.getFolderPath())))
                .toList();
    }

    /** 关键词 → 命中的 unit id，比的是归一化后的别名 */
    private Set<Long> searchByName(String keyword) {
        String key = MangaTextUtil.normalizeNameKey(keyword);
        if (key == null) {
            return Set.of();
        }
        return nameMapper.selectList(Wrappers.<MangaArchiveName>lambdaQuery()
                        .like(MangaArchiveName::getName, key))
                .stream()
                .map(MangaArchiveName::getUnitId)
                .collect(Collectors.toSet());
    }

    /**
     * 每个归档目录下的漫画数。
     * <p>{@code manga_data} 目前没有任何写入点，所以这里查出来是空的、各目录都记 0。
     * 等漫画入库后这一列自然就有值，不必改调用方。
     */
    private Map<Long, Long> countMangaByUnit(Collection<Long> unitIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : mangaDataMapper.selectMaps(Wrappers.<MangaData>query()
                .select("archive_unit_id", "count(*) as c")
                .in("archive_unit_id", unitIds)
                .groupBy("archive_unit_id"))) {
            Object id = row.get("archive_unit_id");
            Object count = row.get("c");
            if (id != null && count != null) {
                counts.put(((Number) id).longValue(), ((Number) count).longValue());
            }
        }
        return counts;
    }

    /**
     * 每个归档目录下漫画的平均分。
     * <p>单本评分取 {@code COALESCE(score, unit.score)}：有 SELF 分的用其分，
     * 没有的（新行落 INHERIT_ARCHIVE）按目录所在分区的评分算。与
     * {@link #countMangaByUnit} 同一批行（同 status 不筛），保证「本数」与
     * 「平均分」对得上 —— 平均分 = 分数总和 ÷ 本数。
     */
    private Map<Long, Double> averageScoreByUnit(Collection<Long> unitIds,
                                                 Map<Long, Integer> unitScoreById) {
        Map<Long, List<Integer>> scoresByUnit = new HashMap<>();
        for (Map<String, Object> row : mangaDataMapper.selectMaps(Wrappers.<MangaData>query()
                .select("archive_unit_id", "score")
                .in("archive_unit_id", unitIds))) {
            Object id = row.get("archive_unit_id");
            if (id == null) {
                continue;
            }
            Object score = row.get("score");
            scoresByUnit.computeIfAbsent(((Number) id).longValue(), k -> new ArrayList<>())
                    .add(score == null ? null : ((Number) score).intValue());
        }
        Map<Long, Double> result = new HashMap<>();
        scoresByUnit.forEach((id, scores) -> {
            double sum = 0;
            for (Integer s : scores) {
                sum += s == null ? unitScoreById.getOrDefault(id, 0) : s;
            }
            result.put(id, sum / scores.size());
        });
        return result;
    }

    /**
     * 每个归档目录下、按出现本数取前 3 的漫画独立标签。
     * <p>口径见 {@link MangaDataMapper#countTagsByUnit}：只算每本漫画自己的
     * MANGA_DATA 标签，不把继承的目录标签算进去。结果已按
     * {@code archive_unit_id, cnt DESC} 排好序，故每个 unit 只取前 3 行。
     */
    private Map<Long, List<TagFreq>> topTagsByUnit(Collection<Long> unitIds) {
        if (unitIds == null || unitIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<TagFreq>> result = new HashMap<>();
        for (Map<String, Object> row : mangaDataMapper.countTagsByUnit(unitIds)) {
            Long unitId = ((Number) row.get("unit_id")).longValue();
            String tagName = (String) row.get("tag_name");
            long cnt = ((Number) row.get("cnt")).longValue();
            List<TagFreq> list = result.computeIfAbsent(unitId, k -> new ArrayList<>());
            if (list.size() < 3) {
                list.add(new TagFreq(tagName, cnt));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 扫描归档漫画
    // ------------------------------------------------------------------

    /**
     * 扫描归档目录下的漫画，把 {@code manga_data} 的 {@code ARCHIVED} 层补全。
     * <p>遍历各 ACTIVE unit 目录，递归找漫画目录（判定与
     * {@code MangaNewService.collectMangasUnder} 同思路：{@code parseDir} 命中即漫画，
     * 命中后不再往下钻）。对每本：按 {@code folder_path} 认行 upsert，解析出的字段
     * 整组覆盖；评分按来源区分 —— <b>已存在行的 SELF 分不覆盖</b>（人打的，优先），
     * 其余落 {@code unit.score + INHERIT_ARCHIVE}；{@code cover_file} 用
     * {@code getFirstImage} 重算（压缩后封面后缀变了的存量行在这里被修回）。
     * <p>最后把「本次没扫到且目录已不在磁盘」的 ARCHIVED 行标 {@code MISSING}。
     * 目录还在磁盘上只是没被走到（unit 非 ACTIVE）的不动。
     */
    public ScanMangaResult scanArchivedMangas() {
        MangaNameParser parser = new MangaNameParser(dictService.current());
        List<MangaArchiveUnit> units = unitMapper.selectList(Wrappers.<MangaArchiveUnit>lambdaQuery()
                .eq(MangaArchiveUnit::getStatus, MangaArchiveUnitStatus.ACTIVE));
        ScanCounters counters = new ScanCounters();
        Set<String> seenPaths = new HashSet<>();
        for (MangaArchiveUnit unit : units) {
            Path root = Paths.get(unit.getFolderPath());
            if (!Files.isDirectory(root)) {
                continue;
            }
            scanUnitMangas(parser, unit, root, seenPaths, counters);
        }
        int missing = markArchivedMissing(seenPaths);
        return new ScanMangaResult(counters.scanned, counters.inserted,
                counters.updated, missing);
    }

    /** 递归找 unit 目录下的漫画并 upsert；命中漫画后不再往下钻 */
    private void scanUnitMangas(MangaNameParser parser, MangaArchiveUnit unit, Path dir,
                                Set<String> seenPaths, ScanCounters counters) {
        for (File sub : listSubDirs(dir.toString())) {
            Path subPath = sub.toPath();
            MangaData parsed = parser.parseDir(subPath);
            if (parsed == null) {
                // cbz 是文件，钻不进去；只对目录递归下钻
                if (Files.isDirectory(subPath)) {
                    scanUnitMangas(parser, unit, subPath, seenPaths, counters);
                }
                continue;
            }
            counters.scanned++;
            upsertArchivedManga(parsed, subPath, unit, seenPaths, counters);
        }
    }

    private void upsertArchivedManga(MangaData parsed, Path dir, MangaArchiveUnit unit,
                                     Set<String> seenPaths, ScanCounters counters) {
        String folderPath = dir.toString();
        seenPaths.add(folderPath);
        MangaData row = mangaDataMapper.selectOne(Wrappers.<MangaData>lambdaQuery()
                .eq(MangaData::getFolderPath, folderPath));
        boolean isNew = row == null;
        if (isNew) {
            row = new MangaData();
            counters.inserted++;
        } else {
            counters.updated++;
        }

        // 解析出的字段整组覆盖。评分单独处理，见下
        row.setFolderPath(folderPath);
        row.setExhibit(parsed.getExhibit());
        row.setGroupName(parsed.getGroupName());
        row.setArtist(parsed.getArtist());
        row.setDateTag(parsed.getDateTag());
        row.setTitle(parsed.getTitle());
        row.setParody(parsed.getParody());
        row.setMagazine(parsed.getMagazine());
        row.setMatchedRule(parsed.getMatchedRule());
        row.setArchiveUnitId(unit.getId());
        row.setStatus(MangaDataStatus.ARCHIVED);
        row.setCoverFile(MangaNameParser.getFirstImage(dir));
        row.setFileType(MangaDataFileType.fromFolderPath(folderPath));
        // 文件数/图片数现扫落库，之后归档作者页/抽选读库免遍历（与 cover_file 同款重算修回）
        MangaNewService.FileCounts counts = MangaNewService.countFiles(dir);
        row.setFileCount(counts.fileCount());
        row.setImageCount(counts.imageCount());

        // 已存在行的 SELF 分不覆盖（人打的，优先）；新行或沿用目录分的，落 unit.score
        boolean keepSelf = !isNew && row.getScoreSource() == MangaScoreSource.SELF;
        if (!keepSelf) {
            row.setScore(unit.getScore());
            row.setScoreSource(MangaScoreSource.INHERIT_ARCHIVE);
        }

        if (isNew) {
            mangaDataMapper.insert(row);
        } else {
            mangaDataMapper.updateById(row);
        }
    }

    /**
     * 一个归档目录下的全部漫画（「归档作者」页点本数打开的合集弹窗）。
     * <p>遍历 unit 目录递归找漫画，每本复用 {@link MangaNewService#toNewManga} 现扫现拼 ——
     * 卡片上的「可存储/不规范/未识别展会/未识别原作」标签与新漫画、未归档两页同一套判定，
     * 不会出现「合集里说能存、单本页又说不能」的口径差。
     * <p>评分对库里已有的行取行的分（SELF 是人打的），没有行或没分则沿用目录分。
     */
    public List<MangaNewService.NewManga> unitMangas(Long unitId) {
        MangaArchiveUnit unit = unitMapper.selectById(unitId);
        if (unit == null) {
            throw new IllegalArgumentException("归档目录不存在：" + unitId);
        }
        Path root = Paths.get(unit.getFolderPath());
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        MangaNameParser parser = new MangaNameParser(dictService.current());
        // 该 unit 下的漫画行一次取全，构 path → row，递归里别再逐本 selectOne（N+1）。
        // targets 不再需要：漫画已经在这个归档目录里，归档去向/提示无意义，
        // 传 null 让 toNewManga 跳过归档匹配，省 loadNameTargets 的全量查表
        Map<String, MangaData> rowByPath = listMangaUnder(unit.getFolderPath()).stream()
                .filter(r -> r.getFolderPath() != null)
                .collect(Collectors.toMap(MangaData::getFolderPath, r -> r, (a, b) -> a));
        List<MangaNewService.NewManga> result = new ArrayList<>();
        collectUnitMangas(parser, unit, root, rowByPath, result);
        return withDisplayTags(result, unitId);
    }

    private void collectUnitMangas(MangaNameParser parser, MangaArchiveUnit unit, Path dir,
                                   Map<String, MangaData> rowByPath,
                                   List<MangaNewService.NewManga> result) {
        for (File sub : listSubDirs(dir.toString())) {
            Path subPath = sub.toPath();
            MangaData parsed = parser.parseDir(subPath);
            if (parsed == null) {
                // cbz 是文件，钻不进去；只对目录递归下钻
                if (Files.isDirectory(subPath)) {
                    collectUnitMangas(parser, unit, subPath, rowByPath, result);
                }
                continue;
            }
            MangaData row = rowByPath.get(subPath.toString());
            Integer score = row != null && row.getScore() != null ? row.getScore() : unit.getScore();
            MangaScoreSource source = row != null && row.getScoreSource() == MangaScoreSource.SELF
                    ? MangaScoreSource.SELF : MangaScoreSource.INHERIT_ARCHIVE;
            result.add(newService.toNewManga(parsed, subPath, score, source, null,
                    row != null ? row.getId() : null,
                    row != null ? row.getFileCount() : null,
                    row != null ? row.getImageCount() : null));
        }
    }

    // ------------------------------------------------------------------
    // 合并冲突页：归档目录根下的「其他文件」（列出 + 批量移动）
    // ------------------------------------------------------------------

    /**
     * 归档目录根下的一个「其他文件」。
     *
     * @param name          直接子项的名字（移动时就用它，不带路径）
     * @param path          磁盘路径
     * @param directory     是目录还是文件（页面上要分开画）
     * @param size          文件的字节数；目录恒为 0（递归求和太慢，页面上也用不着）
     * @param blockedReason 动不了的原因；{@code null} 表示可移动
     */
    public record OtherEntry(String name, String path, boolean directory, long size,
                             String blockedReason) {
    }

    /**
     * 归档目录根下的其他文件：<b>直接子项里既不是漫画、也不是评分分区</b>的那些。
     *
     * <p>只扫直接子项（<b>不递归</b>）——「根目录其他文件」说的就是这一层。递归下去会把
     * 漫画目录里的图片、说明文件全捞出来，那是几万条，页面根本没法用。
     *
     * <p><b>什么算「不是漫画」</b>：与 {@code collectUnitMangas} 同一口径 —— 目录或 cbz
     * 且 {@code parseDir} 认得出。普通文件（{@code .txt} / {@code .url} / 图片）即使名字
     * 长得像漫画名也不算漫画：扫描侧从来不把它们当漫画（{@code listSubDirs} 只收目录与 cbz），
     * 这里要是认了，就会出现「列表里有这本、其他文件里没有」的对不上。
     *
     * <p><b>目录里的漫画要拦住</b>：{@code collectUnitMangas} 会钻进「认不出漫画」的目录继续找，
     * 所以 {@code 作者名\旧版本\} 这种目录底下的漫画<b>已经落库</b>（{@code folder_path} 指着
     * 它下面的路径）。把这种目录整个搬走，库里的行就指向一个不存在的地方 —— 那不是「搬几个散文件」
     * 的后果，所以直接打上拦截原因，让人先把里面的漫画按漫画处理掉。
     */
    public List<OtherEntry> unitOthers(Long unitId) {
        MangaArchiveUnit unit = requireUnit(unitId);
        Path root = Paths.get(unit.getFolderPath());
        if (!Files.isDirectory(root)) {
            // 与 unitMangas 一致：目录不在磁盘上就返回空，由页面上的状态说明兜底
            return List.of();
        }
        return scanOthers(root, new MangaNameParser(dictService.current()));
    }

    /**
     * 扫直接子项，挑出其他文件（纯函数：给一个目录就出清单，不碰库）。
     * <p>排序按名字，与扫描侧一致 —— 两个页面列同样的东西，顺序不同会让人以为漏了。
     */
    static List<OtherEntry> scanOthers(Path root, MangaNameParser parser) {
        File[] children = root.toFile().listFiles();
        if (children == null) {
            return List.of();
        }
        List<File> sorted = new ArrayList<>(List.of(children));
        sorted.sort(Comparator.comparing(File::getName));
        List<OtherEntry> others = new ArrayList<>();
        for (File child : sorted) {
            Path path = child.toPath();
            String name = child.getName();
            if (MangaScoreDir.parseScore(name) != null) {
                continue; // 评分分区目录：底下全是漫画，不是「其他文件」
            }
            boolean mangaCandidate = child.isDirectory() || MangaCbzUtil.isCbz(path);
            if (mangaCandidate && parser.parseDir(path) != null) {
                continue; // 已经当漫画列在侧栏里了
            }
            String blocked = null;
            if (child.isDirectory()) {
                int mangas = countMangasUnder(parser, path);
                if (mangas > 0) {
                    blocked = "这个目录底下有 " + mangas + " 本漫画（已经落库），"
                            + "先把它们按漫画移走再搬这个目录";
                }
            }
            others.add(new OtherEntry(name, path.toString(), child.isDirectory(),
                    child.isDirectory() ? 0L : child.length(), blocked));
        }
        return others;
    }

    /** 递归数一个目录下的漫画（口径同 {@code collectUnitMangas}：认得出就是一本） */
    private static int countMangasUnder(MangaNameParser parser, Path dir) {
        int n = 0;
        for (File sub : MangaNewService.listSubDirs(dir.toString())) {
            Path path = sub.toPath();
            if (parser.parseDir(path) != null) {
                n++;
            } else if (Files.isDirectory(path)) {
                n += countMangasUnder(parser, path);
            }
        }
        return n;
    }

    /**
     * 其他文件的搬动计划（旧路径 / 新路径 / 拦截原因），一条都不动磁盘。
     *
     * <p><b>只受理「确实是这个目录下的其他文件」的名字</b>：请求体里的名字是对接口开放的，
     * 不按 {@link #scanOthers} 再判一次，就等于让接口能搬走这个目录下的<b>漫画</b> ——
     * 那些是落了库的（{@code folder_path} 指着它们），搬走会把库里的行指到不存在的地方，
     * 而且绕过了页面上「漫画要走上面的移动 / 删除」这条分工。前端确实只列其他文件，
     * 但「前端只画不判」，判定必须在后端。
     *
     * @param names 要搬的直接子项名（不许带路径分隔符，防着从接口把别处的东西搬走）
     */
    public OtherMovePlan planOtherMove(Long fromUnitId, Long toUnitId, List<String> names) {
        MangaArchiveUnit from = requireUnit(fromUnitId);
        MangaArchiveUnit to = requireUnit(toUnitId);
        List<String> clean = new ArrayList<>();
        for (String name : names == null ? List.<String>of() : names) {
            clean.add(requireChildName(name));
        }
        String blocked = null;
        if (clean.isEmpty()) {
            blocked = "没有勾选要移动的文件";
        } else if (Objects.equals(fromUnitId, toUnitId)) {
            blocked = "两边是同一个归档目录，没有可搬的";
        } else if (!FileUtil.isDirectory(from.getFolderPath())) {
            blocked = "源目录不在磁盘上：" + from.getFolderPath();
        } else if (to.getStatus() != MangaArchiveUnitStatus.ACTIVE) {
            blocked = "目标归档目录不是 ACTIVE，动不了：" + to.getFolderPath();
        } else if (!FileUtil.isDirectory(to.getFolderPath())) {
            blocked = "目标归档目录不在磁盘上：" + to.getFolderPath();
        }

        List<OtherMove> moves = new ArrayList<>();
        if (blocked == null) {
            Path fromDir = Paths.get(from.getFolderPath());
            Path toDir = Paths.get(to.getFolderPath());
            Map<String, OtherEntry> known = new LinkedHashMap<>();
            for (OtherEntry entry : scanOthers(fromDir, new MangaNameParser(dictService.current()))) {
                known.put(entry.name(), entry);
            }
            for (String name : clean) {
                Path source = fromDir.resolve(name);
                Path target = toDir.resolve(name);
                OtherEntry entry = known.get(name);
                String reason;
                if (entry == null) {
                    reason = "「" + name + "」不是这个目录下的其他文件"
                            + "（这个目录里的漫画要走上面的移动 / 删除，别在这里搬）";
                } else if (entry.blockedReason() != null) {
                    reason = entry.blockedReason();
                } else {
                    reason = blockedReasonFor(source, target);
                }
                moves.add(new OtherMove(name, source.toString(), target.toString(), reason));
            }
        }
        return new OtherMovePlan(from.getFolderPath(), to.getFolderPath(), moves, blocked);
    }

    /**
     * 单个文件的拦截判据。
     * <p>目标已存在同名时<b>不覆盖</b>（同 {@code GroupFileOps.planMoves} 的立场）：
     * 覆盖会静默毁掉目标那一份，而这页面上搬的多半是随手丢下的图 / 说明，
     * 「哪一份是新的」人自己都分不清。
     */
    private static String blockedReasonFor(Path source, Path target) {
        if (!Files.exists(source)) {
            return "源文件不在了：" + source;
        }
        if (Files.exists(target)) {
            return "目标已存在同名：" + target;
        }
        return null;
    }

    /**
     * 名字必须是「直接子项」的名字：不许空、不许带路径分隔符、不许 {@code ..}。
     * <p>请求体是字符串数组，不守这一道就等于让接口能搬磁盘上任意位置的东西。
     */
    static String requireChildName(String name) {
        String value = StringUtils.trimToNull(name);
        if (value == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (value.contains("/") || value.contains("\\") || value.equals("..")
                || value.contains(":")) {
            throw new IllegalArgumentException("只能搬这个目录下的直接子项，名字里不能有路径："
                    + value);
        }
        return value;
    }

    /**
     * 其他文件的批量搬动（执行前必须看过计划）。
     * <p><b>整批不执行</b>：任一条被拦就一条都不搬（同「有一条 blockedReason 就整批不执行」）；
     * 中途失败则中断并报出已经搬了几个 —— 这些是互不相干的散文件，不是「一组」，
     * 搬一半不会像歌曲那样裂出半个组来，所以<b>不回滚</b>，让人看得见现状。
     *
     * <p>这些文件不在库里（不是漫画），所以磁盘动完没有「库跟着走」这一步。
     *
     * @return 实际搬动的个数
     */
    public int applyOtherMove(Long fromUnitId, Long toUnitId, List<String> names) {
        OtherMovePlan plan = planOtherMove(fromUnitId, toUnitId, names);
        if (plan.blockedReason() != null) {
            throw new IllegalStateException(plan.blockedReason());
        }
        if (plan.moves().isEmpty()) {
            throw new IllegalStateException("没有要搬的文件");
        }
        for (OtherMove move : plan.moves()) {
            if (move.blockedReason() != null) {
                throw new IllegalStateException(move.blockedReason());
            }
        }
        int moved = 0;
        for (OtherMove move : plan.moves()) {
            try {
                Files.move(Path.of(move.fromPath()), Path.of(move.toPath()));
                moved++;
            } catch (IOException e) {
                throw new IllegalStateException("搬「" + move.name() + "」失败：" + e.getMessage()
                        + "（已搬 " + moved + " 个，剩下的没动）", e);
            }
        }
        return moved;
    }

    /** 其他文件的搬动计划：整批级拦截原因 + 逐条的新旧路径。 */
    public record OtherMovePlan(String fromFolder, String toFolder, List<OtherMove> moves,
                                String blockedReason) {
    }

    /**
     * 一条搬动：{@code fromPath} → {@code toPath}。
     *
     * @param blockedReason 这条动不了的原因；{@code null} 表示可搬
     */
    public record OtherMove(String name, String fromPath, String toPath, String blockedReason) {
    }

    /** 本次没扫到、且目录已不在磁盘上的 ARCHIVED 行标为失踪。只标不删 */
    private int markArchivedMissing(Set<String> seenPaths) {
        List<MangaData> archived = mangaDataMapper.selectList(Wrappers.<MangaData>lambdaQuery()
                .eq(MangaData::getStatus, MangaDataStatus.ARCHIVED));
        int missing = 0;
        for (MangaData row : archived) {
            if (seenPaths.contains(row.getFolderPath())
                    || MangaCbzUtil.unitExists(Paths.get(row.getFolderPath()))) {
                continue;
            }
            row.setStatus(MangaDataStatus.MISSING);
            mangaDataMapper.updateById(row);
            missing++;
        }
        return missing;
    }

    private static class ScanCounters {
        int scanned;
        int inserted;
        int updated;
    }

    // ------------------------------------------------------------------
    // 归档漫画的单本操作：改名 / 评分 / 删除（阅读页与未归档页一致）
    // ------------------------------------------------------------------

    /**
     * 改归档漫画的目录名。只在原地改（同一归档目录下），不跨目录。
     * <p>归档漫画已入库，改名后 {@code manga_data.folder_path} 与封面要跟上，
     * 否则下次「扫描归档漫画」会把旧路径判成失踪、新路径当成新行。
     */
    public String renameManga(String folderPath, String newFolderName) {
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.PAGE, null, () -> {
            Path dir = requireArchivedManga(folderPath);
            String name = requireSimpleFolderName(newFolderName);
            Path target = dir.resolveSibling(name);
            if (target.equals(dir)) {
                return dir.toString();
            }
            if (Files.exists(target)) {
                throw new IllegalStateException("目标目录已存在：" + target);
            }
            // 判据必须在**搬动之前**取：搬完原路径已经不存在，Files.isDirectory 恒为 false
            // （2026-09-23 修 —— 原先写在 move 之后，这三处记录点一直是死代码）
            boolean isDir = Files.isDirectory(dir);
            try {
                Files.move(dir, target);
            } catch (IOException e) {
                throw new IllegalStateException("改名失败：" + e.getMessage(), e);
            }
            // 目录级动作才记：cbz 是文件（requireArchivedManga 对目录与 cbz 都放行），
            // 按**路径本身**判，不按方法名判
            if (isDir) {
                FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE,
                        FileOpLevel.SINGLE, "单本改名", dir.toString(), target.toString());
            }
            updateMangaPath(dir.toString(), target.toString());
            return target.toString();
        });
    }

    /**
     * 给归档漫画单独评分（{@code score_source = SELF}）。传 {@code null} 撤销，
     * 回到继承归档目录分（{@code INHERIT_ARCHIVE}）。
     * <p>与未归档「评分 = 分区间移动」不同，归档漫画的单独评分只落库不动目录 ——
     * 目录属于哪个评分分区由它所在的归档根决定，单本分只是人打的、覆盖继承分的那个值。
     */
    public void scoreManga(String folderPath, Integer score) {
        Path dir = requireArchivedManga(folderPath);
        MangaData row = mangaDataMapper.selectOne(Wrappers.<MangaData>lambdaQuery()
                .eq(MangaData::getFolderPath, dir.toString()));
        if (row == null) {
            throw new IllegalArgumentException("库里没有这本归档漫画，先跑「扫描归档漫画」："
                    + folderPath);
        }
        if (score == null) {
            MangaArchiveUnit unit = row.getArchiveUnitId() == null
                    ? null : unitMapper.selectById(row.getArchiveUnitId());
            row.setScore(unit == null ? null : unit.getScore());
            row.setScoreSource(MangaScoreSource.INHERIT_ARCHIVE);
        } else {
            row.setScore(requireValidScore(score));
            row.setScoreSource(MangaScoreSource.SELF);
        }
        mangaDataMapper.updateById(row);
    }

    /**
     * 删除一个归档漫画目录，连同里面的文件，并删掉 {@code manga_data} 行。
     * <p><b>送进回收站</b>（2026-09-30 起；原先是真的删掉），删错了去资源管理器里右键「还原」。
     */
    public void deleteManga(String folderPath) {
        FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.PAGE, null, () -> {
            Path dir = requireArchivedManga(folderPath);
            // 判据在删之前取（同 renameManga：删完原路径就没了，Files.isDirectory 已经是 false）
            boolean isDir = Files.isDirectory(dir);
            RecycleBin.recycle(dir);
            if (isDir) {
                // cbz 是文件，不算目录级动作（同 renameManga 的判据）
                FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.DELETE,
                        FileOpLevel.SINGLE, "删已归档的单本（进回收站）", dir.toString(), null);
            }
            mangaDataMapper.delete(Wrappers.<MangaData>lambdaQuery()
                    .eq(MangaData::getFolderPath, dir.toString()));
            return null;
        });
    }

    /**
     * 把一本归档漫画从当前目录移到另一个归档目录下（保持原名）。
     * <p>合并冲突页的「→ / ←」操作。磁盘先动、库后动：{@code Files.move} 成功后更新
     * {@code manga_data} 的 {@code folder_path}、{@code archive_unit_id} 与封面。
     * <p>评分继承随动（文档 4.6「继承来的评分会随目录移动而改变」）：{@code SELF}
     * 是人打的，保持不动；其余（{@code INHERIT_ARCHIVE} 或还没扫到行的）改记目标目录分。
     */
    @Transactional(rollbackFor = Exception.class)
    public String moveManga(String folderPath, Long toUnitId) {
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.PAGE, null, () -> {
            Path dir = requireArchivedManga(folderPath);
            MangaArchiveUnit to = requireUnit(toUnitId);
            if (to.getStatus() != MangaArchiveUnitStatus.ACTIVE) {
                throw new IllegalStateException("目标归档目录不是 ACTIVE，动不了：" + to.getFolderPath());
            }
            if (!FileUtil.isDirectory(to.getFolderPath())) {
                throw new IllegalStateException("目标归档目录不在磁盘上：" + to.getFolderPath());
            }
            Path target = Paths.get(to.getFolderPath()).resolve(dir.getFileName().toString());
            if (target.equals(dir)) {
                return dir.toString();
            }
            if (Files.exists(target)) {
                throw new IllegalStateException("目标目录已存在同名：" + target);
            }
            // 判据在搬之前取（同 renameManga/deleteManga）
            boolean isDir = Files.isDirectory(dir);
            try {
                Files.move(dir, target);
            } catch (IOException e) {
                throw new IllegalStateException("移动失败：" + e.getMessage(), e);
            }
            if (isDir) {
                FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE,
                        FileOpLevel.SINGLE, "单本换作者目录", dir.toString(), target.toString());
            }

            MangaData row = mangaDataMapper.selectOne(Wrappers.<MangaData>lambdaQuery()
                    .eq(MangaData::getFolderPath, dir.toString()));
            if (row != null) {
                row.setFolderPath(target.toString());
                row.setArchiveUnitId(to.getId());
                row.setCoverFile(MangaNameParser.getFirstImage(target));
                if (row.getScoreSource() != MangaScoreSource.SELF) {
                    row.setScore(to.getScore());
                    row.setScoreSource(MangaScoreSource.INHERIT_ARCHIVE);
                }
                mangaDataMapper.updateById(row);
            }
            return target.toString();
        });
    }

    /**
     * 合并完成：把已清空的归档目录（现扫<b>既无漫画、也无其他文件</b>）从磁盘删掉，
     * 连带库里的 unit 行。<b>磁盘目录进回收站</b>（2026-09-30 起）。
     * <p>「其他文件」也拦（2026-09-15，随 {@link #unitOthers} 一起）：整棵树是<b>一起</b>走的
     * （送回收站也是递归），底下剩着东西时这里是最后一次能拦住的机会 —— 让人先看见、自己决定搬到哪边。
     * <p>与 {@link #forgetUnit} 的分工：forgetUnit 处理「磁盘上已经没了」的失踪目录，
     * 这个方法处理「磁盘上还在、但合并后已经空了」的目录 —— 先删磁盘目录，再删行。
     * <p>不调 {@link #forgetUnit}：那是本类自调用，会绕过 Spring 的事务代理
     * （见 {@code MangaTagAdminService} 的同类警告），每个目录「删磁盘 + 删库」的
     * 原子性会悄悄失效，所以清理逻辑独立写在这里。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteEmptyUnit(Long unitId) {
        FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.PAGE, null, () -> {
            MangaArchiveUnit unit = requireUnit(unitId);
            if (!FileUtil.isDirectory(unit.getFolderPath())) {
                throw new IllegalStateException("目录已不在磁盘上，该走「确认已删除」而不是合并："
                        + unit.getFolderPath());
            }
            if (!unitMangas(unitId).isEmpty()) {
                throw new IllegalStateException("目录下还有漫画，先把它们移走再删："
                        + unit.getFolderPath());
            }
            // 「空」要真为空：送回收站也是整棵树一起走，底下还剩着别的文件时这里是最后一次
            // 能拦住的机会 —— 那些文件页面上列得出来（unitOthers），让人自己决定搬到哪边，
            // 别在一次「删空目录」里连它们一起静默删掉
            List<OtherEntry> others = unitOthers(unitId);
            if (!others.isEmpty()) {
                throw new IllegalStateException("目录下还有 " + others.size() + " 个其他文件（"
                        + others.stream().map(OtherEntry::name).limit(5)
                                .collect(Collectors.joining("、"))
                        + (others.size() > 5 ? " 等" : "")
                        + "），要么搬到另一边、要么自己在资源管理器里删掉，再来删这个目录："
                        + unit.getFolderPath());
            }
            RecycleBin.recycle(unit.getFolderPath());
            FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.DELETE,
                    FileOpLevel.AUTHOR, "删已清空的归档目录（进回收站）", unit.getFolderPath(), null);
            // 漫画指向一个即将不存在的 id，先摘掉（此时应为空，稳妥起见仍做）
            mangaDataMapper.update(null, Wrappers.<MangaData>lambdaUpdate()
                    .set(MangaData::getArchiveUnitId, null)
                    .eq(MangaData::getArchiveUnitId, unitId));
            mangaTagService.deleteRefs(MangaTagTargetType.ARCHIVE_UNIT, unitId);
            nameMapper.delete(Wrappers.<MangaArchiveName>lambdaQuery()
                    .eq(MangaArchiveName::getUnitId, unitId));
            unitMapper.deleteById(unitId);
            return null;
        });
    }

    /**
     * 归档目录改名 / 移动后，把库里指向它下面单本漫画的路径跟着改。
     *
     * <p>{@link Files#move} 搬的是整个归档目录，里面的单本漫画子目录物理上跟着走了，
     * 但 {@code manga_data.folder_path} 存的是<b>全路径</b>，不一起改就全部指向不存在的
     * 位置 —— 这个归档目录下的漫画会集体失联：列表查不到、封面裂图、随机抽选抽不中，
     * 而磁盘上文件一个没少，现象上很像"数据丢了"。下次同步也修不回来，
     * 只会把它们判成 MISSING。
     *
     * <p>只改 {@code folder_path}：{@code cover_file} 存的是文件名而非路径
     * （见 {@link MangaNameParser#getFirstImage}），不受目录改名影响。
     *
     * <p>不按 {@code archive_unit_id} 找行：那一列是后加的，历史行大量为 NULL
     * （见 实现说明 §4），拿它当依据会漏掉绝大多数漫画。路径前缀才是可靠的归属依据。
     */
    private void updateMangaPathsUnder(Path oldRoot, Path newRoot) {
        String oldPrefix = oldRoot + File.separator;
        for (MangaData row : listMangaUnder(oldRoot.toString())) {
            row.setFolderPath(newRoot.resolve(
                    row.getFolderPath().substring(oldPrefix.length())).toString());
            mangaDataMapper.updateById(row);
        }
    }

    /**
     * 某个归档目录下的全部单本漫画，按<b>路径前缀</b>取。
     * <p>归属判定不用 {@code archive_unit_id}：那一列是后加的、历史行大量为 NULL
     * （见 实现说明 §4），路径前缀才是可靠依据。
     */
    public List<MangaData> listMangaUnder(String folderPath) {
        String prefix = folderPath + File.separator;
        return mangaDataMapper.selectList(Wrappers.<MangaData>lambdaQuery()
                        .likeRight(MangaData::getFolderPath, MangaTextUtil.escapeLike(prefix)))
                .stream()
                .filter(r -> r.getFolderPath() != null && r.getFolderPath().startsWith(prefix))
                .toList();
    }

    /**
     * 改名后 folder_path、封面与解析出的 title/社团/作者等字段一起跟着新名字更新。
     * <p>改名 = 换了文件名，解析字段得按新名字重新算，口径与扫描归档的
     * {@link #upsertArchivedManga} 一致（解析出什么就覆盖什么）。新名字不合规解析不出
     * （{@code parseDir} 返回 {@code null}，如名字本身形如合集目录）时只改路径与封面，
     * 不动旧字段，不因一次改名把库里数据抹掉。
     */
    private void updateMangaPath(String oldPath, String newPath) {
        MangaData row = mangaDataMapper.selectOne(Wrappers.<MangaData>lambdaQuery()
                .eq(MangaData::getFolderPath, oldPath));
        if (row == null) {
            return;
        }
        Path newDir = Paths.get(newPath);
        MangaData parsed = new MangaNameParser(dictService.current()).parseDir(newDir);
        if (parsed != null) {
            row.setExhibit(parsed.getExhibit());
            row.setGroupName(parsed.getGroupName());
            row.setArtist(parsed.getArtist());
            row.setDateTag(parsed.getDateTag());
            row.setTitle(parsed.getTitle());
            row.setParody(parsed.getParody());
            row.setMagazine(parsed.getMagazine());
            row.setMatchedRule(parsed.getMatchedRule());
        }
        row.setFolderPath(newPath);
        row.setCoverFile(MangaNameParser.getFirstImage(newDir));
        mangaDataMapper.updateById(row);
    }

    /** 可就地编辑的归档漫画目录：必须落在归档根下且在磁盘上 */
    private Path requireArchivedManga(String folderPath) {
        if (StringUtils.isBlank(folderPath)) {
            throw new IllegalArgumentException("目录路径不能为空");
        }
        Path target = Paths.get(folderPath).toAbsolutePath().normalize();
        // 评分分区都在归档根下面，比归档根本身就够
        String archiveRoot = properties.getArchiveDir();
        boolean underRoot = StringUtils.isNotBlank(archiveRoot)
                && target.startsWith(Paths.get(archiveRoot).toAbsolutePath().normalize());
        if (!underRoot) {
            throw new IllegalArgumentException("不是归档根下的目录：" + folderPath);
        }
        if (!MangaCbzUtil.unitExists(target)) {
            throw new IllegalStateException("目录不在磁盘上：" + folderPath);
        }
        return target;
    }

    private static int requireValidScore(Integer score) {
        if (score == null) {
            throw new IllegalArgumentException("请先评分");
        }
        for (int valid : MangaScoreDir.SCORES) {
            if (valid == score) {
                return score;
            }
        }
        throw new IllegalArgumentException("评分只能是 3/5/7/9，收到 " + score);
    }

    // ------------------------------------------------------------------
    // 改目录名：页面上唯一能改归档信息的途径
    // ------------------------------------------------------------------

    /**
     * 只改归档目录名，其余按新目录名重新解析。标签重命名的连带改名走这里。
     * <p><b>文件系统是权威</b>：库里改了磁盘没改，下次 {@link #sync} 会原样覆盖回去，
     * 所以这里先动磁盘。新名字必须仍是 {@code [社团 (作者)]【标签】} 形态，否则下次
     * 同步会把它算进 {@code unparsedFolders} 而不再入库，等于把这个 unit 连同标签一起丢了。
     * <p>实现委托给 {@link #applyEdit}：那边已经处理了「改目录名要重新判定标签、
     * 已有关联作者不解绑」这些规则，两处各写一遍必然走岔。这里只负责把
     * 「新目录名」翻译成一次完整的编辑提交 —— 关联名沿用现有的（不解绑），
     * 标签取新目录名解析出来的那份，归档根不变。
     */
    public UnitView renameFolder(Long unitId, String newFolderName) {
        String newName = requireSimpleFolderName(newFolderName);
        ArchiveFolderInfo info = MangaNameParser.parseArchiveFolderName(newName);
        if (info == null || nameKeysOf(info).isEmpty()) {
            throw new IllegalArgumentException("新目录名不是 [社团 (作者)]【标签】 形态，"
                    + "这样改完下次同步就不会再入库了：" + newName);
        }
        // 新目录名里的社团/作者 + 原有的关联名（不解绑），后者会落成 MANUAL
        List<NameEdit> names = new ArrayList<>(namesOf(info));
        Set<String> keys = names.stream()
                .map(n -> MangaTextUtil.normalizeNameKey(n.rawName()))
                .collect(Collectors.toCollection(HashSet::new));
        for (NameEdit existing : listNameEdits(unitId)) {
            if (keys.add(MangaTextUtil.normalizeNameKey(existing.rawName()))) {
                names.add(existing);
            }
        }
        applyEdit(unitId, new UnitEditRequest(newName, names, info.tags(), null));

        MangaArchiveUnit unit = requireUnit(unitId);
        return new UnitView(unit, listTags(unitId), listNameEdits(unitId),
                countMangaByUnit(List.of(unitId)).getOrDefault(unitId, 0L),
                averageScoreByUnit(List.of(unitId), Map.of(unit.getId(), unit.getScore()))
                        .getOrDefault(unitId, 0.0),
                topTagsByUnit(List.of(unitId)).getOrDefault(unitId, List.of()),
                findConflicts().stream().anyMatch(c -> c.units().stream()
                        .anyMatch(u -> u.getId().equals(unitId))),
                FileUtil.isDirectory(unit.getFolderPath()));
    }

    /**
     * 目录名解析结果里的社团/作者，拆成表单用的关联名。
     *
     * <p><b>社团位与作者位都要按 {@code 、} 拆</b>：目录名的社团位是 {@code (} 之前的自由
     * 文本，多社团写成 {@code [社团甲、社团乙 (作者)]}，解析出来的 {@code groupName} 就是
     * {@code 社团甲、社团乙} 这一整串。一条关联名 = 一个社团，是 {@code manga_archive_name}
     * 的既有语义（{@code sync} 侧的 {@code insertNames} / {@code collectKeys} 一直这么拆），
     * 不拆的话：{@code normalizeNames} 会以「名字里不能有 、」直接拒掉整次编辑，
     * 多社团的归档目录改不了名；就算放过去，别名表里也会存成一条合并的键，
     * 归档匹配再也匹配不上其中任何一个社团。
     */
    private static List<NameEdit> namesOf(ArchiveFolderInfo info) {
        List<NameEdit> names = new ArrayList<>();
        addSplitNames(names, info.groupName(), MangaArchiveNameType.GROUP);
        addSplitNames(names, info.artistNames(), MangaArchiveNameType.ARTIST);
        return names;
    }

    /** 把目录名里按 {@code 、} 并列的一串名字拆成逐条关联名 */
    private static void addSplitNames(List<NameEdit> names, String rawNames,
                                      MangaArchiveNameType nameType) {
        if (StringUtils.isBlank(rawNames)) {
            return;
        }
        for (String raw : rawNames.split("、")) {
            if (StringUtils.isNotBlank(raw)) {
                names.add(new NameEdit(raw.trim(), nameType, MangaArchiveNameSource.FOLDER));
            }
        }
    }

    // ------------------------------------------------------------------
    // 归档编辑表单：目录名 / 关联作者 / 标签 / 评分分区，一次提交
    // ------------------------------------------------------------------

    /**
     * 一条关联名（社团或作者）在表单里的样子。
     *
     * @param source {@code FOLDER} 表示它来自目录名 —— 删改它会连带改目录名；
     *               {@code MANUAL} 表示额外关联，增删只动库
     */
    public record NameEdit(String rawName, MangaArchiveNameType nameType,
                           MangaArchiveNameSource source) {
    }

    /**
     * 归档编辑表单的一次提交。四类改动互相有牵连，故一起算、一起落。
     *
     * @param folderName 目标目录名。留空表示不直接改目录名，由其余字段推出；
     *                   填了则以它为基准，再叠加作者/标签的改动
     * @param names      提交后应有的全部关联名。缺了的就是删除，多出的就是新增
     * @param tags       提交后应有的全部标签，顺序即写进 {@code 【…】} 的顺序
     * @param rootPath   目标归档根（决定评分）。与当前不同则移动目录
     */
    public record UnitEditRequest(String folderName, List<NameEdit> names,
                                  List<String> tags, String rootPath) {
    }

    /**
     * 编辑表单的预演结果，提交前给人看的那份。
     *
     * @param blockedReason 非空表示提交会被拒，原因直接给人看
     * @param movedRoot     目录要挪到另一个归档根去（改评分分区）
     */
    /**
     * @param finalNames 提交后应有的全部关联名，{@code source} 已判定好。
     *                   {@link #applyEdit} 直接落这一份，避免与预演给人看的那份分歧
     */
    public record UnitEditPlan(Long unitId, String fromFolderPath, String toFolderPath,
                              String fromFolderName, String toFolderName,
                              List<NameEdit> finalNames,
                              List<NameEdit> addedNames, List<NameEdit> removedNames,
                              List<String> fromTags, List<String> toTags,
                              Integer fromScore, Integer toScore, boolean movedRoot,
                              String blockedReason) {

        public boolean folderRenamed() {
            return !fromFolderName.equals(toFolderName);
        }
    }

    /**
     * 算出一次编辑提交的后果，只读。
     * <p>四类改动的相互作用是这里唯一的复杂之处，规则来自需求：
     * <ul>
     *     <li><b>改目录名</b> —— 目录名变了要重新解析标签与社团/作者；解析不出归档形态
     *         则拒绝提交。已有的关联作者<b>不解绑</b>，那些不在新目录名里的会转成
     *         {@link MangaArchiveNameSource#MANUAL} 留下来。</li>
     *     <li><b>增加关联</b> —— 不进目录名，落成 {@code MANUAL}。</li>
     *     <li><b>删改关联</b> —— 若该名字在目录名里（{@code FOLDER} 来源），
     *         连带改目录名；删到目录名里没有作者了就拒绝提交，因为那样拼不出合法目录名。</li>
     *     <li><b>增删改标签</b> —— 一律同步进目录名的 {@code 【…】} 块。</li>
     *     <li><b>改评分分区</b> —— 把目录移到目标归档根下，目录名不变。</li>
     * </ul>
     */
    public UnitEditPlan planEdit(Long unitId, UnitEditRequest request) {
        MangaArchiveUnit unit = requireUnit(unitId);
        if (request == null) {
            throw new IllegalArgumentException("提交内容为空");
        }
        List<String> toTags = normalizeTags(request.tags());
        List<NameEdit> submitted = normalizeNames(request.names());

        // 基准目录名：用户直接改了就以它为准，否则沿用现有的
        String baseName = StringUtils.defaultIfBlank(
                requireSimpleFolderNameOrNull(request.folderName()), unit.getFolderName());
        ArchiveFolderInfo baseInfo = MangaNameParser.parseArchiveFolderName(baseName);
        if (baseInfo == null) {
            return blocked(unit, baseName, submitted, toTags,
                    "目录名不是 [社团 (作者)]【标签】 形态，这样改完下次同步就不会再入库了："
                            + baseName);
        }

        Set<String> submittedKeys = submitted.stream()
                .map(n -> MangaTextUtil.normalizeNameKey(n.rawName()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 目录名的社团/作者块由「该进目录名的那些关联」重建。判据是每条关联自己的
        // source：FOLDER 表示要出现在目录名里，MANUAL 表示只存库。这样改名（把某条
        // FOLDER 关联的文字改掉）能落到目录名上 —— 若改成按旧目录名过滤，
        // 改过字的那条会因为对不上旧键而被当成删除，目录名里凭空少一个人。
        // 用户直接改了目录名时以目录名为准：那时目录名本身就是最明确的表达。
        boolean folderNameGiven = StringUtils.isNotBlank(request.folderName());
        String keptGroup;
        String keptArtists;
        if (folderNameGiven) {
            keptGroup = baseInfo.groupName();
            keptArtists = baseInfo.artistNames();
        } else {
            keptGroup = joinNames(submitted, MangaArchiveNameType.GROUP);
            keptArtists = joinNames(submitted, MangaArchiveNameType.ARTIST);
        }
        if (StringUtils.isBlank(keptGroup) && StringUtils.isBlank(keptArtists)) {
            return blocked(unit, baseName, submitted, toTags,
                    "删掉这些关联后目录名里既没有社团也没有作者，拼不出合法的归档目录名");
        }
        // [社团 ()] 不成形态：社团还在但作者全删了，把社团降格到作者位
        if (StringUtils.isBlank(keptArtists)) {
            keptArtists = keptGroup;
            keptGroup = null;
        }

        String toName = MangaNameParser.buildArchiveFolderName(keptGroup, keptArtists, toTags);
        if (toName == null || MangaNameParser.parseArchiveFolderName(toName) == null) {
            return blocked(unit, baseName, submitted, toTags,
                    "按提交内容拼不出合法的归档目录名");
        }

        String toRoot = StringUtils.defaultIfBlank(
                StringUtils.trimToNull(request.rootPath()), unit.getRootPath());
        Integer toScore = MangaScoreDir.rootScoreMap(properties.getArchiveDir()).get(toRoot);
        if (toScore == null) {
            return blocked(unit, baseName, submitted, toTags, "不是已知的归档根：" + toRoot);
        }
        boolean movedRoot = !toRoot.equals(unit.getRootPath());
        Path toPath = Paths.get(toRoot).resolve(toName);

        // 提交后的别名清单：以最终目录名为准判来源 —— 在目录名里的记 FOLDER，其余记
        // MANUAL。这样「改目录名时被挤出目录名、但按需求不解绑」的那些会自动落成 MANUAL，
        // 不必在别处特判
        ArchiveFolderInfo toInfo = MangaNameParser.parseArchiveFolderName(toName);
        Set<String> toFolderKeys = nameKeysOf(toInfo);
        List<NameEdit> finalNames = new ArrayList<>(submitted.stream()
                .map(n -> new NameEdit(n.rawName(), n.nameType(),
                        toFolderKeys.contains(MangaTextUtil.normalizeNameKey(n.rawName()))
                                ? MangaArchiveNameSource.FOLDER : MangaArchiveNameSource.MANUAL))
                .toList());
        // 直接改目录名时可能引入清单里没有的名字，那些也要成为别名 ——
        // 否则目录名里写着这个作者，库里却查不到他，归档匹配会漏掉
        for (NameEdit fromFolder : namesOf(toInfo)) {
            if (!submittedKeys.contains(MangaTextUtil.normalizeNameKey(fromFolder.rawName()))) {
                finalNames.add(fromFolder);
            }
        }

        List<NameEdit> existing = listNameEdits(unitId);
        Set<String> existingKeys = existing.stream()
                .map(n -> MangaTextUtil.normalizeNameKey(n.rawName()))
                .collect(Collectors.toSet());
        List<NameEdit> added = finalNames.stream()
                .filter(n -> !existingKeys.contains(MangaTextUtil.normalizeNameKey(n.rawName())))
                .toList();
        List<NameEdit> removed = existing.stream()
                .filter(n -> !submittedKeys.contains(MangaTextUtil.normalizeNameKey(n.rawName())))
                .toList();

        String blockedReason = null;
        boolean pathChanged = !toPath.equals(Paths.get(unit.getFolderPath()));
        if (pathChanged) {
            if (!FileUtil.isDirectory(unit.getFolderPath())) {
                blockedReason = "目录不在磁盘上（"
                        + (unit.getStatus() == MangaArchiveUnitStatus.MISSING ? "失踪" : "未同步")
                        + "），动不了：" + unit.getFolderPath();
            } else if (toPath.toFile().exists()) {
                blockedReason = "目标目录已存在，先手工合并：" + toPath;
            } else if (movedRoot && !FileUtil.isDirectory(toRoot)) {
                blockedReason = "目标归档根不在磁盘上：" + toRoot;
            }
        }

        return new UnitEditPlan(unitId, unit.getFolderPath(), toPath.toString(),
                unit.getFolderName(), toName, List.copyOf(finalNames), added, removed,
                listTags(unitId), toTags, unit.getScore(), toScore, movedRoot, blockedReason);
    }

    /**
     * 提交编辑：先动磁盘（改名/移动），再把库更新到与磁盘一致。
     * <p>顺序不能反 —— 库先改完而磁盘失败，库里就指着一个不存在的路径；
     * 反过来磁盘先成功、库更新失败时事务回滚，下次同步会按「改名」把这个目录认回来
     * （别名集合相同、旧路径已不在），标签不丢。
     * <p><b>只在这一步落盘</b>：{@link #planEdit} 全程只读，表单上的改动在提交前
     * 都只活在前端。
     */
    @Transactional(rollbackFor = Exception.class)
    public UnitEditPlan applyEdit(Long unitId, UnitEditRequest request) {
        // 批次包在方法上：直接调（归档编辑表单 / renameFolder）时它就是批次；
        // 被标签连带改名（applyRewrites 的外层批次）调时外层赢 —— 一次标签操作一个批次
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.PAGE, null, () -> {
            UnitEditPlan plan = planEdit(unitId, request);
            if (plan.blockedReason() != null) {
                throw new IllegalStateException(plan.blockedReason());
            }
            MangaArchiveUnit unit = requireUnit(unitId);

            Path oldPath = Paths.get(plan.fromFolderPath());
            Path newPath = Paths.get(plan.toFolderPath());
            if (!oldPath.equals(newPath)) {
                if (!FileUtil.isDirectory(oldPath.toFile())) {
                    throw new IllegalStateException("原目录不在磁盘上：" + oldPath);
                }
                if (newPath.toFile().exists()) {
                    throw new IllegalStateException("目标目录已存在：" + newPath);
                }
                try {
                    Files.move(oldPath, newPath);
                } catch (IOException e) {
                    // 跨盘移动会抛，本项目四个归档根同盘，真出现了原因得让人看见；
                    // 不同异常类型给不同的提示，比把 e.getMessage() 原样甩出来好查
                    String verb = plan.movedRoot() ? "移动" : "重命名";
                    if (e instanceof FileAlreadyExistsException) {
                        throw new IllegalStateException(verb + "目录失败：目标已存在：" + newPath, e);
                    }
                    if (e instanceof DirectoryNotEmptyException) {
                        throw new IllegalStateException(verb + "目录失败：目标目录非空：" + newPath, e);
                    }
                    if (e instanceof AccessDeniedException) {
                        throw new IllegalStateException(verb + "目录失败：没有权限，可能有进程占用：" + newPath, e);
                    }
                    throw new IllegalStateException(verb + "目录失败：" + e.getMessage(), e);
                }
                // 「整体移动作者文件夹」在代码里就是这一次 Files.move，天然只记一条
                FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE,
                        FileOpLevel.AUTHOR, "改归档目录名", oldPath.toString(), newPath.toString());
                updateMangaPathsUnder(oldPath, newPath);
            }

        ArchiveFolderInfo info = MangaNameParser.parseArchiveFolderName(plan.toFolderName());
        fillUnit(unit, newPath.toString(), plan.toFolderName(),
                StringUtils.defaultIfBlank(StringUtils.trimToNull(request.rootPath()),
                        unit.getRootPath()),
                plan.toScore(), info);
        unitMapper.updateById(unit);

        // 改了评分分区（分数跟着变）后，把继承归档目录分的漫画分同步到新分；
        // SELF（人打的）不动。按路径前缀而非 archive_unit_id 找行：那一列是后加的、
        // 历史行大量为 NULL（见 updateMangaPathsUnder 的说明），路径前缀才可靠。
        if (!Objects.equals(plan.fromScore(), plan.toScore())) {
            for (MangaData row : listMangaUnder(plan.toFolderPath())) {
                if (row.getScoreSource() == MangaScoreSource.INHERIT_ARCHIVE) {
                    row.setScore(plan.toScore());
                    mangaDataMapper.updateById(row);
                }
            }
        }

        // 别名整组重写：目录名派生的与手工的一起，因为表单提交的是「最终应有的全部关联」。
        // 落 plan.finalNames() 而不是再从 request 推一遍 —— 推两遍就会与预演给人看的
        // 那份出现分歧，而分歧的那次已经改完了磁盘
        nameMapper.delete(Wrappers.<MangaArchiveName>lambdaQuery()
                .eq(MangaArchiveName::getUnitId, unitId));
        Set<String> done = new HashSet<>();
        for (NameEdit edit : plan.finalNames()) {
            String key = MangaTextUtil.normalizeNameKey(edit.rawName());
            if (key == null || !done.add(key)) {
                continue;
            }
            MangaArchiveName name = new MangaArchiveName();
            name.setUnitId(unitId);
            name.setName(key);
            name.setRawName(edit.rawName().trim());
            name.setNameType(edit.nameType());
            name.setNameSource(edit.source());
            nameMapper.insert(name);
        }
        mangaTagService.replaceRefs(MangaTagTargetType.ARCHIVE_UNIT, unitId, info.tags());
            return plan;
        });
    }

    /** 表单初值：该 unit 现有的全部关联名 */
    public List<NameEdit> listNameEdits(Long unitId) {
        return nameMapper.selectList(Wrappers.<MangaArchiveName>lambdaQuery()
                        .eq(MangaArchiveName::getUnitId, unitId)
                        .orderByAsc(MangaArchiveName::getNameType)
                        .orderByAsc(MangaArchiveName::getId))
                .stream()
                .map(n -> new NameEdit(n.getRawName(), n.getNameType(),
                        n.getNameSource() == null
                                ? MangaArchiveNameSource.FOLDER : n.getNameSource()))
                .toList();
    }

    /**
     * 把该进目录名的关联名按 {@code 、} 连起来，充当目录名的社团位或作者位。
     * <p>只取 {@link MangaArchiveNameSource#FOLDER} 的：{@code MANUAL} 那些按需求
     * 不进目录名。社团位与作者位都可以有多个（{@code [社团甲、社团乙 (作者甲、作者乙)]}）
     * —— 社团位是 {@code (} 之前的自由文本，解析侧的 {@code nameKeysOf} 与
     * {@code insertNames} 本来就按 {@code 、} 拆，所以不限一个。
     */
    private static String joinNames(List<NameEdit> names, MangaArchiveNameType type) {
        List<String> kept = names.stream()
                .filter(n -> n.nameType() == type)
                .filter(n -> n.source() != MangaArchiveNameSource.MANUAL)
                .map(n -> n.rawName().trim())
                .toList();
        return kept.isEmpty() ? null : String.join("、", kept);
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String tag : tags) {
            String name = StringUtils.trimToNull(tag);
            if (name == null) {
                continue;
            }
            // 标签要能原样写进【】块，空白会被同步时按空白拆成两个标签
            MangaTagService.requireTagName(name);
            if (result.stream().noneMatch(t -> t.equalsIgnoreCase(name))) {
                result.add(name);
            }
        }
        return result;
    }

    private static List<NameEdit> normalizeNames(List<NameEdit> names) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("至少要留一个关联的社团或作者");
        }
        List<NameEdit> result = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (NameEdit edit : names) {
            String raw = edit == null ? null : StringUtils.trimToNull(edit.rawName());
            if (raw == null) {
                continue;
            }
            if (StringUtils.containsAny(raw, '、', '[', ']', '(', ')', '【', '】')) {
                throw new IllegalArgumentException(
                        "社团名/作者名里不能有 、[]()【】 —— 它们是目录名的结构字符：" + raw);
            }
            String key = MangaTextUtil.normalizeNameKey(raw);
            if (key == null || !done.add(key)) {
                continue;
            }
            // source 缺省按 FOLDER：表单里新加的「额外关联」会显式带 MANUAL，
            // 而没带 source 的多半是外部直接调接口，那时「进目录名」是更符合直觉的默认
            result.add(new NameEdit(raw, edit.nameType() == null
                    ? MangaArchiveNameType.ARTIST : edit.nameType(),
                    edit.source() == null ? MangaArchiveNameSource.FOLDER : edit.source()));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("至少要留一个关联的社团或作者");
        }
        // 社团不限一个：目录名的社团位是 ( 之前的自由文本，多社团按 、 分隔写在一起
        // （[社团甲、社团乙 (作者)]），解析侧的 nameKeysOf 与 insertNames 本来就按 、 拆
        return result;
    }

    /** 拒绝提交时也把算到一半的结果给回去，页面好显示「原本要改成什么」 */
    private UnitEditPlan blocked(MangaArchiveUnit unit, String toName, List<NameEdit> names,
                                List<String> toTags, String reason) {
        return new UnitEditPlan(unit.getId(), unit.getFolderPath(), unit.getFolderPath(),
                unit.getFolderName(), toName, names, List.of(), List.of(),
                listTags(unit.getId()), toTags, unit.getScore(), unit.getScore(), false, reason);
    }

    private static String requireSimpleFolderNameOrNull(String folderName) {
        return StringUtils.isBlank(folderName) ? null : requireSimpleFolderName(folderName);
    }

    /**
     * 「确认已删除」：把 MISSING 的归档目录从库里清掉，连带别名与标签关联。
     * <p>与 {@link #markMissing} 的分工：同步只标状态不删行，因为目录可能只是被临时移走
     * （外挂盘没挂上），删了就把标签关联一起丢了。什么时候真删由人来判断，就是这个方法。
     * <p>目录还在磁盘上时拒绝执行 —— 那说明它没失踪，该做的是重新同步。
     */
    @Transactional(rollbackFor = Exception.class)
    public void forgetUnit(Long unitId) {
        MangaArchiveUnit unit = requireUnit(unitId);
        if (FileUtil.isDirectory(unit.getFolderPath())) {
            throw new IllegalStateException("目录还在磁盘上，不该删库里的行："
                    + unit.getFolderPath() + "。要清掉它请先重新同步");
        }
        // 漫画指向的是一个即将不存在的 id，先摘掉，否则会挂成孤儿
        mangaDataMapper.update(null, Wrappers.<MangaData>lambdaUpdate()
                .set(MangaData::getArchiveUnitId, null)
                .eq(MangaData::getArchiveUnitId, unitId));
        mangaTagService.deleteRefs(MangaTagTargetType.ARCHIVE_UNIT, unitId);
        nameMapper.delete(Wrappers.<MangaArchiveName>lambdaQuery()
                .eq(MangaArchiveName::getUnitId, unitId));
        unitMapper.deleteById(unitId);
    }

    // ------------------------------------------------------------------
    // 标签改动连带的目录改名
    // ------------------------------------------------------------------

    /**
     * 一个归档目录因标签改动而要做的改名。
     *
     * @param blockedReason 不为空表示这条做不了，原因直接给人看；此时整批不执行
     */
    public record FolderRewrite(Long unitId, String folderPath, String fromFolderName,
                                String toFolderName, String blockedReason) {
    }

    /**
     * 算出「把标签 {@code tagName} 换成 {@code replacement}」要改哪些目录名。
     * <p>标签存在库里，但<b>权威是目录名里的 {@code 【…】} 块</b>：只改库不改目录，
     * 下次 {@link #sync} 会照着目录名把旧标签建回来。所以标签的重命名/合并/删除
     * 必须连带改目录名，这个方法算的就是那份清单，供页面在动手前过目。
     * <p>新名字怎么拼见 {@link MangaNameParser#replaceArchiveTag} —— 只动
     * {@code 【…】} 块，且本目录的其它标签保留目录里的原文。
     *
     * @param replacement 新标签名；传 {@code null} 表示从目录名里去掉该标签
     */
    public List<FolderRewrite> planTagRewrite(String tagName, String replacement) {
        // HashMap 而不是 Map.of：replacement 为 null（删标签）是合法入参，Map.of 不收 null 值
        Map<String, String> single = new LinkedHashMap<>();
        single.put(tagName, replacement);
        return planTagRewrite(single);
    }

    /**
     * 批量版：一次把多个标签换掉，<b>同一个目录只产出一条改名</b>。
     *
     * <p>逐个标签调单条版再合并是不行的：一个目录名里有三个词要换，就会被
     * {@code Files.move} 三次，而 move 是这里唯一不可回滚的动作。所以按目录聚合，
     * 一个目录把所有命中的词一次换完。
     *
     * <p>页面上的单标签改名走的是它（{@link #planTagRewrite(String, String)} 也并到这一份实现里）——
     * 两个 {@code blockedReason} 判据、目录名解析失败的处置、{@link FolderRewrite} 的构造
     * 只写一遍，否则两处迟早走岔。原先还有个一次性批量迁移脚本与它共用这里，那个脚本
     * 已随迁移完成删除（2026-09-15），批量入口留着：一次换多个词仍然要按目录聚合。
     *
     * @param replacements 旧标签名 → 新标签名；值为 {@code null} 表示从目录名里去掉该标签
     */
    public List<FolderRewrite> planTagRewrite(Map<String, String> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            return List.of();
        }
        Set<Long> unitIds = new LinkedHashSet<>();
        for (String tagName : replacements.keySet()) {
            if (MangaTextUtil.normalizeNameKey(tagName) == null) {
                throw new IllegalArgumentException("标签名不能为空");
            }
            unitIds.addAll(mangaTagService.listTargetIds(MangaTagTargetType.ARCHIVE_UNIT, tagName));
        }
        if (unitIds.isEmpty()) {
            return List.of();
        }

        List<FolderRewrite> plan = new ArrayList<>();
        // 本批已占用的目标目录名（大写键），用来在动手前拦住「两条改名落到同一目标名」的冲突
        Map<String, MangaArchiveUnit> plannedTargets = new HashMap<>();
        for (MangaArchiveUnit unit : unitMapper.selectByIds(unitIds)) {
            String from = unit.getFolderName();
            String to = from;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                to = MangaNameParser.replaceArchiveTag(to, entry.getKey(), entry.getValue());
                if (to == null) {
                    break;
                }
            }
            if (to == null) {
                plan.add(new FolderRewrite(unit.getId(), unit.getFolderPath(), from, null,
                        "目录名解析不出社团/作者，先手工改名"));
                continue;
            }
            String blocked = null;
            if (!FileUtil.isDirectory(unit.getFolderPath())) {
                blocked = "目录不在磁盘上（"
                        + (unit.getStatus() == MangaArchiveUnitStatus.MISSING ? "失踪" : "未同步") + "）";
            } else if (!from.equals(to)
                    && Paths.get(unit.getFolderPath()).resolveSibling(to).toFile().exists()) {
                blocked = "目标目录已存在：" + to;
            }
            if (blocked == null && !from.equals(to)) {
                // 批内目标冲突：另一条本批改名已占用同一目标名，逐条检查拦不住它，
                // 因为目标此刻还没被建出来。按父目录+目标名做 Windows 大小写不敏感比较。
                Path parent = Paths.get(unit.getFolderPath()).getParent();
                String target = (parent == null ? "" : parent.toString() + File.separator) + to;
                MangaArchiveUnit prev = plannedTargets.putIfAbsent(target.toUpperCase(), unit);
                if (prev != null) {
                    blocked = "改名后与同批另一个目录重名：" + to
                            + "（" + prev.getFolderName() + " 也改成它）";
                }
            }
            plan.add(new FolderRewrite(unit.getId(), unit.getFolderPath(), from, to, blocked));
        }
        return plan;
    }

    /**
     * 执行 {@link #planTagRewrite} 的清单前的把关。
     * <p>存在 {@code blockedReason} 的条目一律拒绝整批执行：漏改一个目录，下次同步就会
     * 把旧标签建回来，出现「改过了又冒出来」这种最难查的现象。宁可让人先把那个目录处理掉。
     * <p>真正的逐个改名在 {@code MangaTagAdminService} 里做，不放在本类：
     * 那样就成了 {@link #renameFolder} 的自调用，Spring 的事务代理会被绕过，
     * 每个目录「改磁盘 + 更新库」的原子性会悄悄失效。
     */
    public void checkTagRewritable(List<FolderRewrite> plan) {
        List<FolderRewrite> blocked = plan.stream()
                .filter(r -> r.blockedReason() != null)
                .toList();
        if (!blocked.isEmpty()) {
            throw new IllegalStateException("有 " + blocked.size() + " 个目录改不了，"
                    + "整批未执行：" + blocked.getFirst().fromFolderName()
                    + " —— " + blocked.getFirst().blockedReason());
        }
    }

    /**
     * 换掉目录名末尾的 {@code 【…】} 标签块，前面的部分原样保留。
     * <p>{@code parseArchiveFolderName} 保证归档目录名至多两个顶层节点、第二个必是
     * {@code 【】}，所以按最后一个 {@code 【} 切开就能把社团块完整留下。
     */
    private static String withTags(String folderName, List<String> tags) {
        String trimmed = folderName.trim();
        int cn = trimmed.lastIndexOf('【');
        String prefix = cn >= 0 && trimmed.endsWith("】") ? trimmed.substring(0, cn) : trimmed;
        if (tags.isEmpty()) {
            // 标签块没了，原来 ] 与 【 之间的那个空格会变成结尾空格，Windows 目录名不能留
            return StringUtils.stripEnd(prefix, null);
        }
        return prefix + "【" + String.join(" ", tags) + "】";
    }

    private MangaArchiveUnit requireUnit(Long unitId) {
        MangaArchiveUnit unit = unitId == null ? null : unitMapper.selectById(unitId);
        if (unit == null) {
            throw new IllegalArgumentException("归档目录不存在: " + unitId);
        }
        return unit;
    }

    /** 目录名而非路径：判据见 {@link MangaFolderName#requireSimple}（三处改名共用那一份） */
    private static String requireSimpleFolderName(String folderName) {
        return MangaFolderName.requireSimple(folderName);
    }

    /** 全部归档目录及其标签，供列表页一次取全 */
    public Map<MangaArchiveUnit, List<String>> listUnitsWithTags(MangaArchiveUnitStatus status) {
        List<MangaArchiveUnit> units = unitMapper.selectList(Wrappers.<MangaArchiveUnit>lambdaQuery()
                .eq(status != null, MangaArchiveUnit::getStatus, status)
                .orderByAsc(MangaArchiveUnit::getRootPath)
                .orderByAsc(MangaArchiveUnit::getFolderName));
        if (units.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> tagsByUnit = mangaTagService.listTagNamesBatch(
                MangaTagTargetType.ARCHIVE_UNIT,
                units.stream().map(MangaArchiveUnit::getId).toList());
        Map<MangaArchiveUnit, List<String>> result = new LinkedHashMap<>();
        units.forEach(u -> result.put(u, tagsByUnit.getOrDefault(u.getId(), List.of())));
        return result;
    }
}
