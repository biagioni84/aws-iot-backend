package uy.plomo.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CloudApplication {
    public static void main(String[] args) {
        // FIX: eliminado SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL).
        // Causaba split-brain con el SecurityContextHolderStrategy bean de SecurityConfig.
        SpringApplication.run(CloudApplication.class, args);
    }
}