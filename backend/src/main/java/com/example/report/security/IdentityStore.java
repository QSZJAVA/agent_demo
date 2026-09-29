package com.example.report.security;

import com.example.report.common.ApiException;
import com.example.report.common.Digests;
import com.example.report.permission.CurrentUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.*;
import java.security.SecureRandom;
import java.util.*;

@Service
@ConditionalOnProperty(name="security.enabled",havingValue="true")
public class IdentityStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final StringRedisTemplate redis;
    private final String tenant;
    private final String bootstrap;
    private final String dummy=Passwords.hash(UUID.randomUUID().toString());
    public IdentityStore(JdbcTemplate jdbc,ObjectMapper json,StringRedisTemplate redis,
                         @Value("${security.tenant-id:T001}") String tenant,
                         @Value("${security.bootstrap-password:}") String bootstrap) {
        this.jdbc=jdbc; this.json=json; this.redis=redis; this.tenant=tenant; this.bootstrap=bootstrap;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE tenant_id=?",Long.class,tenant)>0) return;
        if(bootstrap==null || bootstrap.isBlank()) throw new IllegalStateException("首次启动请设置 AUTH_BOOTSTRAP_PASSWORD（至少 12 位）");
        jdbc.update("INSERT IGNORE INTO app_user(tenant_id,user_id,display_name,password_hash,companies_json,permissions_json,is_admin) VALUES (?,?,?,?,?,?,true)",
                tenant,"admin","管理员",Passwords.hash(bootstrap),"[\"A\",\"B\",\"C\"]","[\"*\"]");
    }
    public String tenant() { return tenant; }
    public CurrentUser resolve(String tenantId,String userId) {
        if(!tenant.equals(tenantId) || userId==null) throw ApiException.forbidden("用户不可用");
        return jdbc.query("SELECT * FROM app_user WHERE tenant_id=? AND user_id=? AND enabled=true",
                (rs,i)->new CurrentUser(tenantId,rs.getString("user_id"),rs.getString("display_name"),
                        strings(rs.getString("companies_json")),strings(rs.getString("permissions_json")),rs.getBoolean("is_admin")),tenantId,userId)
                .stream().findFirst().orElseThrow(()->ApiException.forbidden("用户不可用"));
    }
    public CurrentUser authenticate(String token) {
        if(token==null || token.length()>256) throw new ApiException(401,"请先登录");
        var users=jdbc.query("SELECT user_id FROM app_session WHERE token_hash=? AND tenant_id=? AND expires_at>NOW()",
                (rs,i)->rs.getString(1),Digests.sha256(token),tenant);
        if(users.isEmpty()) throw new ApiException(401,"登录已失效，请重新登录");
        try { return resolve(tenant,users.get(0)); } catch(ApiException e) { throw new ApiException(401,"登录已失效"); }
    }
    public LoginResult login(String userId,String password,String clientAddress) {
        if(userId==null || !userId.matches("[a-zA-Z0-9_.-]{1,64}")) throw new ApiException(401,"账号或密码错误");
        limit("account:"+userId.toLowerCase(Locale.ROOT),10); limit("ip:"+clientAddress,40);
        var hashes=jdbc.query("SELECT password_hash FROM app_user WHERE tenant_id=? AND user_id=? AND enabled=true",
                (rs,i)->rs.getString(1),tenant,userId);
        if(!Passwords.matches(password,hashes.isEmpty()?dummy:hashes.get(0)) || hashes.isEmpty()) throw new ApiException(401,"账号或密码错误");
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime expires=LocalDateTime.now().plusHours(8);
        jdbc.update("INSERT INTO app_session(token_hash,tenant_id,user_id,expires_at) VALUES (?,?,?,?)",Digests.sha256(token),tenant,userId,expires);
        return new LoginResult(token,expires,resolve(tenant,userId));
    }
    private void limit(String scope,int max) {
        String key="auth:login:"+Digests.sha256(tenant+":"+scope)+":"+(System.currentTimeMillis()/600000);
        Long n=redis.opsForValue().increment(key);
        if(n==null) throw new ApiException(503,"认证暂不可用");
        if(n==1) redis.expire(key,Duration.ofMinutes(11));
        if(n>max) throw new ApiException(429,"登录尝试过多，请 10 分钟后重试");
    }
    public void logout(String token) { if(token!=null) jdbc.update("DELETE FROM app_session WHERE token_hash=?",Digests.sha256(token)); }
    @Transactional
    public void saveUser(CurrentUser admin,UserForm form) {
        if(!admin.admin()) throw ApiException.forbidden("仅管理员可管理账号");
        if(form.userId()==null || !form.userId().matches("[a-zA-Z0-9_.-]{1,64}") || form.displayName()==null
                || form.displayName().isBlank() || form.displayName().length()>128 || form.companies()==null || form.permissions()==null
                || form.companies().size()>100 || form.permissions().size()>200
                || form.companies().stream().anyMatch(c->c==null || !c.matches("[a-zA-Z0-9_.-]{1,64}"))
                || form.permissions().stream().anyMatch(p->p==null || p.isBlank() || p.length()>128)) throw new ApiException("账号参数不合法");
        if(form.userId().equals(admin.userId()) && (!form.enabled() || !form.admin())) throw new ApiException("不能禁用或降权当前管理员");
        var old=jdbc.query("SELECT password_hash FROM app_user WHERE tenant_id=? AND user_id=? FOR UPDATE",(rs,i)->rs.getString(1),tenant,form.userId());
        String hash;
        try { hash=form.password()==null || form.password().isBlank() ? old.stream().findFirst().orElseThrow() : Passwords.hash(form.password()); }
        catch(RuntimeException e) { throw new ApiException("新账号须设置至少 12 位密码"); }
        jdbc.update("INSERT INTO app_user(tenant_id,user_id,display_name,password_hash,companies_json,permissions_json,is_admin,enabled) VALUES (?,?,?,?,?,?,?,?) "
                        +"ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),password_hash=VALUES(password_hash),companies_json=VALUES(companies_json),permissions_json=VALUES(permissions_json),is_admin=VALUES(is_admin),enabled=VALUES(enabled)",
                tenant,form.userId(),form.displayName(),hash,write(form.companies()),write(form.permissions()),form.admin(),form.enabled());
        jdbc.update("DELETE FROM app_session WHERE tenant_id=? AND user_id=?",tenant,form.userId());
    }
    @Scheduled(fixedDelay=3600000) public void cleanup() { jdbc.update("DELETE FROM app_session WHERE expires_at<NOW() LIMIT 10000"); }
    private Set<String> strings(String value) { try{return json.readValue(value,new TypeReference<>() {});}catch(Exception e){throw new IllegalStateException("账号权限配置无效");} }
    private String write(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);} }
    public record LoginResult(String token,LocalDateTime expiresAt,CurrentUser user) {}
    public record UserForm(String userId,String displayName,String password,Set<String> companies,Set<String> permissions,boolean admin,boolean enabled) {}
}
