package cn.az.code.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class CoffeeConfiguration {

    @Bean
    @ConditionalOnProperty(value = "coffee.rest.enabled", havingValue = "true", matchIfMissing = false)
    RestTemplate restTemplate() {
        return new RestTemplate();
    }

}
