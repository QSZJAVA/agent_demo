package com.example.report.web;

import com.example.report.common.Result;
import com.example.report.dispatch.DispatchJobService;
import com.example.report.permission.PermissionService;
import org.springframework.web.bind.annotation.*;

/**
 * 派单持久化任务的 HTTP 入口；POST 接收稳定幂等键，GET 查询已存在任务。
 * 用户身份从服务端上下文取得；任务被接收不代表业务已经成功，前端须继续读取终态。
 */
@RestController
@RequestMapping("/api/dispatch/jobs")
public class DispatchJobController {
    private final PermissionService permissions;
    private final DispatchJobService jobs;
    public DispatchJobController(PermissionService permissions,DispatchJobService jobs) { this.permissions=permissions;this.jobs=jobs; }
    /** 持久化操作命令并返回任务；同一幂等键只能回放同一负载，业务结果须继续查询终态。 */
    @PostMapping public Result<DispatchJobService.Job> submit(@RequestHeader(PermissionService.USER_HEADER) String userId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody DispatchJobService.Request request) {
        return Result.ok(jobs.submit(permissions.resolve(userId),request,key));
    }
    /** 只读当前用户所属任务；读取时再次验证当前数据授权，不能据任务ID绕过权限。 */
    @GetMapping("/{id}") public Result<DispatchJobService.Job> get(@RequestHeader(PermissionService.USER_HEADER) String userId,@PathVariable String id) {
        return Result.ok(jobs.get(permissions.resolve(userId),id));
    }
    /** 查询清单最近既有任务供页面恢复；无任务时返回空，不创建任何派单命令。 */
    @GetMapping public Result<DispatchJobService.Job> latest(@RequestHeader(PermissionService.USER_HEADER) String userId,@RequestParam String planId) {
        return Result.ok(jobs.latest(permissions.resolve(userId),planId));
    }
}
