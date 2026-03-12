package org.egov.user.web.controller;

import static org.egov.tracer.http.HttpUtils.isInterServiceCall;
import static org.springframework.util.CollectionUtils.isEmpty;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.validation.Valid;

import org.egov.common.contract.response.ResponseInfo;
import org.egov.tracer.model.CustomException;
import org.egov.user.domain.model.UpdateRequest;
import org.egov.user.domain.model.UpdateResponse;
import org.egov.user.domain.model.User;
import org.egov.user.domain.model.UserDetail;
import org.egov.user.domain.model.UserSearchCriteria;
import org.egov.user.domain.service.TokenService;
import org.egov.user.domain.service.UserService;
import org.egov.user.web.contract.CreateUserRequest;
import org.egov.user.web.contract.UserDetailResponse;
import org.egov.user.web.contract.UserRequest;
import org.egov.user.web.contract.UserSearchRequest;
import org.egov.user.web.contract.UserSearchResponse;
import org.egov.user.web.contract.UserSearchResponseContent;
import org.egov.user.web.contract.auth.CustomUserDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
public class UserController {

    private UserService userService;
    private TokenService tokenService;

    @Value("${mobile.number.validation.workaround.enabled}")
    private String mobileValidationWorkaroundEnabled;

    @Value("${otp.validation.register.mandatory}")
    private boolean IsValidationMandatory;

    @Value("${citizen.registration.withlogin.enabled}")
    private boolean isRegWithLoginEnabled;

    @Value("${egov.user.search.default.size}")
    private Integer defaultSearchSize;

    @Autowired
    public UserController(UserService userService, TokenService tokenService) {
        this.userService = userService;
        this.tokenService = tokenService;
    }

    /**
     * end-point to create the citizen with otp.Here otp is mandatory to create
     * citizen.
     *
     * @param createUserRequest
     * @return
     */
    @PostMapping("/citizen/_create")
    public Object createCitizen(@RequestBody @Valid CreateUserRequest createUserRequest) {
        log.info("Received Citizen Registration Request  " + createUserRequest);
        User user = createUserRequest.toDomain(true);
        user.setOtpValidationMandatory(IsValidationMandatory);
        if (isRegWithLoginEnabled) {
            Object object = userService.registerWithLogin(user, createUserRequest.getRequestInfo());
            return new ResponseEntity<>(object, HttpStatus.OK);
        }
        User createdUser = userService.createCitizen(user, createUserRequest.getRequestInfo());
        return createResponse(createdUser);
    }

    /**
     * end-point to create the user without otp validation.
     *
     * @param createUserRequest
     * @param headers
     * @return
     */
    @PostMapping("/users/_createnovalidate")
    public UserDetailResponse createUserWithoutValidation(@RequestBody @Valid CreateUserRequest createUserRequest,
            @RequestHeader HttpHeaders headers) {

        User user = createUserRequest.toDomain(true);
        user.setMobileValidationMandatory(isMobileValidationRequired(headers));
        user.setOtpValidationMandatory(false);
        final User newUser = userService.createUser(user, createUserRequest.getRequestInfo());
        return createResponse(newUser);
    }

    /**
     * end-point to search the users by providing userSearchRequest. In Request
     * if there is no active filed value, it will fetch only active users
     *
     * @param request
     * @return
     */
    @PostMapping("/_search")
    public UserSearchResponse get(@RequestBody @Valid UserSearchRequest request, @RequestHeader HttpHeaders headers) {

        log.info("Received User search Request  " + request);
        if (request.getActive() == null) {
            request.setActive(true);
        }
        return searchUsers(request, headers);
    }

    /**
     * end-point to search the users by providing userSearchRequest. In Request
     * if there is no active filed value, it will fetch all(active & inactive)
     * users.
     *
     * @param request
     * @return
     */
    @PostMapping("/v1/_search")
    public UserSearchResponse getV1(@RequestBody UserSearchRequest request, @RequestHeader HttpHeaders headers) {
        return searchUsers(request, headers);
    }

