package com.example.report.rule;

import com.example.report.common.ApiException;
import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.Feature;
import com.googlecode.aviator.Options;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 规则表达式引擎：Aviator。只开放纯表达式能力（关闭 if/for/lambda/new/模块导入等），编译结果带缓存。
 */
@Slf4j
@Component
public class RuleEngine {

    private final AviatorEvaluatorInstance evaluator;

    public RuleEngine() {
        this.evaluator = AviatorEvaluator.newInstance();
        // 只允许纯表达式，防止规则里执行语句、创建对象或导入模块
        this.evaluator.setOption(Options.FEATURE_SET, Feature.asSet());
    }

    /** 语法校验，失败抛出带位置信息的业务异常 */
    public void validate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new ApiException("规则表达式不能为空");
        }
        try {
            evaluator.validate(expression);
        } catch (Exception e) {
            throw new ApiException("表达式语法错误：" + e.getMessage());
        }
    }

    /** 表达式引用的变量名，用于和事实模型字段比对*/
    public Set<String> variables(String expression) {
        validate(expression);
        Expression compiled = evaluator.compile(expression, true);
        return new LinkedHashSet<>(compiled.getVariableNames());
    }

    /** 在事实模型上求值；非布尔结果视为不命中 */
    public boolean matches(String expression, Map<String, Object> facts) {
        Expression compiled = evaluator.compile(expression, true);
        Object result = compiled.execute(new HashMap<>(facts));
        if (result instanceof Boolean b) {
            return b;
        }
        log.warn("规则表达式返回了非布尔值，按不命中处理：{} -> {}", expression, result);
        return false;
    }
}
