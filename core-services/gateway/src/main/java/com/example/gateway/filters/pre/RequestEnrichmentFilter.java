package com.example.gateway.filters.pre;

import static com.example.gateway.constants.GatewayConstants.CORRELATION_ID_HEADER_NAME;
import static com.example.gateway.constants.GatewayConstants.CORRELATION_ID_KEY;
import static com.example.gateway.constants.GatewayConstants.REQUEST_TENANT_ID_KEY;
import static com.example.gateway.constants.GatewayConstants.TENANTID_MDC;
import com.example.gateway.constants.GatewayConstants;
import com.example.gateway.filters.pre.helpers.RequestEnrichmentFilterHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;
import org.egov.common.utils.MultiStateInstanceUtil;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Component
public class RequestEnrichmentFilter implements GlobalFilter, Ordered {

    private ModifyRequestBodyGatewayFilterFactory modifyRequestBodyFilter;

    private RequestEnrichmentFilterHelper requestEnrichmentFilterHelper;

    private MultiStateInstanceUtil centralInstanceUtil;

    public RequestEnrichmentFilter(ModifyRequestBodyGatewayFilterFactory modifyRequestBodyFilter,
            RequestEnrichmentFilterHelper requestEnrichmentFilterHelper,
            MultiStateInstanceUtil centralInstanceUtil) {
        this.modifyRequestBodyFilter = modifyRequestBodyFilter;
        this.requestEnrichmentFilterHelper = requestEnrichmentFilterHelper;
        this.centralInstanceUtil = centralInstanceUtil;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String contentType = exchange.getRequest().getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);

        String correlationId = (String) exchange.getAttributes().get(CORRELATION_ID_KEY);
        String tenantId = (String) exchange.getAttributes().get(TENANTID_MDC);

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(headers -> {
                            headers.set(GatewayConstants.PASS_THROUGH_GATEWAY_HEADER_NAME,
                                    GatewayConstants.PASS_THROUGH_GATEWAY_HEADER_VALUE);
                            if (correlationId != null) {
                                headers.set(CORRELATION_ID_HEADER_NAME, correlationId);
                            }
                            if (centralInstanceUtil.getIsEnvironmentCentralInstance() && tenantId != null) {
                                headers.set(REQUEST_TENANT_ID_KEY, tenantId);
                            }
                        })
                        .build())
                .build();

        if (contentType == null || (contentType.contains("multipart/form-data")
                || contentType.contains("application/x-www-form-urlencoded"))) {
            return chain.filter(mutatedExchange);
        } else {
            return modifyRequestBodyFilter.apply(new ModifyRequestBodyGatewayFilterFactory.Config()
                    .setRewriteFunction(Map.class, Map.class, requestEnrichmentFilterHelper))
                    .filter(mutatedExchange, chain);
        }

    }

    @Override
    public int getOrder() {
        return 6;
    }
}