    /**
     * end-point to fetch the user details by access-token
     *
     * @param accessToken
     * @return
     */
    @PostMapping("/_details")
    public CustomUserDetails getUser(@RequestParam(value = "access_token") String accessToken) {
        final UserDetail userDetail = tokenService.getUser(accessToken);
        return new CustomUserDetails(userDetail);
        // no encrypt/decrypt
    }

    /**
     * end-point to update the user details without otp validations.
     *
     * @param createUserRequest
     * @param headers
     * @return
     */
    @PostMapping("/users/_updatenovalidate")
    public UpdateResponse updateUserWithoutValidation(@RequestBody final @Valid CreateUserRequest createUserRequest,
            @RequestHeader HttpHeaders headers) {
        User user = createUserRequest.toDomain(false);
        user.setMobileValidationMandatory(isMobileValidationRequired(headers));
        final User updatedUser = userService.updateWithoutOtpValidation(user, createUserRequest.getRequestInfo());
        return createResponseforUpdate(updatedUser);
    }

    /**
     * end-point to update user profile.
     *
     * @param createUserRequest
     * @return
     */
    @PostMapping("/profile/_update")
    public UpdateResponse patch(@RequestBody final @Valid CreateUserRequest createUserRequest) {
        log.info("Received Profile Update Request  " + createUserRequest);
        User user = createUserRequest.toDomain(false);
        final User updatedUser = userService.partialUpdate(user, createUserRequest.getRequestInfo());
        return createResponseforUpdate(updatedUser);
    }

    private UserDetailResponse createResponse(User newUser) {
        UserRequest userRequest = new UserRequest(newUser);
        ResponseInfo responseInfo = ResponseInfo.builder().status(String.valueOf(HttpStatus.OK.value())).build();
        return new UserDetailResponse(responseInfo, Collections.singletonList(userRequest));
    }

    private UpdateResponse createResponseforUpdate(User newUser) {
        UpdateRequest updateRequest = new UpdateRequest(newUser);
        ResponseInfo responseInfo = ResponseInfo.builder().status(String.valueOf(HttpStatus.OK.value())).build();
        return new UpdateResponse(responseInfo, Collections.singletonList(updateRequest));
    }

