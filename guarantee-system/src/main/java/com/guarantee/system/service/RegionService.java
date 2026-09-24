package com.guarantee.system.service;

import com.guarantee.common.exception.BizException;
import com.guarantee.system.entity.SysRegion;
import com.guarantee.system.mapper.SysRegionMapper;
import com.guarantee.system.vo.RegionOptionVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 行政区划字典服务（地区基础信息）。
 *
 * <p>依据 {@code docs/REQ-地区基础信息与区域筛选下拉.md}：把"区域编码"从自由文本输入
 * 换成地区下拉，并让机构写入按字典校验。数据由 {@code db/seed/region.sql} 导入
 * （省级 34 + 市级 342），表结构见 {@code db/schema.sql} 的 {@code sys_region}。</p>
 *
 * <p><b>两种口径，默认给"全量"</b>（产品口径：下拉要看到整本字典）：</p>
 * <ul>
 *   <li>{@code listOptions(..., onlyWithData = false)}（默认）：全部启用地区——省/市/区县都能选；</li>
 *   <li>{@code listOptions(..., onlyWithData = true)}：只给"当前有业务数据"的地区，
 *       用于"下拉里出现的每一项都能筛出结果"的分析/对账场景。</li>
 * </ul>
 */
@Service
public class RegionService {

    private static final Logger log = LoggerFactory.getLogger(RegionService.class);

    /** "有业务数据"集合的缓存时长：订单只读、地区极少变动，没必要每次请求扫 5 张表。 */
    private static final long USED_CODES_TTL_MS = 5 * 60 * 1000L;

    private final SysRegionMapper sysRegionMapper;

    /** 缓存：业务数据里出现过的区划码。volatile + 整体替换，读多写少不需要锁。 */
    private volatile Set<String> usedCodesCache = Set.of();
    private volatile long usedCodesCachedAt = 0L;

    public RegionService(SysRegionMapper sysRegionMapper) {
        this.sysRegionMapper = sysRegionMapper;
    }

    /**
     * 地区下拉选项。
     *
     * @param level        层级（1 省 / 2 市 / 3 区县）；null = 不限层级
     * @param parentCode   上级区划码；空 = 不限上级
     * @param onlyWithData true = 只返回"当前有业务数据"的地区（筛选口径，推荐）
     */
    @Transactional(readOnly = true)
    public List<RegionOptionVO> listOptions(Integer level, String parentCode, boolean onlyWithData) {
        List<SysRegion> rows = sysRegionMapper.selectByLevel(level, parentCode);
        if (!onlyWithData) {
            return rows.stream().map(RegionOptionVO::of).toList();
        }
        Set<String> used = usedRegionCodes();
        return rows.stream()
                .filter(row -> used.contains(row.getCode()))
                .map(RegionOptionVO::of)
                .toList();
    }

    /** 业务数据里出现过的区划码（带 TTL 缓存）。 */
    public Set<String> usedRegionCodes() {
        long now = System.currentTimeMillis();
        Set<String> cached = usedCodesCache;
        if (!cached.isEmpty() && now - usedCodesCachedAt < USED_CODES_TTL_MS) {
            return cached;
        }
        Set<String> fresh = new LinkedHashSet<>(sysRegionMapper.selectUsedRegionCodes());
        usedCodesCache = Set.copyOf(fresh);
        usedCodesCachedAt = now;
        log.debug("刷新「有业务数据」的地区集合：{} 个", fresh.size());
        return usedCodesCache;
    }

    /** 测试/运维用：手工失效缓存（导入数据或改了业务数据后调用）。 */
    public void invalidateUsedCodesCache() {
        usedCodesCache = Set.of();
        usedCodesCachedAt = 0L;
    }

    /**
     * 机构写入用：把"区划码或名称"规范成一个**启用中**的地区（省 / 市 / 区县都接受）。
     *
     * <p>两条约束与理由：</p>
     * <ol>
     *   <li>码必须存在于字典（或能用全称/简称解析出来）——这是"作为字典"的核心价值，
     *       否则助手/页面又能写进一个不存在的区划码；</li>
     *   <li>必须启用：停用的地区不该被新业务使用。</li>
     * </ol>
     *
     * <p><b>不再限制层级</b>（原实现要求省级）：机构可以精确到市/区县。
     * 筛选侧已同步改成层级前缀匹配（{@code RegionCodePrefix}），
     * 因此"选省"仍能筛出挂在市/区县码上的机构与订单，不会漏数据。</p>
     *
     * @return 规范化后的地区实体（调用方用 {@code getCode()} / {@code getName()}）
     */
    @Transactional(readOnly = true)
    public SysRegion requireEnabledRegion(String codeOrName) {
        String token = codeOrName == null ? "" : codeOrName.trim();
        if (token.isEmpty()) {
            throw BizException.badRequest("行政区划不能为空（可用地区见 /api/system/regions/options）");
        }
        SysRegion region = sysRegionMapper.selectByCode(token);
        if (region == null) {
            // 助手经常给名称而不是编码，这里做一次规范化，避免"名称对但被拒"
            region = sysRegionMapper.selectByNameOrShortName(token);
        }
        if (region == null) {
            throw BizException.badRequest("行政区划不存在：" + token
                    + "（只能使用系统地区字典里的地区，例如 330000、330100 或 浙江省；"
                    + "可用地区见 /api/system/regions/options）");
        }
        if (!Integer.valueOf(1).equals(region.getStatus())) {
            throw BizException.badRequest("行政区划已停用：" + region.getName() + "（" + region.getCode() + "）");
        }
        return region;
    }
}
