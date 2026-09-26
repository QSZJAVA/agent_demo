package com.example.report.dispatch;

import com.example.report.catalog.query.DispatchStatusWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 派单接口的模拟实现：通过报表查询适配器把记录标记为已派单。真实系统替换为调用现有派单接口。
 * 不再按报表类型分支：任何配置了派单状态列的标准报表都能直接派单。
 */
@Slf4j
@Component
public class MockDispatchGateway implements DispatchGateway {

    @Override
    public Outcome dispatch(DispatchRequest request) {
        if (!(request.report().adapter() instanceof DispatchStatusWriter writer)) {
            return Outcome.fail("NOT_SUPPORTED", "该报表未配置派单状态回写，无法派单");
        }
        if (!writer.markDispatched(request.tenantId(), request.record().recordId(), LocalDateTime.now())) {
            return Outcome.fail("RECORD_NOT_PENDING", "记录不存在或已派单");
        }
        log.info("[模拟派单接口] {} {} {} 金额 {} 派单成功 requestId={}", request.report().reportName(),
                request.record().companyCode(), request.record().docNo(), request.record().amount(), request.externalRequestId());
        return Outcome.ok();
    }
}
