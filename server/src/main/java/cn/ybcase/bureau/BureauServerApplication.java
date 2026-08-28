package cn.ybcase.bureau;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "cn.ybcase")
@EntityScan(basePackages = "cn.ybcase")
@EnableJpaRepositories(basePackages = "cn.ybcase")
@EnableScheduling
public class BureauServerApplication {

    public static void main(String[] args) {
        // 业务日期（立案日/告知日/送达日/各类期限起算）全部走 LocalDate.now()，取 JVM 默认时区。
        // 容器默认 UTC 时，中国 0:00-8:00 之间算出的"今天"比实际早一天——法律文书日期会记错，
        // 且事后无法从数据判断哪些是错的。部署侧已设 TZ，此处再兜一层：允许用
        // -Duser.timezone 或 YBCASE_TIMEZONE 覆盖（跨省部署时改这一处即可）。
        applyBusinessTimeZone(System.getProperty("user.timezone"),
                System.getenv().getOrDefault("YBCASE_TIMEZONE", "Asia/Shanghai"));
        SpringApplication.run(BureauServerApplication.class, args);
    }

    /**
     * 决定业务时区并应用；返回最终生效的时区 ID（供测试断言）。
     * current 为空或是 UTC/GMT（容器默认）时改用 want，否则尊重部署方的显式设置。
     */
    static String applyBusinessTimeZone(String current, String want) {
        String effective = (current == null || current.isBlank()
                || "UTC".equals(current) || "GMT".equals(current)) ? want : current;
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(effective));
        System.setProperty("user.timezone", effective);
        return effective;
    }
}
