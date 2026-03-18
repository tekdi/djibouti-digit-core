package org.egov.wf.service;

import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.Role;
import org.egov.common.contract.request.User;
import org.egov.tracer.model.CustomException;
import org.egov.wf.config.WorkflowConfig;
import org.egov.wf.repository.BusinessServiceRepository;
import org.egov.wf.repository.WorKflowRepository;
import org.egov.wf.util.WorkflowConstants;
import org.egov.wf.util.WorkflowUtil;
import org.egov.wf.validator.WorkflowValidator;
import org.egov.wf.web.models.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import static java.util.Objects.isNull;

import java.util.*;

@Slf4j
@Service
public class WorkflowService {

    private WorkflowConfig config;

    private TransitionService transitionService;

    private EnrichmentService enrichmentService;

    private WorkflowValidator workflowValidator;

    private StatusUpdateService statusUpdateService;

    private WorKflowRepository workflowRepository;

    private WorkflowUtil util;

    private BusinessServiceRepository businessServiceRepository;

    @Autowired
    private MDMSService mdmsService;

    @Autowired
    private BusinessMasterService businessMasterService;

    @Autowired
    private UserService userService;

    @Autowired
    public WorkflowService(WorkflowConfig config, TransitionService transitionService,
            EnrichmentService enrichmentService, WorkflowValidator workflowValidator,
            StatusUpdateService statusUpdateService, WorKflowRepository workflowRepository,
            WorkflowUtil util, BusinessServiceRepository businessServiceRepository) {
        this.config = config;
        this.transitionService = transitionService;
        this.enrichmentService = enrichmentService;
        this.workflowValidator = workflowValidator;
        this.statusUpdateService = statusUpdateService;
        this.workflowRepository = workflowRepository;
        this.util = util;
        this.businessServiceRepository = businessServiceRepository;
    }

    /**
     * Creates or updates the processInstanceFromRequest
     * 
     * @param request The incoming request for workflow transition
     * @return The list of processInstanceFromRequest objects after taking action
     */
    public List<ProcessInstance> transition(ProcessInstanceRequest request, boolean isInit) {
        RequestInfo requestInfo = request.getRequestInfo();

        List<ProcessStateAndAction> processStateAndActions = transitionService
                .getProcessStateAndActions(request.getProcessInstances(), true, isInit);
        enrichmentService.enrichProcessRequest(requestInfo, processStateAndActions);
        workflowValidator.validateRequest(requestInfo, processStateAndActions);
        statusUpdateService.updateStatus(requestInfo, processStateAndActions);
        return request.getProcessInstances();
    }

    /**
     * Fetches ProcessInstances from db based on processSearchCriteria
     * 
     * @param requestInfo The RequestInfo of the search request
     * @param criteria    The object containing Search params
     * @return List of processInstances based on search criteria
     */
    public List<ProcessInstance> search(RequestInfo requestInfo, ProcessInstanceSearchCriteria criteria,
            boolean isInterServiceCall) {
        List<ProcessInstance> processInstances;

        if (ObjectUtils.isEmpty(criteria.getTenantId()))
            throw new CustomException("EG_WF_CRITERIA_ERR", "TenantId is mandatory for searching workflow");

        log.info("Process search invoked as inter-service call : {}", isInterServiceCall);

        if (!isInterServiceCall && requestInfo != null && requestInfo.getUserInfo() == null
                && !ObjectUtils.isEmpty(requestInfo.getAuthToken())) {
            User user = userService.getUserByAuthToken(requestInfo);
            if (user != null) {
                log.info("Successfully enriched userInfo from authToken for uuid: {}", user.getUuid());
                requestInfo.setUserInfo(user);
            }
        }

        if (!isInterServiceCall && (requestInfo == null || requestInfo.getUserInfo() == null))
            throw new CustomException("UNAUTHORIZED_ACCESS",
                    "User information is required to search processes. Access Denied (403)");

        if (criteria.isNull())
            processInstances = getUserBasedProcessInstances(requestInfo, criteria);
        else {
            processInstances = workflowRepository.getProcessInstances(criteria);
            // Validate user access to retrieved process instances
            if (!isInterServiceCall) {
                validateUserAccessToProcesses(requestInfo, processInstances);
            } else {
                log.info(
                        "Skipping user access validation for inter-service call, criteria: tenantId={}, businessService={}, businessIds={}",
                        criteria.getTenantId(), criteria.getBusinessService(), criteria.getBusinessIds());
            }
        }
        if (CollectionUtils.isEmpty(processInstances))
            return processInstances;

        enrichmentService.enrichUsersFromSearch(requestInfo, processInstances);
        List<ProcessStateAndAction> processStateAndActions = enrichmentService.enrichNextActionForSearch(requestInfo,
                processInstances);
        // workflowValidator.validateSearch(requestInfo,processStateAndActions);
        enrichmentService.enrichAndUpdateSlaForSearch(processInstances);
        return processInstances;
    }

