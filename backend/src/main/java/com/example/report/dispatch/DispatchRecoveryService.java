package com.example.report.dispatch;

import com.example.report.dispatch.store.PlanRepository;
import com.example.report.entity.DispatchPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 进程崩溃后的保守恢复：未知外部结果只进入待核对，不重新发送。 */
@Slf4j
@Component
public class DispatchRecoveryService {
    private final PlanRepository plans;

    public DispatchRecoveryService(PlanRepository plans) {
        this.plans = plans;
    }

    @Scheduled(fixedDelayString = "${agent.dispatch-recovery-ms:60000}")
    public void recover() {
        LocalDateTime now = LocalDateTime.now();
        for (DispatchPlan plan : plans.staleExecuting(now.minusMinutes(5))) {
            if (plans.markStaleForReview(plan.getId(), now.minusMinutes(5), now)) {
                log.error("清单执行超过五分钟无进展，已置为待核对 plan={}", plan.getId());
            }
        }
    }
}
