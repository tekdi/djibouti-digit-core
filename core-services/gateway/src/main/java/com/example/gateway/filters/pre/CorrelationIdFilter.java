package com.example.gateway.filters.pre;

import com.example.gateway.config.ApplicationProperties;
import com.example.gateway.filters.pre.helpers.CorrelationIdFilterHelper;
import com.example.gateway.utils.CommonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.*;

import static com.example.gateway.constants.GatewayConstants.*;

@Slf4j
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    private ModifyRequestBodyGatewayFilterFactory modifyRequestBodyFilter;
    private CorrelationIdFilterHelper correlationIdFilterHelper;
    private ApplicationProperties applicationProperties;

    private final CommonUtils commonUtils;

    public CorrelationIdFilter(ModifyRequestBodyGatewayFilterFactory modifyRequestBodyGatewayFilter,
            CorrelationIdFilterHelper correlationIdFilterHelper, ApplicationProperties applicationProperties,
            CommonUtils commonUtils) {

        this.modifyRequestBodyFilter = modifyRequestBodyGatewayFilter;
        this.correlationIdFilterHelper = correlationIdFilterHelper;
        this.applicationProperties = applicationProperties;
        this.commonUtils = commonUtils;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        String contentType = exchange.getRequest().getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);

        if (contentType == null
                || (contentType.contains(FORM_DATA_TYPE) || contentType.contains(X_WWW_FORM_URLENCODED_TYPE))) {

            String requestURI = exchange.getRequest().getPath().value();
            String correlationId = UUID.randomUUID().toString();
            MDC.put(CORRELATION_ID_KEY, correlationId);
            exchange.getAttributes().put(CORRELATION_ID_KEY, correlationId);
            log.debug(RECEIVED_REQUEST_MESSAGE, requestURI);
            return chain.filter(exchange);

        } else {
            return modifyRequestBodyFilter.apply(new ModifyRequestBodyGatewayFilterFactory.Config()
                    .setRewriteFunction(Map.class, Map.class, correlationIdFilterHelper)).filter(exchange, chain);
        }
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
