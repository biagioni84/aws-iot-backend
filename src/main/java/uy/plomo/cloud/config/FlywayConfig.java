package uy.plomo.cloud.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class FlywayConfig implements BeanFactoryPostProcessor {

    /**
     * Forces entityManagerFactory to wait for the flyway bean.
     * Spring Boot 4 does not auto-wire this dependency when Flyway
     * is configured manually, so we add it programmatically.
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (beanFactory.containsBeanDefinition("entityManagerFactory")) {
            BeanDefinition bd = beanFactory.getBeanDefinition("entityManagerFactory");
            bd.setDependsOn("flyway");
        }
    }

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
    }
}