package com.guarantee.system.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import com.guarantee.system.dto.InsuranceTypeDto;
import com.guarantee.system.entity.InsuranceType;
import com.guarantee.system.mapper.InsuranceTypeMapper;
import com.guarantee.system.vo.InsuranceTypeVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 险种配置服务。
 *
 * <p>这是本项目的分层样板：Controller -> Service -> Mapper -> DB。
 * Service 负责校验与实体/VO 转换，对外只暴露 VO 与 DTO。</p>
 */
@Service
public class InsuranceTypeService {

    private static final Logger log = LoggerFactory.getLogger(InsuranceTypeService.class);

    private static final Map<String, String> CATEGORY_NAMES = Map.of(
            "TENDER", "投标保函",
            "PERFORMANCE", "履约保函",
            "OTHER", "其他");

    private final InsuranceTypeMapper insuranceTypeMapper;
    private final WebAuditor webAuditor;

    public InsuranceTypeService(InsuranceTypeMapper insuranceTypeMapper, WebAuditor webAuditor) {
        this.insuranceTypeMapper = insuranceTypeMapper;
        this.webAuditor = webAuditor;
    }

    @Transactional(readOnly = true)
    public PageResult<InsuranceTypeVO> page(InsuranceTypeDto.Query query) {
        long total = insuranceTypeMapper.countByQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<InsuranceTypeVO> list = insuranceTypeMapper
                .selectPage(query, query.offset(), query.getPageSize())
                .stream()
                .map(InsuranceTypeService::toVO)
                .toList();
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    @Transactional(readOnly = true)
    public List<InsuranceTypeVO> listAllEnabled() {
        return insuranceTypeMapper.selectAllEnabled().stream()
                .map(InsuranceTypeService::toVO)
                .toList();
    }

    @Transactional(readOnly = true)
    public InsuranceTypeVO getById(Long id) {
        InsuranceType entity = insuranceTypeMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("险种不存在: " + id);
        }
        return toVO(entity);
    }

