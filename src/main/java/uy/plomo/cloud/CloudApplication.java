package uy.plomo.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.core.context.SecurityContextHolder;


@SpringBootApplication
@EnableScheduling
public class CloudApplication {
    public static void main(String[] args) {
        SecurityContextHolder.setStrategyName(
                SecurityContextHolder.MODE_INHERITABLETHREADLOCAL
        );
        SpringApplication.run(CloudApplication.class, args);
    }

}
