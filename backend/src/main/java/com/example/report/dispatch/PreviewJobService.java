package com.example.report.dispatch;

import com.example.report.agent.ConversationCards;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.config.ResourceQuotaService;
import com.example.report.permission.CurrentUser;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 长查询的持久化任务入口。任务状态可由任意实例读取；运行实例负责取消和超时。 */
@Service
public class PreviewJobService {
    private final JdbcTemplate jdbc;
    private final PreviewService previews;
    private final ConversationCards cards;
    private final ResourceQuotaService quotas;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(40), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final Map<String, ResourceQuotaService.Permit> permits = new ConcurrentHashMap<>();

    public record Job(String id, String status, String stage, int scannedRows, String message, String previewId,
                      LocalDateTime createdAt, LocalDateTime updatedAt) { }

    public PreviewJobService(JdbcTemplate jdbc, PreviewService previews, ConversationCards cards,
                             ResourceQuotaService quotas) {
        this.jdbc = jdbc;
        this.previews = previews;
        this.cards = cards;
        this.quotas = quotas;
    }

    public Job submit(CurrentUser user, String conversationId, PreviewCommand command) {
        ResourceQuotaService.Permit permit = quotas.acquire(user, "preview-job", command.reportIds());
        String id = JsonUtil.newId();
        LocalDateTime now = LocalDateTime.now();
        long requestVersion;
        try {
            ResourceQuotaService.check(permit);
            requestVersion = previews.beginRequest(conversationId);
            jdbc.update("INSERT INTO dispatch_preview_job (id,tenant_id,user_id,conversation_id,request_version,status,stage,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", id, user.tenantId(), user.userId(), conversationId,
                    requestVersion, "QUEUED", "WAITING", now, now);
            permits.put(id, permit);
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
        try {
            FutureTask<Void> future = new FutureTask<>(() -> {
                run(user, conversationId, command, id, requestVersion);
                return null;
            });
            futures.put(id, future);
            workers.execute(future);
            timer.schedule(() -> timeout(user, id), 120, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            futures.remove(id);
            try { update(id, "FAILED", "QUEUE", "查询队列已满，请稍后重试", null); }
            finally { release(id); }
        }
        return get(user, id);
    }

    public Job get(CurrentUser user, String id) {
        return jdbc.query("SELECT id,status,stage,scanned_rows,message,preview_id,created_at,updated_at "
                        + "FROM dispatch_preview_job WHERE id=? AND tenant_id=? AND user_id=?",
                (rs, row) -> map(rs), id, user.tenantId(), user.userId()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("预览任务不存在"));
    }

    /** SSE 事件丢失时从数据库恢复任务；已完成任务也返回，防止遗漏完成窗口。 */
    public Job latestForConversation(CurrentUser user, String conversationId) {
        return jdbc.query("SELECT j.id,j.status,j.stage,j.scanned_rows,j.message,j.preview_id,j.created_at,j.updated_at "
                        + "FROM dispatch_preview_job j JOIN agent_conversation c ON c.id=j.conversation_id "
                        + "WHERE j.tenant_id=? AND j.user_id=? AND j.conversation_id=? "
                        + "AND j.request_version=c.preview_request_version "
                        + "ORDER BY j.request_version DESC LIMIT 1",
                (rs, row) -> map(rs), user.tenantId(), user.userId(), conversationId)
                .stream().findFirst().orElse(null);
    }

    public Job cancel(CurrentUser user, String id) {
        get(user, id);
        int changed = jdbc.update("UPDATE dispatch_preview_job SET status='CANCELLED',stage='CANCELLED',"
                        + "message='用户已取消',updated_at=NOW() WHERE id=? AND tenant_id=? AND user_id=? "
                        + "AND status IN ('QUEUED','RUNNING')", id, user.tenantId(), user.userId());
        if (changed == 1) {
            cancelLocal(id);
        }
        return get(user, id);
    }

    private void run(CurrentUser user, String conversationId, PreviewCommand command, String id, long requestVersion) {
        try {
            if (!transition(id, "QUEUED", "RUNNING", "QUERYING")) return;
            ensureRunning(id);
            java.util.concurrent.atomic.AtomicInteger reported = new java.util.concurrent.atomic.AtomicInteger();
            PreviewOutcome outcome = previews.preview(user, conversationId, command, scanned -> {
                ensureRunning(id);
                if (scanned - reported.get() >= 5000 || (scanned > 0 && reported.get() == 0)) {
                    jdbc.update("UPDATE dispatch_preview_job SET stage='SCANNING',scanned_rows=?,updated_at=NOW() "
                            + "WHERE id=? AND status='RUNNING'", scanned, id);
                    reported.set(scanned);
                }
            }, previewId -> {
                ensureRunning(id);
                if (jdbc.update("UPDATE dispatch_preview_job SET status='SUCCEEDED',stage='DONE',preview_id=?,"
                        + "updated_at=NOW() WHERE id=? AND status='RUNNING'", previewId, id) != 1) {
                    throw new ApiException(409, "查询任务已取消或超时");
                }
            }, requestVersion);
            if (outcome.status() != PreviewOutcome.Status.OK) {
                update(id, "FAILED", "RESOLVE", "没有找到可用的报表，请重新选择", null);
                return;
            }
            if ("selection".equals(command.source())) {
                cards.recordSelectionPreview(user, conversationId, outcome);
            } else {
                cards.recordAsyncPreview(user, conversationId, outcome);
            }
        } catch (RuntimeException e) {
            if (!get(user, id).status().equals("CANCELLED")) {
                update(id, "FAILED", "QUERY", e.getMessage(), null);
            }
        } finally {
            futures.remove(id);
            release(id);
        }
    }

    private void ensureRunning(String id) {
        ResourceQuotaService.check(permits.get(id));
        if (Thread.currentThread().isInterrupted() || !"RUNNING".equals(jdbc.queryForObject(
                "SELECT status FROM dispatch_preview_job WHERE id=?", String.class, id))) {
            throw new ApiException(409, "查询任务已取消或超时");
        }
    }

    private void release(String id) {
        ResourceQuotaService.Permit permit = permits.remove(id);
        if (permit != null) permit.close();
    }

    private void timeout(CurrentUser user, String id) {
        Job job = get(user, id);
        if (!job.status().equals("QUEUED") && !job.status().equals("RUNNING")) return;
        int changed = jdbc.update("UPDATE dispatch_preview_job SET status='FAILED',stage='TIMEOUT',message='查询超过 120 秒，请缩小范围后重试',"
                        + "updated_at=NOW() WHERE id=? AND status IN ('QUEUED','RUNNING')", id);
        // Activation may have won after the read. Do not interrupt completed work or release its permit.
        if (changed == 1) cancelLocal(id);
    }

    private void cancelLocal(String id) {
        Future<?> future = futures.remove(id);
        if (future != null) {
            future.cancel(true);
            if (future instanceof Runnable queued) workers.remove(queued);
        }
        release(id);
    }

    @Scheduled(fixedDelayString = "${agent.preview-job-recovery-ms:60000}")
    public void recoverOrphans() {
        jdbc.update("UPDATE dispatch_preview_job SET status='FAILED',stage='INTERRUPTED',"
                + "message='查询任务中断，请重新发起',updated_at=NOW() "
                + "WHERE status IN ('QUEUED','RUNNING') AND updated_at < DATE_SUB(NOW(), INTERVAL 3 MINUTE)");
        // 进程在批量写入期间退出时，未激活的快照不会被前端读取，但其明细仍需回收。
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);
        for (String id : jdbc.queryForList("SELECT id FROM dispatch_preview WHERE status='BUILDING' "
                + "AND updated_at<? ORDER BY updated_at LIMIT 100", String.class, cutoff)) {
            previews.deleteAbandonedBuilding(id, cutoff);
        }
    }

    private boolean transition(String id, String from, String to, String stage) {
        return jdbc.update("UPDATE dispatch_preview_job SET status=?,stage=?,updated_at=NOW() WHERE id=? AND status=?",
                to, stage, id, from) == 1;
    }

    private void update(String id, String status, String stage, String message, String previewId) {
        jdbc.update("UPDATE dispatch_preview_job SET status=?,stage=?,message=?,preview_id=?,updated_at=NOW() "
                        + "WHERE id=? AND status IN ('QUEUED','RUNNING')", status, stage,
                message == null ? null : message.substring(0, Math.min(message.length(), 512)), previewId, id);
    }

    private static Job map(ResultSet rs) throws SQLException {
        return new Job(rs.getString("id"), rs.getString("status"), rs.getString("stage"), rs.getInt("scanned_rows"),
                rs.getString("message"), rs.getString("preview_id"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
        timer.shutdownNow();
    }
}
