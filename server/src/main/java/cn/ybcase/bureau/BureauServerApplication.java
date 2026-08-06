package cn.ybcase.bureau;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "cn.ybcase")
@EntityScan(basePackages = "cn.ybcase")
@EnableJpaRepositories(basePackages = "cn.ybcase")
public class BureauServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BureauServerApplication.class, args);
    }
}
