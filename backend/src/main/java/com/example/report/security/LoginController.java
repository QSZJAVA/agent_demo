package com.example.report.security;

import com.example.report.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 当前 Demo 的登录及用户管理 HTTP 入口；返回服务端解析的用户与会话。真实外部认证系统接入待完成。
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name="security.enabled",havingValue="true")
public class LoginController {
    private final IdentityStore identities;
    private final ClientAddressResolver addresses;
    public LoginController(IdentityStore identities,ClientAddressResolver addresses) {this.identities=identities;this.addresses=addresses;}
    /**
     * 当前Demo登录请求；真实外部认证接入待完成。
     * @param userId 租户内用户标识，来自服务端身份
     * @param password 请求中的密码输入，仅用于当前Demo认证；不得保存或打印原文
     */
    public record Login(String userId,String password) {}
    @PostMapping("/login") public Result<IdentityStore.LoginResult> login(@RequestBody Login login,HttpServletRequest request) {
        return Result.ok(identities.login(login.userId(),login.password(),addresses.resolve(request)));
    }
    @PostMapping("/logout") public Result<Boolean> logout(HttpServletRequest request) {identities.logout(SessionFilter.bearer(request));return Result.ok(true);}
    @PutMapping("/users") public Result<Boolean> save(@RequestBody IdentityStore.UserForm user,HttpServletRequest request) {
        identities.saveUser(identities.authenticate(SessionFilter.bearer(request)),user);return Result.ok(true);
    }
}
