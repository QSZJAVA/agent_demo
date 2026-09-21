package com.example.report.dispatch;

import com.example.report.rule.Candidate;

/**
 * 现有派单接口的抽象：真实系统在这里调用已有的派单服务
 */
public interface DispatchGateway {

    Outcome dispatch(Candidate candidate);

    record Outcome(boolean success, String message) {
        public static Outcome ok() {
            return new Outcome(true, "派单成功");
        }

        public static Outcome fail(String message) {
            return new Outcome(false, message);
        }
    }
}
