package com.example.report.security;

import com.example.report.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name="security.enabled",havingValue="true")
public class LoginController {
    private final IdentityStore identities;
    private final ClientAddressResolver addresses;
    public LoginController(IdentityStore identities,ClientAddressResolver addresses) {this.identities=identities;this.addresses=addresses;}
    public record Login(String userId,String password) {}
    @PostMapping("/login") public Result<IdentityStore.LoginResult> login(@RequestBody Login login,HttpServletRequest request) {
        return Result.ok(identities.login(login.userId(),login.password(),addresses.resolve(request)));
    }
    @PostMapping("/logout") public Result<Boolean> logout(HttpServletRequest request) {identities.logout(SessionFilter.bearer(request));return Result.ok(true);}
    @PutMapping("/users") public Result<Boolean> save(@RequestBody IdentityStore.UserForm user,HttpServletRequest request) {
        identities.saveUser(identities.authenticate(SessionFilter.bearer(request)),user);return Result.ok(true);
    }
}
