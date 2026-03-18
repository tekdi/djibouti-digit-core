package com.example.gateway.filters.pre;

import com.example.gateway.config.ApplicationProperties;
import com.example.gateway.filters.pre.helpers.AuthPreCheckFilterHelper;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;
import org.egov.tracer.model.CustomException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static com.example.gateway.constants.GatewayConstants.*;
import static com.example.gateway.filters.pre.helpers.AuthPreCheckFilterHelper.*;

@Slf4j
@Component
public class AuthPreCheckFilter implements GlobalFilter, Ordered {

    private ModifyRequestBodyGatewayFilterFactory modifyRequestBodyFilter;

    private AuthPreCheckFilterHelper authPreCheckFilterHelper;

    private ApplicationProperties applicationProperties;

    public AuthPreCheckFilter(ModifyRequestBodyGatewayFilterFactory modifyRequestBodyFilter,
            AuthPreCheckFilterHelper authPreCheckFilterHelper,
            ApplicationProperties applicationProperties) {
        this.modifyRequestBodyFilter = modifyRequestBodyFilter;
        this.authPreCheckFilterHelper = authPreCheckFilterHelper;
        this.applicationProperties = applicationProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        String endPointPath = exchange.getRequest().getPath().value();
        String contentType = exchange.getRequest().getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);

        if (applicationProperties.getOpenEndpointsWhitelist().contains(endPointPath)) {
            exchange.getAttributes().put(AUTH_BOOLEAN_FLAG_NAME, Boolean.FALSE);
            log.info(OPEN_ENDPOINT_MESSAGE, endPointPath);
            return chain.filter(exchange);
        } else if (contentType != null
                && (contentType.contains(FORM_DATA_TYPE) || contentType.contains(X_WWW_FORM_URLENCODED_TYPE))) {

            List<String> authTokenHeader = exchange.getRequest().getHeaders().get(AUTH_TOKEN);
            String authToken = (authTokenHeader != null && !authTokenHeader.isEmpty()) ? authTokenHeader.get(0) : null;

            if (!ObjectUtils.isEmpty(authToken)) {
                exchange.getAttributes().put(AUTH_BOOLEAN_FLAG_NAME, Boolean.TRUE);
                exchange.getAttributes().put(AUTH_TOKEN_KEY, authToken);
            } else {
                if (applicationProperties.getMixedModeEndpointsWhitelist().contains(endPointPath)) {
                    log.info(ROUTING_TO_ANONYMOUS_ENDPOINT_MESSAGE, endPointPath);
                    exchange.getAttributes().put(AUTH_BOOLEAN_FLAG_NAME, Boolean.FALSE);
                } else {
                    log.info(ROUTING_TO_PROTECTED_ENDPOINT_RESTRICTED_MESSAGE, endPointPath);
                    throw new CustomException(UNAUTHORIZED_USER_MESSAGE, UNAUTHORIZED_USER_MESSAGE);
                }
            }
            return chain.filter(exchange);
        } else {
            return modifyRequestBodyFilter.apply(new ModifyRequestBodyGatewayFilterFactory.Config()
                    .setRewriteFunction(Map.class, Map.class, authPreCheckFilterHelper))
                    .filter(exchange, chain);
        }
    }

    @Override
    public int getOrder() {
        return 2;
    }

}
