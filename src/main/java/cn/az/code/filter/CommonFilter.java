package cn.az.code.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
public class CommonFilter implements WebFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommonFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        LOGGER.info("wow, a filter, your uri is {}", exchange.getRequest().getURI());
        return chain.filter(exchange);
    }

}