    public Integer count(RequestInfo requestInfo, ProcessInstanceSearchCriteria criteria) {
        Integer count;

        if (ObjectUtils.isEmpty(criteria.getTenantId()))
            throw new CustomException("EG_WF_CRITERIA_ERR", "TenantId is mandatory for count workflow call");

        // Enrich slot sla limit in case of nearingSla count
        if (criteria.getIsNearingSlaCount()) {

            if (ObjectUtils.isEmpty(criteria.getBusinessService()))
                throw new CustomException("EG_WF_BUSINESSSRV_ERR",
                        "Providing business service is mandatory for nearing escalation count");

            Integer slotPercentage = mdmsService.fetchSlotPercentageForNearingSla(requestInfo, criteria.getTenantId());
            Long maxBusinessServiceSla = businessMasterService.getMaxBusinessServiceSla(criteria);
            criteria.setSlotPercentageSlaLimit(maxBusinessServiceSla - slotPercentage * (maxBusinessServiceSla / 100));
        }

        if (criteria.isNull()) {
            enrichSearchCriteriaFromUser(requestInfo, criteria);
            count = workflowRepository.getInboxCount(criteria);
        } else
            count = workflowRepository.getProcessInstancesCount(criteria);

        return count;
    }

    /**
     * Searches the processInstances based on user and its roles
     * 
     * @param requestInfo The RequestInfo of the search request
     * @param criteria    The object containing Search params
     * @return List of processInstances based on search criteria
     */
    private List<ProcessInstance> getUserBasedProcessInstances(RequestInfo requestInfo,
            ProcessInstanceSearchCriteria criteria) {

        enrichSearchCriteriaFromUser(requestInfo, criteria);
        List<ProcessInstance> processInstances = workflowRepository.getProcessInstancesForUserInbox(criteria);

        processInstances = filterDuplicates(processInstances);

        return processInstances;

    }

    public Integer getUserBasedProcessInstancesCount(RequestInfo requestInfo, ProcessInstanceSearchCriteria criteria) {
        Integer count;
        count = workflowRepository.getProcessInstancesForUserInboxCount(criteria);
        return count;
    }

    /**
     * Removes duplicate businessId which got created due to simultaneous request
     * 
     * @param processInstances
     * @return
     */
    private List<ProcessInstance> filterDuplicates(List<ProcessInstance> processInstances) {

        if (CollectionUtils.isEmpty(processInstances))
            return processInstances;

        Map<String, ProcessInstance> businessIdToProcessInstanceMap = new LinkedHashMap<>();

        for (ProcessInstance processInstance : processInstances) {
            businessIdToProcessInstanceMap.put(processInstance.getBusinessId(), processInstance);
        }

        return new LinkedList<>(businessIdToProcessInstanceMap.values());
    }

    public List statusCount(RequestInfo requestInfo, ProcessInstanceSearchCriteria criteria) {
        List result;
        if (criteria.isNull() && !isNull(criteria.getBusinessService())
                && !criteria.getBusinessService().equalsIgnoreCase(WorkflowConstants.FSM_MODULE)) {
            enrichSearchCriteriaFromUser(requestInfo, criteria);
            result = workflowRepository.getInboxStatusCount(criteria);
        } else {
            // List<String> origCriteriaStatuses = criteria.getStatus();
            // enrichSearchCriteriaFromUser(requestInfo, criteria);
            // String tenantId = (criteria.getTenantId() == null ?
            // (requestInfo.getUserInfo().getTenantId()) :(criteria.getTenantId()));
            // List<String> finalCriteriaStatuses = new ArrayList<String>();
            // if(origCriteriaStatuses != null && !origCriteriaStatuses.isEmpty()) {
            // origCriteriaStatuses.forEach((status) ->{
            // finalCriteriaStatuses.add(tenantId+":"+status);
            // });
            // criteria.setStatus(finalCriteriaStatuses);
            // }
            result = workflowRepository.getProcessInstancesStatusCount(criteria);
        }

        return result;
    }

