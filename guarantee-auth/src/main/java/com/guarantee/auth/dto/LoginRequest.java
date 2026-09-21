package com.guarantee.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 登录请求。 */
@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名长度不能超过 64")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码长度不能超过 64")
    private String password;
}