    /** 按主键读险种实体（写操作预检与确认卡需要读原值）。 */
    @Transactional(readOnly = true)
    public InsuranceType getEntityById(Long id) {
        InsuranceType entity = insuranceTypeMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("险种不存在: " + id);
        }
        return entity;
    }

    @Transactional
    public InsuranceTypeVO create(InsuranceTypeDto.CreateRequest request) {
        validateCreate(request);
        InsuranceType entity = new InsuranceType();
        entity.setTypeCode(request.getTypeCode().trim());
        entity.setTypeName(request.getTypeName());
        entity.setCategory(request.getCategory());
        entity.setBaseRate(request.getBaseRate());
        entity.setMinAmount(request.getMinAmount());
        entity.setMaxAmount(request.getMaxAmount());
        entity.setStatus(1);
        entity.setDescription(request.getDescription());

        insuranceTypeMapper.insert(entity);
        log.info("新增险种成功 id={} code={}", entity.getId(), entity.getTypeCode());

        // 页面直连审计（SYS-A-07 / AC-22）
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("typeCode", entity.getTypeCode());
        after.put("typeName", entity.getTypeName());
        after.put("category", entity.getCategory());
        after.put("baseRate", entity.getBaseRate() == null ? null : entity.getBaseRate().toPlainString());
        after.put("status", entity.getStatus());
        webAuditor.success("CREATE", "INSURANCE_TYPE", entity.getId(), entity.getTypeName(), null, after);

        return toVO(insuranceTypeMapper.selectById(entity.getId()));
    }

    @Transactional
    public InsuranceTypeVO update(Long id, InsuranceTypeDto.UpdateRequest request) {
        validateUpdate(id, request);
        InsuranceType existing = insuranceTypeMapper.selectById(id);
        InsuranceType entity = new InsuranceType();
        entity.setId(id);
        entity.setTypeName(request.getTypeName());
        entity.setCategory(request.getCategory());
        entity.setBaseRate(request.getBaseRate());
        entity.setMinAmount(request.getMinAmount());
        entity.setMaxAmount(request.getMaxAmount());
        entity.setStatus(request.getStatus());
        entity.setDescription(request.getDescription());

        insuranceTypeMapper.updateById(entity);
        log.info("修改险种成功 id={}", id);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("typeName", existing.getTypeName());
        before.put("category", existing.getCategory());
        before.put("baseRate", existing.getBaseRate() == null ? null : existing.getBaseRate().toPlainString());
        before.put("minAmount", existing.getMinAmount() == null ? null : existing.getMinAmount().toPlainString());
        before.put("maxAmount", existing.getMaxAmount() == null ? null : existing.getMaxAmount().toPlainString());
        Map<String, Object> afterSnapshot = new LinkedHashMap<>();
        afterSnapshot.put("typeName",
                request.getTypeName() == null ? existing.getTypeName() : request.getTypeName());
        afterSnapshot.put("category",
                request.getCategory() == null ? existing.getCategory() : request.getCategory());
        afterSnapshot.put("baseRate", request.getBaseRate() == null
                ? (existing.getBaseRate() == null ? null : existing.getBaseRate().toPlainString())
                : request.getBaseRate().toPlainString());
        afterSnapshot.put("minAmount", request.getMinAmount() == null
                ? (existing.getMinAmount() == null ? null : existing.getMinAmount().toPlainString())
                : request.getMinAmount().toPlainString());
        afterSnapshot.put("maxAmount", request.getMaxAmount() == null
                ? (existing.getMaxAmount() == null ? null : existing.getMaxAmount().toPlainString())
                : request.getMaxAmount().toPlainString());
        webAuditor.success("UPDATE", "INSURANCE_TYPE", id, existing.getTypeName(), before, afterSnapshot);

        return toVO(insuranceTypeMapper.selectById(id));
    }

    /**
     * 险种启停（SYS-W-01 ENABLE/DISABLE）。
     *
     * <p>停用前置检查：该险种被多少条订单引用，需在确认卡上明示（这里是执行期校验，
     * 影响面由 {@link #stopImpact} 提供）。</p>
     */
    @Transactional
    public InsuranceTypeVO changeStatus(Long id, Integer targetStatus) {
        InsuranceType existing = insuranceTypeMapper.selectById(id);
        if (existing == null) {
            throw BizException.notFound("险种不存在: " + id);
        }
        if (existing.getStatus() != null && existing.getStatus().equals(targetStatus)) {
            throw new BizException("险种已处于目标状态，无需变更：" + existing.getTypeName());
        }
        int affected = insuranceTypeMapper.updateStatus(id, targetStatus, existing.getStatus());
        if (affected != 1) {
            throw new BizException("险种状态已被他人修改，请刷新后重试");
        }
        log.info("险种启停成功 id={} code={} status={}", id, existing.getTypeCode(), targetStatus);

        webAuditor.success(targetStatus == 1 ? "ENABLE" : "DISABLE", "INSURANCE_TYPE", id,
                existing.getTypeName(), Map.of("status", existing.getStatus()),
                Map.of("status", targetStatus));

        return toVO(insuranceTypeMapper.selectById(id));
    }

    // ==================================================================
    // 预检（写工具生成提案前调用）
    // ==================================================================

    public void validateCreate(InsuranceTypeDto.CreateRequest request) {
        String code = request.getTypeCode() == null ? null : request.getTypeCode().trim();
        if (code == null || code.isEmpty()) {
            throw BizException.badRequest("险种编码不能为空");
        }
        if (insuranceTypeMapper.selectByCode(code) != null) {
            throw new BizException("险种编码已存在: " + code);
        }
        validateRate(request.getBaseRate());
        validateAmount(request.getMinAmount(), request.getMaxAmount());
        validateCategory(request.getCategory());
    }

    public InsuranceType validateUpdate(Long id, InsuranceTypeDto.UpdateRequest request) {
        InsuranceType existing = insuranceTypeMapper.selectById(id);
        if (existing == null) {
            throw BizException.notFound("险种不存在: " + id);
        }
        validateRate(request.getBaseRate());
        validateAmount(
                request.getMinAmount() == null ? existing.getMinAmount() : request.getMinAmount(),
                request.getMaxAmount() == null ? existing.getMaxAmount() : request.getMaxAmount());
        validateCategory(request.getCategory());
        // 已产生订单的险种禁止修改 category：会影响历史口径
        if (request.getCategory() != null && !request.getCategory().equals(existing.getCategory())) {
            long orders = insuranceTypeMapper.countOrderByType(id);
            if (orders > 0) {
                throw new BizException("该险种已产生 " + orders + " 条订单，不允许修改险种分类（会影响历史口径）");
            }
        }
        return existing;
    }

    /** 停用影响面：被多少条订单引用（确认卡明示）。 */
    public Map<String, Object> stopImpact(InsuranceType type) {
        return Map.of("引用订单数", insuranceTypeMapper.countOrderByType(type.getId()));
    }

    private static void validateRate(BigDecimal baseRate) {
        if (baseRate == null) {
            return;
        }
        if (baseRate.compareTo(BigDecimal.ZERO) <= 0
                || baseRate.compareTo(InsuranceTypeDto.MAX_BASE_RATE) > 0) {
            throw BizException.badRequest("基准费率必须落在 (0, 0.1] 区间，实际为 " + baseRate.toPlainString());
        }
    }

    private static void validateAmount(BigDecimal minAmount, BigDecimal maxAmount) {
        if (minAmount != null && maxAmount != null && minAmount.compareTo(maxAmount) >= 0) {
            throw BizException.badRequest("最小保额必须小于最大保额");
        }
    }

    private static void validateCategory(String category) {
        if (category == null || category.isBlank()) {
            return;
        }
        if (!CATEGORY_NAMES.containsKey(category)) {
            throw BizException.badRequest("险种分类只能是 TENDER / PERFORMANCE / OTHER，实际为 " + category);
        }
    }

    /** 写操作目标解析：按名称/编码模糊匹配（SYS-W-10）。 */
    @Transactional(readOnly = true)
    public List<InsuranceType> findCandidates(String keyword, int limit) {
        return insuranceTypeMapper.selectCandidates(keyword, limit);
    }

    /**
     * 供 AI Tool 使用的按编码解析，返回实体以便 Tool 内部读取费率等字段。
     * 注意：Tool 只能调用 Service，不能直接访问 Mapper。
     */
    @Transactional(readOnly = true)
    public InsuranceType getEntityByCode(String typeCode) {
        return insuranceTypeMapper.selectByCode(typeCode);
    }

    /** 全部启用险种的 id -> 名称映射，供 AI Tool 把险种名称翻译成 id。 */
    @Transactional(readOnly = true)
    public List<InsuranceType> listEnabledEntities() {
        return insuranceTypeMapper.selectAllEnabled();
    }

    private static InsuranceTypeVO toVO(InsuranceType entity) {
        return new InsuranceTypeVO(
                entity.getId(),
                entity.getTypeCode(),
                entity.getTypeName(),
                entity.getCategory(),
                CATEGORY_NAMES.getOrDefault(entity.getCategory(), entity.getCategory()),
                entity.getBaseRate(),
                toPercent(entity.getBaseRate()),
                entity.getMinAmount(),
                entity.getMaxAmount(),
                entity.getStatus(),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getIsDeleted(),
                entity.getDeletedAt(),
                entity.getDeletedBy());
    }

    /**
     * 小数费率 -> 百分比数值（0.008000 -> 0.8）。
     *
     * <p>同时返回两种表示是刻意的：费率口径错误是保函业务最敏感的差错，
     * 让模型直接读到"0.8%"，可以避免它自行乘 100 时算错。</p>
     */
    public static BigDecimal toPercent(BigDecimal baseRate) {
        if (baseRate == null) {
            return null;
        }
        return baseRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
    }
    // ==================================================================
    // 逻辑删除 / 恢复（LD-02 / LD-04 / §6.2）
    // ==================================================================

    /**
     * 险种逻辑删除。
     *
     * <p>与停用的差异（§6.2）：停用只提示被引用条数、不禁；**删除被引用即拒绝**——
     * 删除后历史订单会指向一条"不存在"的险种。</p>
     */
    @Transactional
    public InsuranceTypeVO delete(Long id, Long operatorUserId) {
        InsuranceType existing = insuranceTypeMapper.selectById(id);
        if (existing == null) {
            throw BizException.notFound("险种不存在: " + id);
        }
        List<String> blockers = deleteBlockers(existing);
        if (!blockers.isEmpty()) {
            throw new BizException("该险种不能删除：" + String.join("；", blockers)
                    + "。如只需暂停业务，请改用「停用」。");
        }
        InsuranceTypeVO before = toVO(existing);
        int affected = insuranceTypeMapper.softDelete(id, operatorId(operatorUserId));
        if (affected != 1) {
            throw new BizException("险种已被他人删除，请刷新后重试");
        }
        log.info("险种逻辑删除成功 id={} code={} 操作者={}", id, existing.getTypeCode(), operatorUserId);
        webAuditor.success("DELETE", "INSURANCE_TYPE", id, existing.getTypeName(),
                Map.of("isDeleted", 0), Map.of("isDeleted", 1));
        InsuranceType deleted = insuranceTypeMapper.selectByIdIncludingDeleted(id);
        return new InsuranceTypeVO(before.id(), before.typeCode(), before.typeName(), before.category(),
                before.categoryName(), before.baseRate(), before.baseRatePercent(), before.minAmount(),
                before.maxAmount(), before.status(), before.description(), before.createdAt(),
                before.updatedAt(), deleted.getIsDeleted(), deleted.getDeletedAt(), deleted.getDeletedBy());
    }

    /** 删除阻碍项（§6.2）：被订单引用即拒绝。 */
    public List<String> deleteBlockers(InsuranceType type) {
        long orders = insuranceTypeMapper.countOrderByType(type.getId());
        if (orders > 0) {
            return List.of("已被 " + orders + " 条订单引用");
        }
        return List.of();
    }

    /** 删除影响面（确认卡明示）。 */
    public Map<String, Object> deleteImpact(InsuranceType type) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("引用订单数", insuranceTypeMapper.countOrderByType(type.getId()));
        impact.put("影响", "该险种默认不再出现在列表中；被订单引用时会被拒绝，可在「显示已删除」中恢复");
        return impact;
    }

    /** 险种恢复（LD-04a）：无父记录，仅需确认编码未被有效险种占用。 */
    @Transactional
    public InsuranceTypeVO restore(Long id, Long operatorUserId) {
        InsuranceType existing = insuranceTypeMapper.selectByIdIncludingDeleted(id);
        if (existing == null) {
            throw BizException.notFound("险种不存在: " + id);
        }
        if (!Integer.valueOf(1).equals(existing.getIsDeleted())) {
            throw new BizException("险种未被删除，无需恢复");
        }
        InsuranceType duplicate = insuranceTypeMapper.selectByCode(existing.getTypeCode());
        if (duplicate != null && !duplicate.getId().equals(id)) {
            throw new BizException("险种编码已被同名的有效险种占用，无法恢复：" + existing.getTypeCode());
        }
        int affected = insuranceTypeMapper.restore(id);
        if (affected != 1) {
            throw new BizException("险种恢复失败，可能已被他人恢复，请刷新后重试");
        }
        log.info("险种恢复成功 id={} code={} 操作者={}", id, existing.getTypeCode(), operatorUserId);
        webAuditor.success("RESTORE", "INSURANCE_TYPE", id, existing.getTypeName(),
                Map.of("isDeleted", 1), Map.of("isDeleted", 0));
        return toVO(insuranceTypeMapper.selectById(id));
    }

    /** 删除人标识：应用侧写 sys_user.id 的字符串形式；未知/直连时由 SQL 落默认 'DB'（设计 §2.1a）。 */
    private static String operatorId(Long operatorUserId) {
        return operatorUserId == null ? null : String.valueOf(operatorUserId);
    }

}
