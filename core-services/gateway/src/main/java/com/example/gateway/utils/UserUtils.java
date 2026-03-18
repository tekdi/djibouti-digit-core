package com.example.gateway.utils;

import com.example.gateway.config.ApplicationProperties;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.User;
import com.example.gateway.model.UserDetailResponse;
import com.example.gateway.model.UserSearchRequest;
import org.egov.common.utils.MultiStateInstanceUtil;
import org.egov.tracer.model.CustomException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;

import static com.example.gateway.constants.GatewayConstants.CORRELATION_ID_HEADER_NAME;
import static com.example.gateway.constants.GatewayConstants.REQUEST_TENANT_ID_KEY;

@Slf4j
@Component
public class UserUtils {

    private RestTemplate restTemplate;

    private ApplicationProperties applicationProperties;

    private MultiStateInstanceUtil multiStateInstanceUtil;

    @Value("#{${egov.statelevel.tenant.map:{}}}")
    private Map<String, String> stateLevelTenantMap;

    @Value("${egov.statelevel.tenant:}")
    private String stateLevelTenant;

    public UserUtils (RestTemplate restTemplate, ApplicationProperties applicationProperties) {
        this.restTemplate = restTemplate;
        this.applicationProperties = applicationProperties;
    }

    public Map<String, String> getStateLevelTenantMap() {
        return stateLevelTenantMap;
    }

    public String getStateLevelTenant() {
        return stateLevelTenant;
    }

    public User getUser(String authToken) {
        String authURL = String.format("%s%s%s", applicationProperties.getAuthServiceHost(), applicationProperties.getAuthUri(), authToken);

        User user;

        try {
            user = restTemplate.postForObject(authURL, null, User.class);
        } catch (Exception e) {
            throw new CustomException("Exception occurred while fetching user: ", e.getMessage());
//          throw new CustomException("Exception occurred while fetching user: ", "Error while authenticating the auth token");
        }

        return user;
    }

    @Cacheable(value = "systemUser" , sync = true)
    public User fetchSystemUser(String tenantId, String correlationId) {

        UserSearchRequest userSearchRequest =new UserSearchRequest();
        userSearchRequest.setRoleCodes(Collections.singletonList("ANONYMOUS"));
        userSearchRequest.setUserType("SYSTEM");
        userSearchRequest.setPageSize(1);
        userSearchRequest.setTenantId(tenantId);

        final HttpHeaders headers = new HttpHeaders();
        headers.add(CORRELATION_ID_HEADER_NAME, correlationId);
        if (multiStateInstanceUtil.getIsEnvironmentCentralInstance())
            headers.add(REQUEST_TENANT_ID_KEY, tenantId);
        final HttpEntity<Object> httpEntity = new HttpEntity<>(userSearchRequest, headers);

        StringBuilder uri = new StringBuilder(applicationProperties.getUserSearchURI());
        User user = null;
        try {
            UserDetailResponse response = restTemplate.postForObject(uri.toString(), httpEntity, UserDetailResponse.class);
            if (!CollectionUtils.isEmpty(response.getUser()))
                user = response.getUser().get(0);
        } catch(Exception e) {
            log.error("Exception while fetching system user: ",e);
        }

        /*if(user == null)
            throw new CustomException("NO_SYSTEUSER_FOUND","No system user found");*/

        return user;
    }

}