package com.guarantee.system.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.system.dto.DepartmentDto;
import com.guarantee.system.mapper.SysDepartmentMapper;
import com.guarantee.system.vo.DepartmentOptionVO;
import com.guarantee.system.vo.DepartmentVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 部门配置服务。orgName 由 Mapper 关联 sys_org 直接返回。
 */
@Service
public class DepartmentService {

    private final SysDepartmentMapper sysDepartmentMapper;

    public DepartmentService(SysDepartmentMapper sysDepartmentMapper) {
        this.sysDepartmentMapper = sysDepartmentMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<DepartmentVO> page(DepartmentDto.Query query) {
        long total = sysDepartmentMapper.countByQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<DepartmentVO> list = sysDepartmentMapper.selectPage(query, query.offset(), query.getPageSize());
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    /** 下拉框使用：仅启用部门，orgId 为空时返回全部。 */
    @Transactional(readOnly = true)
    public List<DepartmentOptionVO> listOptions(Long orgId) {
        return sysDepartmentMapper.selectEnabledOptions(orgId);
    }
}
