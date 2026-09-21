package com.guarantee.system.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.system.dto.UserDto;
import com.guarantee.system.service.UserService;
import com.guarantee.system.vo.UserVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户配置接口。响应 VO 不含密码字段。
 */
@RestController
@RequestMapping("/api/system/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public Result<PageResult<UserVO>> list(@Valid UserDto.Query query) {
        return Result.ok(userService.page(query));
    }

    @GetMapping("/{id}")
    public Result<UserVO> detail(@PathVariable Long id) {
        return Result.ok(userService.getById(id));
    }
}
