package com.example.report.web;

import com.example.report.common.Result;
import com.example.report.permission.CurrentUser;
import com.example.report.permission.PermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模拟登录：列出 demo 用户，前端用下拉切换身份（真实系统由登录态提供）
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final PermissionService permissionService;

    public AuthController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping("/users")
    public Result<List<CurrentUser>> users(@RequestHeader(value=PermissionService.USER_HEADER,required=false) String userId) {
        return Result.ok(permissionService.isSecure()?List.of(permissionService.resolve(userId)):permissionService.listUsers());
    }

    @GetMapping("/mode")
    public Result<java.util.Map<String,Boolean>> mode() { return Result.ok(java.util.Map.of("loginRequired",permissionService.isSecure())); }

    @GetMapping("/me")
    public Result<CurrentUser> me(@RequestHeader(PermissionService.USER_HEADER) String userId) {
        return Result.ok(permissionService.resolve(userId));
    }
}
