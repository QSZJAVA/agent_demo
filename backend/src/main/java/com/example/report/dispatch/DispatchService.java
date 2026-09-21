package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.conversation.ConversationService;
import com.example.report.entity.ExpenseReport;
import com.example.report.entity.ReceivableReport;
import com.example.report.entity.SalesReport;
import com.example.report.mapper.ExpenseReportMapper;
import com.example.report.mapper.ReceivableReportMapper;
import com.example.report.mapper.SalesReportMapper;
import com.example.report.permission.CurrentUser;
import com.example.report.report.ReportType;
import com.example.report.rule.Candidate;
import com.example.report.rule.RuleCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 派单执行：幂等锁 → 校验清单归属与规则版本 → 逐条调现有派单接口 → 审计 → 结果卡片
 */
@Slf4j
@Service
public class DispatchService {

    public static final String SOURCE_AGENT = "agent";
    public static final String SOURCE_MANUAL = "manual";

    private final PlanStore planStore;
    private final PreviewStore previewStore;
    private final RuleCache ruleCache;
    private final DispatchGateway gateway;
    private final AuditService auditService;
    private final ConversationService conversationService;
    private final ChatMemory chatMemory;
    private final StringRedisTemplate redis;
    private final SalesReportMapper salesMapper;
    private final ReceivableReportMapper receivableMapper;
    private final ExpenseReportMapper expenseMapper;

    public DispatchService(PlanStore planStore, PreviewStore previewStore, RuleCache ruleCache, DispatchGateway gateway,
                           AuditService auditService, ConversationService conversationService, ChatMemory chatMemory,
                           StringRedisTemplate redis, SalesReportMapper salesMapper,
                           ReceivableReportMapper receivableMapper, ExpenseReportMapper expenseMapper) {
        this.planStore = planStore;
        this.previewStore = previewStore;
        this.ruleCache = ruleCache;
        this.gateway = gateway;
        this.auditService = auditService;
        this.conversationService = conversationService;
        this.chatMemory = chatMemory;
        this.redis = redis;
        this.salesMapper = salesMapper;
        this.receivableMapper = receivableMapper;
        this.expenseMapper = expenseMapper;
    }

