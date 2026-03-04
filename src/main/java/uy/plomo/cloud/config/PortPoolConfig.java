package uy.plomo.cloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uy.plomo.cloud.platform.PortPool;

@Configuration
public class PortPoolConfig {

    @Bean
    public PortPool portPool(
            @Value("${port.pool.start:9000}") int start,
            @Value("${port.pool.end:10000}") int end
    ) {
        return new PortPool(start, end);
    }
}