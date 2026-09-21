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

    public InsuranceTypeService(InsuranceTypeMapper insuranceTypeMapper) {
        this.insuranceTypeMapper = insuranceTypeMapper;
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

    @Transactional
    public InsuranceTypeVO create(InsuranceTypeDto.CreateRequest request) {
        if (insuranceTypeMapper.selectByCode(request.getTypeCode()) != null) {
            throw new BizException("险种编码已存在: " + request.getTypeCode());
        }
        InsuranceType entity = new InsuranceType();
        entity.setTypeCode(request.getTypeCode());
        entity.setTypeName(request.getTypeName());
        entity.setCategory(request.getCategory());
        entity.setBaseRate(request.getBaseRate());
        entity.setMinAmount(request.getMinAmount());
        entity.setMaxAmount(request.getMaxAmount());
        entity.setStatus(1);
        entity.setDescription(request.getDescription());

        insuranceTypeMapper.insert(entity);
        log.info("新增险种成功 id={} code={}", entity.getId(), entity.getTypeCode());
        return toVO(insuranceTypeMapper.selectById(entity.getId()));
    }

    @Transactional
    public InsuranceTypeVO update(Long id, InsuranceTypeDto.UpdateRequest request) {
        InsuranceType existing = insuranceTypeMapper.selectById(id);
        if (existing == null) {
            throw BizException.notFound("险种不存在: " + id);
        }
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
        return toVO(insuranceTypeMapper.selectById(id));
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
                entity.getMinAmount(),
                entity.getMaxAmount(),
                entity.getStatus(),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
