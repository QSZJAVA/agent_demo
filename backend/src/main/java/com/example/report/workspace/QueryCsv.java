package com.example.report.workspace;

import com.example.report.assistant.BusinessResult;
import com.example.report.common.ApiException;
import com.example.report.operations.SensitiveData;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 查询快照的有界 CSV 编码器；含标题及元数据在内的全部单元格按文本前缀输出，避免公式执行、长标识及金额被表格软件改写。
 * 原始小数不经浮点，UTF-8 BOM与CRLF便于表格软件读取；出站文本沿用HTTP脱敏策略。
 */
public final class QueryCsv {
    private final ByteArrayOutputStream output=new ByteArrayOutputStream();
    private final List<String> fields;
    public QueryCsv(BusinessResult result) {
        output.writeBytes(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf});fields=result.columns().stream().map(f->f.name()).toList();
        line(List.of("查询运行",result.snapshot().id()));line(List.of("来源",SensitiveData.text(result.source())));
        line(List.of("观测时间",result.observedAt()));line(List.of("到期时间",result.snapshot().expiresAt()));line(List.of("完整匹配数",String.valueOf(result.total())));
        line(List.of("精度与安全","全部单元格使用单引号文本前缀；金额及编号保留完整字符，不作为公式执行"));
        line(result.columns().stream().map(f->f.description()+" ["+f.name()+"]").toList());
    }
    /** 添加一行授权事实，数据中所有标量和特殊字符都经统一编码，不能插入额外公式列。 */
    public void add(Map<String,Object> row) {
        var safe=com.example.report.common.JsonUtil.MAPPER.convertValue(SensitiveData.value(row),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        line(fields.stream().map(safe::get).toList());
    }
    /** 取得完整文件；文件不会被追加或更新，下载必须另行授权。 */
    public byte[] bytes(){return output.toByteArray();}
    /** CSV没有可靠类型声明，单引号前缀同时保护精度与公式；空值仍为空单元格，不改写为零。 */
    public static String cell(Object value){return value==null?"":"'"+value;}
    /** 所有输入在写入边界统一转文本和转义，目录描述及来源元数据也不能成为公式入口。 */
    private void line(List<?> values){String line=values.stream().map(QueryCsv::cell).map(v->"\""+v.replace("\"","\"\"")+"\"").collect(java.util.stream.Collectors.joining(","))+"\r\n";
        byte[] bytes=line.getBytes(StandardCharsets.UTF_8);if((long)output.size()+bytes.length>32L*1024*1024)throw new ApiException(422,"导出文件超过32MiB预算，请缩小查询范围");output.writeBytes(bytes);}
}
