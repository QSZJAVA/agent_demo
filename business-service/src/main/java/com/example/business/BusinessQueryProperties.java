package com.example.business;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

/** 业务查询的数据源单位配置；只为已明确币种的报表声明单位，来源字段自行提供币种时优先使用来源。 */
@Component
@ConfigurationProperties(prefix="business.query")
public class BusinessQueryProperties {
    /** 稳定报表标识到币种代码的映射，缺失表示未声明，不能根据金额或语言猜测。 */
    private Map<String,String> reportCurrencies=new LinkedHashMap<>();
    public Map<String,String> getReportCurrencies(){return Map.copyOf(reportCurrencies);}
    public void setReportCurrencies(Map<String,String> values){
        if(values==null || values.values().stream().anyMatch(v->v==null || !v.matches("[A-Z]{3}")))throw new IllegalArgumentException("报表币种配置无效");
        reportCurrencies=new LinkedHashMap<>(values);
    }
}
