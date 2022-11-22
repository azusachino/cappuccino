package cn.az.code.config;

import java.util.Collections;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.Filter;

@Configuration
public class CommonConfiguration {

    @Bean
    @ConditionalOnProperty(value = "cup.rest.enabled", havingValue = "true", matchIfMissing = false)
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public FilterRegistrationBean<Filter> commRegistrationBean(@Qualifier("commonFilter") Filter f) {
        var frb = new FilterRegistrationBean<>();
        frb.setOrder(0);
        frb.setFilter(f);
        frb.setUrlPatterns(Collections.singletonList("/*"));
        return frb;
    }
}
