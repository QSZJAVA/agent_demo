package com.example.report.catalog.query;

import com.example.report.assistant.BusinessQuery;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.*;

/**
 * 报表查询条件的保守 SQL 编译器；仅接收受信字段映射与 JDBC 元数据证明，用户值始终参数绑定。
 * 每个 OR 分支可下推其安全 AND 子集；任一分支没有安全条件时整个析取不下推，避免缩小结果集。
 */
public final class BusinessSqlPredicate {
    private BusinessSqlPredicate() { }
    /**
     * 经数据源元数据证明的映射。
     * @param column 受信配置中的列名
     * @param type 共同事实协议中的字段类型
     * @param jdbcType 当前实际 JDBC 列类型
     * @param signed JDBC 数值列是否有符号
     */
    public record Column(String column,String type,int jdbcType,boolean signed) { }
    /**
     * 参数化的必要条件；最终 Java 谓词仍执行完整语义。
     * @param sql 已验证列构成的 SQL，空字符串表示不下推
     * @param parameters 仅包含用户标量值，不包含 SQL 标识符
     */
    public record Predicate(String sql,Map<String,Object> parameters) { }
    /** 根据当前来源类型编译，不猜测派生字段或来源状态显示值的数据库含义。 */
    public static Predicate compile(BusinessQuery query,Map<String,Column> columns) {
        if(query==null || query.conditions().isEmpty())return new Predicate("",Map.of());
        var params=new LinkedHashMap<String,Object>();var groups=new ArrayList<String>();
        for(var group:query.conditions()) {
            var terms=new ArrayList<String>();
            for(var filter:group.allOf()) {
                var column=columns.get(filter.field());if(column==null)continue;
                String term=term(column,filter,params);if(term!=null)terms.add(term);
            }
            if(terms.isEmpty())return new Predicate("",Map.of());
            groups.add("("+String.join(" AND ",terms)+")");
        }
        return new Predicate(String.join(" OR ",groups),Collections.unmodifiableMap(params));
    }
    private static String term(Column column,BusinessQuery.Filter filter,Map<String,Object> params) {
        boolean numeric=Set.of("decimal","integer","long").contains(column.type())
                && StandardReportAdapter.exactNumericMapping(column.type(),column.jdbcType(),column.signed());
        boolean text="string".equals(column.type()) && Set.of(Types.VARCHAR,Types.CHAR,Types.LONGVARCHAR,Types.NVARCHAR,Types.NCHAR,Types.LONGNVARCHAR).contains(column.jdbcType());
        boolean date="date".equals(column.type()) && column.jdbcType()==Types.DATE;
        if(!numeric && !text && !date)return null;
        String col=StandardReportAdapter.quote(column.column());String op=filter.operator();
        if("IS_NULL".equals(op))return col+" IS NULL";
        if("NOT_NULL".equals(op))return col+" IS NOT NULL";
        if(!Set.of("EQ","NE","IN","NOT_IN","GT","GTE","LT","LTE","CONTAINS","STARTS_WITH").contains(op))return null;
        // 字符串比较必须保持 Java 的区分大小写与尾随空格语义；范围排序的 UTF-16/UTF-8 顺序不同，不下推。
        if(text && Set.of("GT","GTE","LT","LTE").contains(op))return null;
        if(!text && Set.of("CONTAINS","STARTS_WITH").contains(op))return null;
        if(text)col="CAST("+col+" AS BINARY)";
        var args=new ArrayList<String>();
        for(String value:filter.values()) {
            String name="bq"+params.size();Object scalar=value;
            if(numeric)scalar=new BigDecimal(value);
            if(date)scalar=java.sql.Date.valueOf(java.time.LocalDate.parse(value));
            params.put(name,scalar);args.add(text?"CAST(:"+name+" AS BINARY)":":"+name);
        }
        if("IN".equals(op) || "NOT_IN".equals(op))return col+("IN".equals(op)?" IN (":" NOT IN (")+String.join(",",args)+")";
        if("CONTAINS".equals(op) || "STARTS_WITH".equals(op))return "LOCATE("+args.get(0)+","+col+")"+("CONTAINS".equals(op)?">0":"=1");
        return col+switch(op){case "EQ"->"=";case "NE"->"<>";case "GT"->">";case "GTE"->">=";case "LT"->"<";default->"<=";}+args.get(0);
    }
}
