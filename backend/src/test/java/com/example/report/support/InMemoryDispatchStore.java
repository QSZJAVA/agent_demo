package com.example.report.support;

import com.example.report.dispatch.store.PlanRepository;
import com.example.report.dispatch.store.PreviewRepository;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.entity.DispatchPreview;
import com.example.report.entity.DispatchPreviewItem;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 预览 / 清单仓库的内存实现，行为与 MySQL 版一致：状态迁移是 CAS，读写都复制对象（库里的行不会被调用方的对象改动），
 * 同一会话最多一份 ACTIVE 预览、一份 PENDING 清单（对应生成列唯一索引）。
 */
public final class InMemoryDispatchStore {

    private final Map<String, DispatchPreview> previews = new LinkedHashMap<>();
    private final Map<String, List<DispatchPreviewItem>> previewItems = new HashMap<>();
    private final Map<String, Long> previewOrder = new HashMap<>();
    private final Map<String, DispatchPlan> plans = new LinkedHashMap<>();
    private final Map<String, List<DispatchPlanItem>> planItems = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Long> previewRequestVersions = new HashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> executionLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private java.util.concurrent.locks.ReentrantLock executionLock(String planId) {
        return executionLocks.computeIfAbsent(planId, ignored -> new java.util.concurrent.locks.ReentrantLock());
    }

    private final PreviewRepository previewRepository = new Previews();
    private final PlanRepository planRepository = new Plans();

    public PreviewRepository previews() {
        return previewRepository;
    }

    public PlanRepository plans() {
        return planRepository;
    }

    /** 测试直接改库：例如把有效期改到过去 */
    public synchronized void updatePreview(String id, java.util.function.Consumer<DispatchPreview> change) {
        change.accept(previews.get(id));
    }

    public synchronized void updatePlan(String id, java.util.function.Consumer<DispatchPlan> change) {
        change.accept(plans.get(id));
    }