    /**
     * Enriches processInstance search criteria based on requestInfo
     * 
     * @param requestInfo
     * @param criteria
     */
    private void enrichSearchCriteriaFromUser(RequestInfo requestInfo, ProcessInstanceSearchCriteria criteria) {

        /*
         * BusinessServiceSearchCriteria businessServiceSearchCriteria = new
         * BusinessServiceSearchCriteria();
         * 
         *//*
            * If tenantId is sent in query param processInstances only for that tenantId is
            * returned
            * else all tenantIds for which the user has roles are returned
            *//*
               * if(criteria.getTenantId()!=null)
               * businessServiceSearchCriteria.setTenantIds(Collections.singletonList(criteria
               * .getTenantId()));
               * else
               * businessServiceSearchCriteria.setTenantIds(util.getTenantIds(requestInfo.
               * getUserInfo()));
               * 
               * Map<String, Boolean> stateLevelMapping = stat
               * 
               * List<BusinessService> businessServices =
               * businessServiceRepository.getAllBusinessService();
               * List<String> actionableStatuses =
               * util.getActionableStatusesForRole(requestInfo,businessServices,criteria);
               * criteria.setAssignee(requestInfo.getUserInfo().getUuid());
               * criteria.setStatus(actionableStatuses);
               */
        Map<String, Map<String, List<String>>> roleTenantAndStatusMapping = businessServiceRepository
                .getRoleTenantAndStatusMapping(criteria.getTenantId());
        util.enrichStatusesInSearchCriteria(requestInfo, criteria, roleTenantAndStatusMapping);
        criteria.setAssignee(requestInfo.getUserInfo().getUuid());

    }

    public List<ProcessInstance> escalatedApplicationsSearch(RequestInfo requestInfo,
            ProcessInstanceSearchCriteria criteria) {
        List<String> escalatedApplicationsBusinessIds;
        List<ProcessInstance> escalatedApplications = new ArrayList<>();
        criteria.setIsEscalatedCount(false);

        // Set<String> statesToIgnore =
        // enrichmentService.fetchStatesToIgnoreFromMdms(requestInfo,
        // criteria.getTenantId());
        escalatedApplicationsBusinessIds = workflowRepository.fetchEscalatedApplicationsBusinessIdsFromDb(requestInfo,
                criteria);
        if (CollectionUtils.isEmpty(escalatedApplicationsBusinessIds)) {
            return escalatedApplications;
        }
        // SEARCH BASED ON FILTERED BUSINESS IDs DONE HERE
        ProcessInstanceSearchCriteria searchCriteria = new ProcessInstanceSearchCriteria();
        searchCriteria.setBusinessIds(escalatedApplicationsBusinessIds);
        searchCriteria.setTenantId(criteria.getTenantId());
        searchCriteria.setBusinessService(criteria.getBusinessService());
        // searchCriteria.setHistory(true);
        escalatedApplications = search(requestInfo, searchCriteria, true);

        // Only last but one applications in history needs to show up where the employee
        // failed to take action

        // HashMap<String, List<ProcessInstance>> businessIdsVsProcessInstancesMap = new
        // HashMap<>();
        // HashMap<String, Integer> occurenceMap = new HashMap<>();
        // for(ProcessInstance processInstance : escalatedApplicationsWithHistory){
        // if(businessIdsVsProcessInstancesMap.containsKey(processInstance.getBusinessId())){
        // occurenceMap.put(processInstance.getBusinessId(),
        // occurenceMap.get(processInstance.getBusinessId()) + 1);
        // businessIdsVsProcessInstancesMap.get(processInstance.getBusinessId()).add(processInstance);
        // }else{
        // occurenceMap.put(processInstance.getBusinessId(), 1);
        // List<ProcessInstance> processInstanceList = new ArrayList<>();
        // processInstanceList.add(processInstance);
        // businessIdsVsProcessInstancesMap.put(processInstance.getBusinessId(),
        // processInstanceList);
        // }
        // }
        // criteria.setAssignee(requestInfo.getUserInfo().getUuid());
        // for(String businessId : occurenceMap.keySet()){
        // if(occurenceMap.get(businessId) >= 2){
        // Set<String> uuidsOfAssignees = new HashSet<>();
        // if(!CollectionUtils.isEmpty(businessIdsVsProcessInstancesMap.get(businessId).get(1).getAssignes()))
        // {
        // businessIdsVsProcessInstancesMap.get(businessId).get(1).getAssignes().forEach(user
        // -> {
        // uuidsOfAssignees.add(user.getUuid());
        // });
        // }
        // if(autoEscalationEmployeesUuids.contains(businessIdsVsProcessInstancesMap.get(businessId).get(0).getAuditDetails().getCreatedBy())
        // && uuidsOfAssignees.contains(criteria.getAssignee())){
        // if(!statesToIgnore.contains(businessIdsVsProcessInstancesMap.get(businessId).get(1).getState().getState()))
        // escalatedApplications.add(businessIdsVsProcessInstancesMap.get(businessId).get(0));
        // }
        // }
        // }
        return escalatedApplications;
    }

