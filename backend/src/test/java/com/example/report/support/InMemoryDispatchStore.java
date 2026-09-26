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
    }

    private final class Plans implements PlanRepository {

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
        public boolean claim(String planId, String confirmedBy, LocalDateTime now) {
            synchronized (InMemoryDispatchStore.this) {
                DispatchPlan p = plans.get(planId);
                if (p == null || !DispatchPlan.PENDING.equals(p.getStatus()) || !p.getExpiresAt().isAfter(now)) {
                    return false;
                }
                p.setStatus(DispatchPlan.EXECUTING);
                p.setConfirmedAt(now);
                p.setConfirmedBy(confirmedBy);
                p.setUpdatedAt(now);
                return true;
            }
        }

        @Override
        public void updateItem(DispatchPlanItem item) {
            synchronized (InMemoryDispatchStore.this) {
                List<DispatchPlanItem> list = planItems.get(item.getPlanId());
                list.replaceAll(i -> i.getId().equals(item.getId()) ? copy(item, new DispatchPlanItem()) : i);
            }
        }

        @Override
        public boolean finish(String planId, int successCount, int failedCount, LocalDateTime now) {
            synchronized (InMemoryDispatchStore.this) {
                DispatchPlan p = plans.get(planId);
                if (p == null || !DispatchPlan.EXECUTING.equals(p.getStatus())) {
                    return false;
                }
                p.setStatus(DispatchPlan.EXECUTED);
                p.setStatusReason(null);
                p.setSuccessCount(successCount);
                p.setFailedCount(failedCount);
                p.setFinishedAt(now);
                p.setUpdatedAt(now);
                return true;
            }
        }
    }
}