    private static <T> T copy(T source, T target) {
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private static DispatchPreview copy(DispatchPreview p) {
        return p == null ? null : copy(p, new DispatchPreview());
    }

    private static DispatchPlan copy(DispatchPlan p) {
        return p == null ? null : copy(p, new DispatchPlan());
    }

    private final class Previews implements PreviewRepository {

        @Override
        public long beginRequest(String conversationId) {
            if (conversationId == null) return 0;
            synchronized (InMemoryDispatchStore.this) {
                return previewRequestVersions.merge(conversationId, 1L, Long::sum);
            }
        }

        @Override
        public boolean isLatestRequest(String conversationId, long version) {
            if (conversationId == null) return true;
            synchronized (InMemoryDispatchStore.this) {
                return Objects.equals(previewRequestVersions.get(conversationId), version);
            }
        }

        @Override
        public void lockConversation(String conversationId) {
        }

        @Override
        public void insert(DispatchPreview preview, List<DispatchPreviewItem> items) {
            synchronized (InMemoryDispatchStore.this) {
                if (preview.getConversationId() != null && DispatchPreview.ACTIVE.equals(preview.getStatus())
                        && previews.values().stream().anyMatch(p -> preview.getConversationId().equals(p.getConversationId())
                        && DispatchPreview.ACTIVE.equals(p.getStatus()))) {
                    throw new DuplicateKeyException("uk_preview_active");
                }
                previews.put(preview.getId(), copy(preview));
                previewOrder.put(preview.getId(), sequence.incrementAndGet());
                List<DispatchPreviewItem> stored = new ArrayList<>();
                for (DispatchPreviewItem i : items) {
                    DispatchPreviewItem c = copy(i, new DispatchPreviewItem());
                    c.setId(sequence.incrementAndGet());
                    stored.add(c);
                }
                previewItems.put(preview.getId(), stored);
            }
        }

        @Override
        public Optional<DispatchPreview> find(String previewId) {
            synchronized (InMemoryDispatchStore.this) {
                return Optional.ofNullable(copy(previews.get(previewId)));
            }
        }

        @Override
        public List<DispatchPreview> findAll(Collection<String> previewIds) {
            synchronized (InMemoryDispatchStore.this) {
                return previewIds.stream().map(previews::get).filter(Objects::nonNull).map(InMemoryDispatchStore::copy).toList();
            }
        }

        @Override
        public List<DispatchPreviewItem> items(String previewId) {
            synchronized (InMemoryDispatchStore.this) {
                return previewItems.getOrDefault(previewId, List.of()).stream()
                        .map(i -> copy(i, new DispatchPreviewItem())).toList();
            }
        }

        @Override
        public Optional<DispatchPreview> latest(String tenantId, String userId, String conversationId) {
            synchronized (InMemoryDispatchStore.this) {
                return previews.values().stream()
                        .filter(p -> Objects.equals(tenantId, p.getTenantId()) && Objects.equals(userId, p.getUserId())
                                && conversationId != null && conversationId.equals(p.getConversationId()))
                        .max(Comparator.comparingLong(p -> previewOrder.get(p.getId())))
                        .map(InMemoryDispatchStore::copy);
            }
        }

        @Override
        public List<DispatchPreview> active(String conversationId) {
            synchronized (InMemoryDispatchStore.this) {
                return previews.values().stream()
                        .filter(p -> conversationId != null && conversationId.equals(p.getConversationId())
                                && DispatchPreview.ACTIVE.equals(p.getStatus()))
                        .map(InMemoryDispatchStore::copy).toList();
            }
        }

        @Override
        public List<DispatchPreview> byConversation(String conversationId) {
            synchronized (InMemoryDispatchStore.this) {
                return previews.values().stream().filter(p -> Objects.equals(conversationId, p.getConversationId()))
                        .map(InMemoryDispatchStore::copy).toList();
            }
        }

        @Override
        public boolean transition(String previewId, String fromStatus, String toStatus, String reason, LocalDateTime now) {
            synchronized (InMemoryDispatchStore.this) {
                DispatchPreview p = previews.get(previewId);
                if (p == null || !fromStatus.equals(p.getStatus())) {
                    return false;
                }
                p.setStatus(toStatus);
                p.setStatusReason(reason);
                p.setUpdatedAt(now);
                return true;
            }
        }
        @Override
        public void insertBuilding(DispatchPreview preview) {
            insert(preview, List.of());
        }

        @Override
        public void appendItems(List<DispatchPreviewItem> items) {
            if (items.isEmpty()) return;
            synchronized (InMemoryDispatchStore.this) {
                String previewId = items.get(0).getPreviewId();
                if (!DispatchPreview.BUILDING.equals(previews.get(previewId).getStatus())) {
                    throw new IllegalStateException("preview is not building");
                }
                List<DispatchPreviewItem> stored = previewItems.get(previewId);
                for (DispatchPreviewItem item : items) {
                    DispatchPreviewItem c = copy(item, new DispatchPreviewItem());
                    c.setId(sequence.incrementAndGet());
                    stored.add(c);
                }
            }
        }

        @Override
        public void updateBuilding(DispatchPreview preview) {
            synchronized (InMemoryDispatchStore.this) {
                if (!DispatchPreview.BUILDING.equals(previews.get(preview.getId()).getStatus())) {
                    throw new IllegalStateException("preview is not building");
                }
                previews.put(preview.getId(), copy(preview));
            }
        }

        @Override
        public void deleteBuilding(String previewId) {
            deleteBuildingBefore(previewId, null);
        }

        @Override
        public void deleteBuildingBefore(String previewId, LocalDateTime cutoff) {
            synchronized (InMemoryDispatchStore.this) {
                DispatchPreview preview = previews.get(previewId);
                if (preview != null && DispatchPreview.BUILDING.equals(preview.getStatus())
                        && (cutoff == null || preview.getUpdatedAt().isBefore(cutoff))) {
                    previews.remove(previewId);
                    previewItems.remove(previewId);
                    previewOrder.remove(previewId);
                }
            }
        }

    }

    private final class Plans implements PlanRepository {

        @Override
        public boolean hasOtherStartedByPreview(String previewId, String planId) {
            synchronized (InMemoryDispatchStore.this) {
                return plans.values().stream().anyMatch(p -> previewId.equals(p.getPreviewId())
                        && !planId.equals(p.getId())
                        && List.of(DispatchPlan.EXECUTING, DispatchPlan.EXECUTED,
                                DispatchPlan.REVIEW_REQUIRED).contains(p.getStatus()));
            }
        }

        @Override
        public boolean hasStartedByPreview(String previewId) {
            synchronized (InMemoryDispatchStore.this) {
                return plans.values().stream().anyMatch(p -> previewId.equals(p.getPreviewId())
                        && List.of(DispatchPlan.EXECUTING, DispatchPlan.EXECUTED, DispatchPlan.REVIEW_REQUIRED).contains(p.getStatus()));
            }
        }

        @Override
        public void insert(DispatchPlan plan, List<DispatchPlanItem> items) {
            synchronized (InMemoryDispatchStore.this) {
                if (plan.getConversationId() != null && plans.values().stream().anyMatch(p ->
                        plan.getConversationId().equals(p.getConversationId()) && DispatchPlan.PENDING.equals(p.getStatus()))) {
                    throw new DuplicateKeyException("uk_plan_pending");
                }
                if (plans.values().stream().anyMatch(p -> p.getTenantId().equals(plan.getTenantId())
                        && p.getIdempotencyKey().equals(plan.getIdempotencyKey()))) {
                    throw new DuplicateKeyException("uk_plan_idempotency");
                }
                plans.put(plan.getId(), copy(plan));
                List<DispatchPlanItem> stored = new ArrayList<>();
                for (DispatchPlanItem i : items) {
                    DispatchPlanItem c = copy(i, new DispatchPlanItem());
                    c.setId(sequence.incrementAndGet());
                    stored.add(c);
                }
                planItems.put(plan.getId(), stored);
            }
        }

        @Override
        public Optional<DispatchPlan> find(String planId) {
            synchronized (InMemoryDispatchStore.this) {
                return Optional.ofNullable(copy(plans.get(planId)));
            }
        }

        @Override
        public Optional<DispatchPlan> findByIdempotencyKey(String tenantId, String idempotencyKey) {
            synchronized (InMemoryDispatchStore.this) {
                return plans.values().stream()
                        .filter(p -> p.getTenantId().equals(tenantId) && p.getIdempotencyKey().equals(idempotencyKey))
                        .findFirst().map(InMemoryDispatchStore::copy);
            }
        }

        @Override
        public List<DispatchPlan> findAll(Collection<String> planIds) {
            synchronized (InMemoryDispatchStore.this) {
                return planIds.stream().map(plans::get).filter(Objects::nonNull).map(InMemoryDispatchStore::copy).toList();
            }
        }

        @Override
        public List<DispatchPlan> manualPlans(String tenantId, String userId, String reportId, int offset, int size) {
            synchronized (InMemoryDispatchStore.this) {
                return plans.values().stream().filter(p -> tenantId.equals(p.getTenantId()) && userId.equals(p.getUserId())
                        && p.getIdempotencyKey().startsWith("manual:")
                        && planItems.get(p.getId()).stream().anyMatch(i -> reportId.equals(i.getReportId())))
                        .sorted(java.util.Comparator.comparing(DispatchPlan::getCreatedAt).reversed())
                        .skip(offset).limit(size).map(InMemoryDispatchStore::copy).toList();
            }
        }

        @Override
        public boolean retireManualDraftKey(String planId) {
            synchronized (InMemoryDispatchStore.this) {
                var p = plans.get(planId);
                if (p == null || !(DispatchPlan.EXPIRED.equals(p.getStatus()) || DispatchPlan.CANCELLED.equals(p.getStatus())
                        || DispatchPlan.EXECUTED.equals(p.getStatus()))
                        || !p.getIdempotencyKey().startsWith("manual:")
                        || planItems.get(planId).stream().anyMatch(i -> i.getAttemptCount() > 0)) return false;
                p.setIdempotencyKey("retired:" + planId);
                return true;
            }
        }

        @Override
        public List<DispatchPlanItem> items(String planId) {
            synchronized (InMemoryDispatchStore.this) {
                return planItems.getOrDefault(planId, List.of()).stream().map(i -> copy(i, new DispatchPlanItem())).toList();
            }
        }

        @Override
        public List<DispatchPlan> pending(String conversationId) {
            synchronized (InMemoryDispatchStore.this) {
                return plans.values().stream()
                        .filter(p -> conversationId != null && conversationId.equals(p.getConversationId())
                                && DispatchPlan.PENDING.equals(p.getStatus()))
                        .map(InMemoryDispatchStore::copy).toList();
            }
        }

        @Override
        public List<DispatchPlan> pendingByPreview(String previewId) {
            synchronized (InMemoryDispatchStore.this) {
                return plans.values().stream()
                        .filter(p -> previewId.equals(p.getPreviewId()) && DispatchPlan.PENDING.equals(p.getStatus()))
                        .map(InMemoryDispatchStore::copy).toList();
            }
        }

        @Override
        public List<DispatchPlan> byConversation(String conversationId) {
            synchronized (InMemoryDispatchStore.this) {
                return plans.values().stream().filter(p -> Objects.equals(conversationId, p.getConversationId()))
                        .map(InMemoryDispatchStore::copy).toList();
            }
        }

        @Override
        public boolean transition(String planId, String fromStatus, String toStatus, String reason, LocalDateTime now) {
            synchronized (InMemoryDispatchStore.this) {
                DispatchPlan p = plans.get(planId);
                if (p == null || !fromStatus.equals(p.getStatus())) {
                    return false;
                }
                p.setStatus(toStatus);
                p.setStatusReason(reason);
                p.setUpdatedAt(now);
                return true;
            }
        }

        @Override
        public Optional<Long> claim(String planId, String confirmedBy, LocalDateTime now) {
            synchronized (InMemoryDispatchStore.this) {
                DispatchPlan p = plans.get(planId);
                if (p == null || !DispatchPlan.PENDING.equals(p.getStatus()) || !p.getExpiresAt().isAfter(now)) {
                    return Optional.empty();
                }
                p.setStatus(DispatchPlan.EXECUTING);
                p.setConfirmedAt(now);
                p.setExecutionVersion(p.getExecutionVersion() + 1);
                p.setConfirmedBy(confirmedBy);
                p.setUpdatedAt(now);
                return Optional.of(p.getExecutionVersion());
            }
        }

        @Override
        public Optional<Long> claimRetry(String planId, LocalDateTime now) {
            synchronized (InMemoryDispatchStore.this) {
                DispatchPlan p = plans.get(planId);
                if (p == null || !DispatchPlan.EXECUTED.equals(p.getStatus()) || p.getFailedCount() == null || p.getFailedCount() <= 0) {
                    return Optional.empty();
                }
                p.setStatus(DispatchPlan.EXECUTING);
                p.setExecutionVersion(p.getExecutionVersion() + 1);
                p.setUpdatedAt(now);
                return Optional.of(p.getExecutionVersion());
            }
        }

        @Override
        public void updateItem(DispatchPlanItem item, long executionVersion) {
            synchronized (InMemoryDispatchStore.this) {
                if (!isExecuting(item.getPlanId(), executionVersion)) {
                    throw new IllegalStateException("派单执行权已失效");
                }
                List<DispatchPlanItem> list = planItems.get(item.getPlanId());
                list.replaceAll(i -> i.getId().equals(item.getId()) ? copy(item, new DispatchPlanItem()) : i);
            }
        }

        @Override
        public boolean isExecuting(String planId, long executionVersion) {
            synchronized (InMemoryDispatchStore.this) {
                DispatchPlan plan = plans.get(planId);
                return plan != null && DispatchPlan.EXECUTING.equals(plan.getStatus())
                        && java.util.Objects.equals(plan.getExecutionVersion(), executionVersion);
            }
        }

        @Override
        public <T> T withExecutionRight(String planId, long executionVersion, java.util.function.Supplier<T> action) {
            var lock = executionLock(planId);
            lock.lock();
            try {
                if (!isExecuting(planId, executionVersion)) throw new IllegalStateException("派单执行权已失效");
                return action.get();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean finish(String planId, long executionVersion, int successCount, int failedCount, LocalDateTime now) {
            synchronized (InMemoryDispatchStore.this) {
                if (!isExecuting(planId, executionVersion)) return false;
                DispatchPlan p = plans.get(planId);
                p.setStatus(DispatchPlan.EXECUTED);
                p.setStatusReason(null);
                p.setSuccessCount(successCount);
                p.setFailedCount(failedCount);
                p.setFinishedAt(now);
                p.setUpdatedAt(now);
                return true;
            }
        }

        @Override
        public boolean transitionExecution(String planId, long executionVersion, String toStatus, String reason, LocalDateTime now) {
            synchronized (InMemoryDispatchStore.this) {
                return isExecuting(planId, executionVersion)
                        && transition(planId, DispatchPlan.EXECUTING, toStatus, reason, now);
            }
        }

        @Override
        public boolean resolveUnknownItem(DispatchPlanItem item, String expectedStatus, long executionVersion) {
            synchronized (InMemoryDispatchStore.this) {
                DispatchPlan plan = plans.get(item.getPlanId());
                if (plan == null || !DispatchPlan.REVIEW_REQUIRED.equals(plan.getStatus())
                        || plan.getExecutionVersion() != executionVersion) return false;
                List<DispatchPlanItem> list = planItems.get(item.getPlanId());
                if (list == null) return false;
                for (int index = 0; index < list.size(); index++) {
                    DispatchPlanItem current = list.get(index);
                    if (current.getId().equals(item.getId()) && expectedStatus.equals(current.getStatus())
                            && Objects.equals(current.getAttemptCount(), item.getAttemptCount())) {
                        list.set(index, copy(item, new DispatchPlanItem()));
                        return true;
                    }
                }
                return false;
            }
        }

        @Override
        public boolean finishReview(String planId, long executionVersion, int successCount, int failedCount, LocalDateTime now) {
            synchronized (InMemoryDispatchStore.this) {
                DispatchPlan p = plans.get(planId);
                if (p == null || !DispatchPlan.REVIEW_REQUIRED.equals(p.getStatus())
                        || p.getExecutionVersion() != executionVersion) return false;
                p.setStatus(DispatchPlan.EXECUTED);
                p.setStatusReason(null);
                p.setSuccessCount(successCount);
                p.setFailedCount(failedCount);
                p.setFinishedAt(now);
                p.setUpdatedAt(now);
                return true;
            }
        }

        @Override
        public void touchExecuting(String planId, long executionVersion, LocalDateTime now) {
            synchronized (InMemoryDispatchStore.this) {
                if (isExecuting(planId, executionVersion)) plans.get(planId).setUpdatedAt(now);
            }
        }

        @Override
        public List<DispatchPlan> staleExecuting(LocalDateTime cutoff) {
            synchronized (InMemoryDispatchStore.this) {
                return plans.values().stream().filter(p -> DispatchPlan.EXECUTING.equals(p.getStatus())
                        && p.getUpdatedAt().isBefore(cutoff)).map(InMemoryDispatchStore::copy).toList();
            }
        }

        @Override
        public boolean markStaleForReview(String planId, LocalDateTime cutoff, LocalDateTime now) {
            var lock = executionLock(planId);
            lock.lock();
            try {
                synchronized (InMemoryDispatchStore.this) {
                    DispatchPlan plan = plans.get(planId);
                    if (plan == null || !DispatchPlan.EXECUTING.equals(plan.getStatus())
                            || !plan.getUpdatedAt().isBefore(cutoff)) return false;
                    plan.setStatus(DispatchPlan.REVIEW_REQUIRED);
                    plan.setExecutionVersion(plan.getExecutionVersion() + 1);
                    plan.setStatusReason(com.example.report.dispatch.StateReason.EXECUTION_INTERRUPTED);
                    plan.setUpdatedAt(now);
                    return true;
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