    public Integer countEscalatedApplications(RequestInfo requestInfo, ProcessInstanceSearchCriteria criteria) {
        Integer count;
        criteria.setIsEscalatedCount(true);
        count = workflowRepository.getEscalatedApplicationsCount(requestInfo, criteria);
        return count;
    }

    /**
     * Validates if current user has access to the process instance based on their
     * role and association
     * 
     * @param requestInfo      The RequestInfo containing user details
     * @param processInstances List of process instances to validate access for
     * @throws CustomException if user is not authorized to access the processes
     */
    public void validateUserAccessToProcesses(RequestInfo requestInfo, List<ProcessInstance> processInstances) {
        if (CollectionUtils.isEmpty(processInstances)) {
            return;
        }

        User currentUser = requestInfo.getUserInfo();
        if (currentUser == null) {
            throw new CustomException("UNAUTHORIZED_ACCESS",
                    "User information is required to search processes. Access Denied (403)");
        }

        String currentUserUuid = currentUser.getUuid();
        List<Role> userRoles = currentUser.getRoles();

        boolean isArchitect = hasRole(userRoles, "BPA_ARCHITECT");
        boolean isCitizen = hasRole(userRoles, "CITIZEN");
        boolean isStudioAdmin = hasRole(userRoles, "STUDIO_ADMIN");
        boolean isEmployee = !isArchitect && !isCitizen && !isStudioAdmin;

        if (isStudioAdmin) {
            return;
        }

        boolean hasAccess = false;
        for (ProcessInstance processInstance : processInstances) {
            if (isArchitect || isEmployee) {
                hasAccess = isUserInAssignees(processInstance, currentUserUuid)
                        || isUserAssigner(processInstance, currentUserUuid);
            } else if (isCitizen) {
                hasAccess = isUserAssigner(processInstance, currentUserUuid)
                        || isUserApplicant(processInstance, currentUserUuid);
            }
            if (hasAccess) {
                break;
            }
        }

        if (!hasAccess) {
            throw new CustomException("UNAUTHORIZED_ACCESS", "Access Denied");
        }
    }

    /**
     * Checks if user has a specific role
     * 
     * @param roles    List of user roles
     * @param roleCode The role code to check
     * @return true if user has the role, false otherwise
     */
    private boolean hasRole(List<Role> roles, String roleCode) {
        if (CollectionUtils.isEmpty(roles)) {
            return false;
        }
        return roles.stream()
                .anyMatch(role -> roleCode.equalsIgnoreCase(role.getCode()));
    }

    /**
     * Checks if current user UUID is in the process instance assignees
     * 
     * @param processInstance The process instance to check
     * @param userUuid        The user UUID to look for
     * @return true if user is in assignees, false otherwise
     */
    private boolean isUserInAssignees(ProcessInstance processInstance, String userUuid) {
        if (processInstance == null || CollectionUtils.isEmpty(processInstance.getAssignes())) {
            return false;
        }
        return processInstance.getAssignes().stream()
                .anyMatch(user -> userUuid.equalsIgnoreCase(user.getUuid()));
    }

    /**
     * Checks if current user UUID is the process instance assigner
     * (creator/submitter)
     * 
     * @param processInstance The process instance to check
     * @param userUuid        The user UUID to look for
     * @return true if user is the assigner, false otherwise
     */
    private boolean isUserAssigner(ProcessInstance processInstance, String userUuid) {
        if (processInstance == null) {
            return false;
        }

        // Check assigner (single User field)
        if (processInstance.getAssigner() != null &&
                userUuid.equalsIgnoreCase(processInstance.getAssigner().getUuid())) {
            return true;
        }

        // For CITIZEN/ARCHITECT: Check audit createdBy field
        if (processInstance.getAuditDetails() != null &&
                userUuid.equalsIgnoreCase(processInstance.getAuditDetails().getCreatedBy())) {
            return true;
        }

        return false;
    }

    private boolean isUserApplicant(ProcessInstance processInstance, String userUuid) {
        if (processInstance == null || processInstance.getApplicantUuid() == null || userUuid == null) {
            return false;
        }
        return userUuid.equalsIgnoreCase(processInstance.getApplicantUuid());
    }
}