    /** 执行一份待确认清单（前端确认按钮，或 require-confirm=false 时由工具直接调用） */
    public DispatchResultPayload executePlan(CurrentUser user, String planId) {
        String lockKey = "agent:dispatch:lock:" + planId;
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, user.userId(), Duration.ofMinutes(2));
        if (!Boolean.TRUE.equals(locked)) {
            throw new ApiException(409, "该派单清单正在执行中，请勿重复提交");
        }
        try {
            DispatchPlan plan = planStore.load(user.userId(), planId)
                    .orElseThrow(() -> ApiException.notFound("待确认清单不存在或已过期，请重新预览"));
            if (!Objects.equals(plan.rulesFingerprint(), ruleCache.fingerprint())) {
                planStore.delete(user.userId(), planId);
                throw new ApiException("预览之后派单规则已变更，请重新预览再执行");
            }
            DispatchResultPayload result = run(user, SOURCE_AGENT, plan.conversationId(), plan.previewId(), plan.id(), plan.records());
            planStore.delete(user.userId(), planId);
            previewStore.delete(user.userId(), plan.previewId());
            if (plan.conversationId() != null) {
                conversationService.logCard(plan.conversationId(), user.userId(), "result", result, plan.previewId(), plan.id());
                // 让模型知道这份清单已经执行过（工作记忆），后续对话不会再拿它说事
                chatMemory.add(plan.conversationId(), new AssistantMessage(memoryNote(result)));
            }
            return result;
        } finally {
            redis.delete(lockKey);
        }
    }

    /** 执行清单但不抛业务异常：不存在 / 不归属当前用户返回 empty（测试与幂等场景用） */
    public java.util.Optional<DispatchResultPayload> executePlanSafely(CurrentUser user, String planId) {
        if (planStore.load(user.userId(), planId).isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(executePlan(user, planId));
    }

    /** 取消一份待确认清单 */
    public void cancelPlan(CurrentUser user, String planId) {
        DispatchPlan plan = planStore.load(user.userId(), planId)
                .orElseThrow(() -> ApiException.notFound("待确认清单不存在或已过期"));
        planStore.delete(user.userId(), planId);
        if (plan.conversationId() != null) {
            chatMemory.add(plan.conversationId(), new AssistantMessage("（系统记录）用户取消了待确认的派单清单，未执行任何派单。"));
        }
    }

    /** 报表页手工派单：按记录 ID，只允许操作用户可见公司的记录 */
    public DispatchResultPayload dispatchDirect(CurrentUser user, ReportType type, List<Long> ids) {
        List<Candidate> records = new ArrayList<>();
        for (Long id : ids) {
            Candidate c = loadCandidate(type, id);
            if (c == null || !user.companies().contains(c.companyCode())) {
                throw ApiException.forbidden("记录 " + id + " 不存在或不在您的可见范围内");
            }
            records.add(c);
        }
        return run(user, SOURCE_MANUAL, null, null, null, records);
    }

    private DispatchResultPayload run(CurrentUser user, String source, String conversationId, String previewId, String planId,
                                      List<Candidate> records) {
        List<Candidate> success = new ArrayList<>();
        List<DispatchResultPayload.FailedRecord> failed = new ArrayList<>();
        for (Candidate c : records) {
            DispatchGateway.Outcome outcome;
            try {
                outcome = gateway.dispatch(c);
            } catch (Exception e) {
                log.warn("派单接口调用异常 {} {}", c.docNo(), e.getMessage());
                outcome = DispatchGateway.Outcome.fail("派单接口异常：" + e.getMessage());
            }
            auditService.record(user.userId(), source, conversationId, previewId, planId, c, outcome);
            if (outcome.success()) {
                success.add(c);
            } else {
                failed.add(new DispatchResultPayload.FailedRecord(c.reportType(), c.reportName(), c.docNo(), c.companyCode(), outcome.message()));
            }
        }
        return new DispatchResultPayload(planId, previewId, records.size(), success.size(), failed.size(), success, failed);
    }

    private Candidate loadCandidate(ReportType type, Long id) {
        return switch (type) {
            case SALES -> {
                SalesReport r = salesMapper.selectById(id);
                yield r == null ? null : new Candidate(type.code(), type.label(), r.getId(), r.getOrderNo(), r.getCompanyCode(),
                        r.getProductName(), r.getAmount(), r.getSaleDate(), "手工派单", null, null);
            }
            case RECEIVABLE -> {
                ReceivableReport r = receivableMapper.selectById(id);
                yield r == null ? null : new Candidate(type.code(), type.label(), r.getId(), r.getInvoiceNo(), r.getCompanyCode(),
                        r.getCustomerName(), r.getAmount(), r.getDueDate(), "手工派单", null, null);
            }
            case EXPENSE -> {
                ExpenseReport r = expenseMapper.selectById(id);
                yield r == null ? null : new Candidate(type.code(), type.label(), r.getId(), r.getExpenseNo(), r.getCompanyCode(),
                        r.getExpenseType(), r.getAmount(), r.getExpenseDate(), "手工派单", null, null);
            }
        };
    }

    private static String memoryNote(DispatchResultPayload r) {
        StringBuilder sb = new StringBuilder("（系统记录）派单清单已执行：成功 ")
                .append(r.successCount()).append(" 条，失败 ").append(r.failedCount()).append(" 条。");
        if (!r.success().isEmpty()) {
            sb.append("成功单据：");
            r.success().forEach(c -> sb.append(c.docNo()).append(' '));
        }
        if (!r.failed().isEmpty()) {
            sb.append("失败单据：");
            r.failed().forEach(f -> sb.append(f.docNo()).append('(').append(f.message()).append(") "));
        }
        return sb.toString().trim();
    }
}
