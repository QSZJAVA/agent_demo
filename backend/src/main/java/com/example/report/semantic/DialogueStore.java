package com.example.report.semantic;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.config.AgentProperties;
import com.example.report.operations.SensitiveData;
import com.example.report.permission.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import java.util.*;
import java.util.function.Supplier;

/**
 * 会话权威语义状态的数据库仓储，以租约令牌和乐观锁版本串行化对话轮次。
 * 事务只覆盖状态保存与业务激活，模型调用和报表扫描在事务外执行，避免长时间锁库。删除墓碑使旧轮次失去写入权。
 */
@Service
public class DialogueStore {
    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;
    private final int timeout;
    public DialogueStore(JdbcTemplate jdbc, TransactionOperations tx, AgentProperties props) {
        this.jdbc = jdbc; this.tx = tx;
        timeout = Math.max(10, Math.min(240, props.getSemantic().getTurnTimeoutSeconds()));
    }
    public int timeoutSeconds() { return timeout; }
    private void owner(CurrentUser user, String id, boolean lock) {
        var rows = jdbc.queryForList("SELECT id FROM agent_conversation WHERE id=? AND tenant_id=? AND user_id=? AND status<>'deleted' "
                + "AND NOT EXISTS(SELECT 1 FROM conversation_erasure e WHERE e.conversation_id=agent_conversation.id)" + (lock ? " FOR UPDATE" : ""),
                id, user.tenantId(), user.userId());
        if (rows.isEmpty()) throw ApiException.notFound("会话不存在或已删除");
    }
    /**
     * 验证会话归属与删除墓碑后读取权威状态；首次会话返回初始状态，读取不取得处理租约。
     */
    public DialogueState read(CurrentUser user, String id) {
        owner(user, id, false);
        var rows = jdbc.queryForList("SELECT state_json FROM semantic_dialogue WHERE conversation_id=? AND tenant_id=? AND user_id=?", id, user.tenantId(), user.userId());
        return rows.isEmpty() ? new DialogueState() : decode(rows.get(0).get("state_json").toString());
    }
    /**
     * 事务内锁会话并认领对话租约；并发轮次或未过期租约返回409，模型调用不得持有此事务。
     * @return 调用方必须关闭的对话会话句柄
     */
    public Session acquire(CurrentUser user, String id) {
        return tx.execute(status -> {
            owner(user, id, true);
            jdbc.update("INSERT IGNORE INTO semantic_dialogue(conversation_id,tenant_id,user_id,state_json,updated_at) VALUES (?,?,?,?,NOW(3))",
                    id, user.tenantId(), user.userId(), JsonUtil.toJson(new DialogueState()));
            String token = JsonUtil.newId();
            int changed = jdbc.update("UPDATE semantic_dialogue SET lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,NOW(3)) WHERE conversation_id=? AND tenant_id=? AND user_id=? AND (lease_token IS NULL OR lease_until<NOW(3))",
                    token, timeout, id, user.tenantId(), user.userId());
            if (changed != 1) throw new ApiException(409, "该会话正在处理上一条消息，请等待完成后重试");
            var row = jdbc.queryForMap("SELECT version,state_json FROM semantic_dialogue WHERE conversation_id=?", id);
            return new Session(user, id, token, ((Number)row.get("version")).longValue(), decode(row.get("state_json").toString()));
        });
    }
    static DialogueState decode(String json) {
        try {
            var node=JsonUtil.MAPPER.readTree(json);
            var pending=node.path("pendingIntent");
            if (pending.path("version").asInt()==1)
                ((com.fasterxml.jackson.databind.node.ObjectNode)node).set("pendingIntent",JsonUtil.MAPPER.valueToTree(IntentCodec.fromStoredV1(pending)));
            return JsonUtil.MAPPER.treeToValue(node, DialogueState.class);
        }
        catch (Exception e) { throw new IllegalStateException("会话状态无法读取", e); }
    }
    public final class Session implements AutoCloseable {
        private final CurrentUser user;
        private final String id, token;
        private long version;
        private final DialogueState state;
        Session(CurrentUser user, String id, String token, long version, DialogueState state) {
            this.user=user; this.id=id; this.token=token; this.version=version; this.state=state;
        }
        public DialogueState state() { return state; }
        /**
         * 同时验证线程取消、会话删除、租约令牌、状态版本与截止时间；任何条件失效都阻止旧轮次继续写入。
         */
        public void check() {
            if (Thread.currentThread().isInterrupted()) throw new ApiException(409, "对话处理已取消");
            owner(user, id, false);
            Boolean valid = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM semantic_dialogue WHERE conversation_id=? AND lease_token=? AND version=? AND lease_until>NOW(3))", Boolean.class, id, token, version);
            if (!Boolean.TRUE.equals(valid)) throw new ApiException(409, "本次对话处理已超时或被替代，请重新发送");
        }
        /**
         * Also used inside preview activation's transaction; fence cannot change before commit.  * 在短事务中锁语义状态行并验证执行权，再运行状态相关的业务激活操作；事务提交前其他轮次不能换走令牌。
         */
        public <T> T fenced(Supplier<T> action) {
            return tx.execute(status -> {
                owner(user, id, true);
                jdbc.queryForList("SELECT conversation_id FROM semantic_dialogue WHERE conversation_id=? FOR UPDATE", id);
                check();
                return action.get();
            });
        }
        /**
         * 先脱敏再用令牌和版本条件保存，成功后同步递增本地版本；不能用无条件 UPDATE 覆盖其他轮次。
         */
        public void save() {
            fenced(() -> {
                // The protocol carries raw source spans; persist a redacted copy only.
                String json = JsonUtil.toJson(SensitiveData.value(state));
                int n = jdbc.update("UPDATE semantic_dialogue SET state_json=?,version=version+1,updated_at=NOW(3) WHERE conversation_id=? AND lease_token=? AND version=?",
                        json, id, token, version);
                if (n != 1) throw new ApiException(409, "会话状态已变更");
                version++;
                return null;
            });
        }
        /**
         * 在有效租约内保存本轮意图与处理结果证据；原文、原因和模型名按存储边界脱敏或截断，latency 单位为毫秒。
         */
        public void record(String requestId, String utterance, SemanticIntent intent, String outcome, String reason, String model, long latency) {
            fenced(() -> {
                jdbc.update("INSERT INTO semantic_turn(request_id,conversation_id,tenant_id,user_id,mode,state_version,utterance,intent_json,outcome,reason,model,latency_ms,created_at) VALUES (?,?,?,?,'active',?,?,?,?,?,?,?,NOW(3))",
                        requestId,id,user.tenantId(),user.userId(),version,limit(SensitiveData.text(utterance),2000),
                        intent == null ? null : JsonUtil.toJson(SensitiveData.value(intent)),outcome,limit(SensitiveData.text(reason),512),limit(model,128),latency);
                return null;
            });
        }
        @Override public void close() {
            jdbc.update("UPDATE semantic_dialogue SET lease_token=NULL,lease_until=NULL WHERE conversation_id=? AND lease_token=?", id, token);
        }
    }
    private static String limit(String value, int n) { return value == null ? null : value.substring(0, Math.min(n,value.length())); }
}