    private UserSearchResponse searchUsers(@RequestBody UserSearchRequest request, HttpHeaders headers) {

        UserSearchCriteria searchCriteria = request.toDomain();
        boolean isInterService = isInterServiceCall(headers);
        log.info("User Search - Headers: {}, isInterService: {}", headers, isInterService);

        // Check if user has STUDIO_ADMIN or BPA_ARCHITECT role
        boolean hasSearchAccess = false;
        if (request.getRequestInfo() != null && request.getRequestInfo().getUserInfo() != null) {
            org.egov.common.contract.request.User userInfo = request.getRequestInfo().getUserInfo();
            if (userInfo.getRoles() != null) {
                hasSearchAccess = userInfo.getRoles().stream()
                        .anyMatch(role -> role.getCode() != null &&
                                (role.getCode().equalsIgnoreCase("STUDIO_ADMIN") ||
                                        role.getCode().equalsIgnoreCase("BPA_ARCHITECT")));
            }
        }

        // Restrict search for all unauthorized roles
        boolean shouldRestrict = false;
        if (request.getRequestInfo() != null && request.getRequestInfo().getUserInfo() != null) {
            if (!hasSearchAccess) {
                // If searching by "Discovery Fields" (Mobile, Username, Email, Name), we
                // restrict even on "Internal" calls
                // to prevent the Postman bypass trick.
                if (searchCriteria.getMobileNumber() != null ||
                        searchCriteria.getUserName() != null ||
                        searchCriteria.getEmailId() != null ||
                        searchCriteria.getName() != null) {
                    shouldRestrict = true;
                }

                // If searching by roleCodes, restrict only on External (non-inter-service)
                // traffic.
                // Internal services (like HRMS) must be allowed to look up users by roleCodes.
                else if (!isEmpty(searchCriteria.getRoleCodes()) && !isInterService) {
                    shouldRestrict = true;
                }

                // If searching by ID or UUID, we only restrict on External traffic.
                // Internal services (like Workflow) must be allowed to look up users by
                // ID/UUID.
                else if (!isInterService) {
                    shouldRestrict = true;
                }
            }
        }

        if (shouldRestrict) {
            org.egov.common.contract.request.User loggedInUser = request.getRequestInfo().getUserInfo();
            boolean isSearchingOtherUsers = false;

            // Check if searching by UUID that doesn't match logged-in user
            if (!isEmpty(searchCriteria.getUuid()) && (loggedInUser.getUuid() == null ||
                    !searchCriteria.getUuid().contains(loggedInUser.getUuid()) ||
                    searchCriteria.getUuid().size() > 1)) {
                isSearchingOtherUsers = true;
            }

            // Check if searching by ID that doesn't match logged-in user
            if (!isEmpty(searchCriteria.getId()) && (loggedInUser.getId() == null ||
                    !searchCriteria.getId().contains(loggedInUser.getId()) ||
                    searchCriteria.getId().size() > 1)) {
                isSearchingOtherUsers = true;
            }

            // Check if searching by userName that doesn't match logged-in user
            if (searchCriteria.getUserName() != null &&
                    (loggedInUser.getUserName() == null ||
                            !searchCriteria.getUserName().equalsIgnoreCase(loggedInUser.getUserName()))) {
                isSearchingOtherUsers = true;
            }

            // Check if searching by mobileNumber that doesn't match logged-in user
            if (searchCriteria.getMobileNumber() != null &&
                    (loggedInUser.getMobileNumber() == null ||
                            !searchCriteria.getMobileNumber().equals(loggedInUser.getMobileNumber()))) {
                isSearchingOtherUsers = true;
            }

            // Check if searching by emailId that doesn't match logged-in user
            if (searchCriteria.getEmailId() != null &&
                    (loggedInUser.getEmailId() == null ||
                            !searchCriteria.getEmailId().equalsIgnoreCase(loggedInUser.getEmailId()))) {
                isSearchingOtherUsers = true;
            }

            // Check if searching by name (not allowed for non-STUDIO_ADMIN)
            // roleCodes are allowed for inter-service calls (e.g., HRMS employee search by
            // role)
            if (searchCriteria.getName() != null ||
                    (!isInterService && !isEmpty(searchCriteria.getRoleCodes()))) {
                isSearchingOtherUsers = true;
            }

            // If trying to search other users, throw 403 Forbidden error
            if (isSearchingOtherUsers) {
                throw new CustomException("UNAUTHORIZED_ACCESS",
                        "You are not authorized to search for other users' data. Please contact your administrator.");
            }
        }

        if (!isInterService && (isEmpty(searchCriteria.getId()) && isEmpty(searchCriteria.getUuid()))
                && (searchCriteria.getLimit() > defaultSearchSize || searchCriteria.getLimit() == 0)) {
            searchCriteria.setLimit(defaultSearchSize);
        }

        List<User> userModels = userService.searchUsers(searchCriteria, isInterService, request.getRequestInfo());
        List<UserSearchResponseContent> userContracts = userModels.stream().map(UserSearchResponseContent::new)
                .collect(Collectors.toList());
        ResponseInfo responseInfo = ResponseInfo.builder().status(String.valueOf(HttpStatus.OK.value())).build();
        return new UserSearchResponse(responseInfo, userContracts);
    }

    private boolean isMobileValidationRequired(HttpHeaders headers) {
        boolean x_pass_through_gateway = !isInterServiceCall(headers);
        if (mobileValidationWorkaroundEnabled != null && Boolean.valueOf(mobileValidationWorkaroundEnabled)
                && !x_pass_through_gateway) {
            return false;
        }
        return true;
    }

}