import java.sql.*;

/** 最终演示库的幂等补充数据工具；只允许已核实的专用库，不重置来源记录、清单或会话。 */
public class PrepareFinalDemo {
    /**
     * 校验专用库和最新字段快照结构，在单一事务内补齐缺失演示发票。
     * @param args 唯一参数为最终演示库名；其他库一律拒绝
     * @throws Exception 目标库、结构或既有专用数据不符合预期时终止，不覆盖已存在记录
     */
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !args[0].equals("report_demo")) throw new IllegalArgumentException("Only the final dedicated Demo database is allowed");
        String target=args[0];
        String url="jdbc:mysql://"+System.getenv().getOrDefault("DB_HOST","127.0.0.1")+":"+System.getenv().getOrDefault("DB_PORT","3306")+"/"+target+"?serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
        try(var c=DriverManager.getConnection(url,System.getenv().getOrDefault("DB_USERNAME","root"),System.getenv("DB_PASSWORD"))) {
            try(var q=c.createStatement();var r=q.executeQuery("SELECT DATABASE()")){r.next();if(!target.equals(r.getString(1)))throw new IllegalStateException("Database ownership check failed");}
            try(var q=c.createStatement();var r=q.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('dispatch_preview_item','dispatch_plan_item') AND column_name='fields_json'")){r.next();if(r.getInt(1)!=2)throw new IllegalStateException("Latest field snapshot schema required");}
            c.setAutoCommit(false);
            // 使用明确的演示单据标识；重复运行只检查已存在数据，绝不复位派单状态或覆盖业务编辑。
            for(int i=1;i<=2;i++) {
                String document="INV-V3-DEMO-"+i;
                try(var q=c.prepareStatement("SELECT company_code,customer_id,customer_name,amount FROM report_receivable WHERE tenant_id='T001' AND invoice_no=?")) {
                    q.setString(1,document);
                    try(var r=q.executeQuery()) {if(r.next()) {
                        if(!"A".equals(r.getString(1)) || !"DEMO-V3-QINGLAN".equals(r.getString(2)) || !"青岚设备有限公司".equals(r.getString(3)) || r.getBigDecimal(4).compareTo(java.math.BigDecimal.valueOf(10+i))!=0)
                            throw new IllegalStateException("Dedicated seed record differs; no overwrite performed");
                        continue;
                    }}
                }
                try(var q=c.prepareStatement("INSERT INTO report_receivable(tenant_id,company_code,invoice_no,customer_id,customer_name,customer_aliases,amount,due_date) VALUES('T001','A',?,'DEMO-V3-QINGLAN','青岚设备有限公司','[\"青岚设备\"]',?,'2026-10-06')")) {
                    q.setString(1,document);q.setInt(2,10+i);q.executeUpdate();
                }
            }
            c.commit();System.out.println("Final Demo supplemental records verified; existing business state preserved.");
        }
    }
}
