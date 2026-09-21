package com.guarantee.system.service;

import com.guarantee.system.mapper.SysPermissionMapper;
import com.guarantee.system.vo.PermissionVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 权限配置服务。返回扁平列表（按 sort_no 升序），由前端按 parentId 组树。
 */
@Service
public class PermissionService {

    private final SysPermissionMapper sysPermissionMapper;

    public PermissionService(SysPermissionMapper sysPermissionMapper) {
        this.sysPermissionMapper = sysPermissionMapper;
    }

    @Transactional(readOnly = true)
    public List<PermissionVO> listAll() {
        return sysPermissionMapper.selectAllOrdered();
    }
}
