package com.guarantee.system.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import com.guarantee.system.dto.OrgDto;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.mapper.SysOrgMapper;
import com.guarantee.system.vo.OrgOptionVO;
import com.guarantee.system.vo.OrgVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 机构配置服务。Controller -> Service -> Mapper -> DB，对外只暴露 VO。
 */
@Service
public class OrgService {

    private final SysOrgMapper sysOrgMapper;

    public OrgService(SysOrgMapper sysOrgMapper) {
        this.sysOrgMapper = sysOrgMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<OrgVO> page(OrgDto.Query query) {
        long total = sysOrgMapper.countByQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<OrgVO> list = sysOrgMapper.selectPage(query, query.offset(), query.getPageSize());
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    /** 下拉框使用：仅启用机构。 */
    @Transactional(readOnly = true)
    public List<OrgOptionVO> listOptions() {
        return sysOrgMapper.selectEnabledOptions();
    }

    @Transactional(readOnly = true)
    public OrgVO getById(Long id) {
        OrgVO vo = sysOrgMapper.selectVoById(id);
        if (vo == null) {
            throw BizException.notFound("机构不存在: " + id);
        }
        return vo;
    }

    /**
     * 供其它模块使用：全部启用机构实体。
     * 其它模块不得直接访问 Mapper，只能通过本方法读取机构。
     */
    @Transactional(readOnly = true)
    public List<SysOrg> listEnabledOrgEntities() {
        return sysOrgMapper.selectAllEnabled();
    }

    /**
     * 供其它模块按主键读取机构实体；不存在时抛 {@link BizException#notFound(String)}。
     */
    @Transactional(readOnly = true)
    public SysOrg getEntityById(Long id) {
        SysOrg entity = sysOrgMapper.selectEntityById(id);
        if (entity == null) {
            throw BizException.notFound("机构不存在: " + id);
        }
        return entity;
    }
}
