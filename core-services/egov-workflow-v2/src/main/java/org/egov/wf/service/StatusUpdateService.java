package org.egov.wf.service;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.tracer.model.CustomException;
import org.egov.wf.config.WorkflowConfig;
import org.egov.wf.producer.Producer;
import org.egov.wf.util.BusinessUtil;
import org.egov.wf.util.WorkflowUtil;
import org.egov.wf.web.models.Action;
import org.egov.wf.web.models.BusinessService;
import org.egov.wf.web.models.ProcessInstance;
import org.egov.wf.web.models.ProcessInstanceRequest;
import org.egov.wf.web.models.ProcessStateAndAction;
import org.egov.wf.web.models.State;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class StatusUpdateService {

	private Producer producer;

	private WorkflowConfig config;

	private BusinessUtil businessUtil;

	private WorkflowService workflowService;
	
	private WorkflowUtil workflowUtil;

	@Autowired
	public StatusUpdateService(Producer producer, WorkflowConfig config, BusinessUtil businessUtil,
			@Lazy WorkflowService workflowService, WorkflowUtil workflowUtil) {
		this.producer = producer;
		this.config = config;
		this.businessUtil = businessUtil;
		this.workflowService = workflowService;
		this.workflowUtil = workflowUtil;
	}


	/**
	 * Updates the status and pushes the request on kafka to persist
	 *
	 * @param requestInfo
	 * @param processStateAndActions
	 */
	public void updateStatus(RequestInfo requestInfo, List<ProcessStateAndAction> processStateAndActions) {

		for (ProcessStateAndAction processStateAndAction : processStateAndActions) {
			if (processStateAndAction.getProcessInstanceFromRequest().getState() != null) {
				String prevStatus = processStateAndAction.getProcessInstanceFromRequest().getState().getUuid();
				processStateAndAction.getProcessInstanceFromRequest().setPreviousStatus(prevStatus);
			}
			processStateAndAction.getProcessInstanceFromRequest().setState(processStateAndAction.getResultantState());
			
			// Check if selective parallel workflows are specified
			List<String> workflowsToTrigger = getWorkflowsToTrigger(
				processStateAndAction.getProcessInstanceFromRequest(), 
				processStateAndAction.getResultantState()
			);
			
			if (!CollectionUtils.isEmpty(workflowsToTrigger)) {
				triggerParallelWorkflows(requestInfo, processStateAndAction, workflowsToTrigger);
			}
		}
		List<ProcessInstance> processInstances = new LinkedList<>();
		processStateAndActions.forEach(processStateAndAction -> {
			processInstances.add(processStateAndAction.getProcessInstanceFromRequest());
		});
		ProcessInstanceRequest processInstanceRequest = new ProcessInstanceRequest(requestInfo, processInstances);
		producer.push(processInstances.get(0).getTenantId(), config.getSaveTransitionTopic(), processInstanceRequest);
	}

	/**
	 * Determines which workflows to trigger based on selective workflows (if provided) or all configured workflows
	 * @param processInstance The process instance from the request
	 * @param resultantState The state resulting from the transition
	 * @return List of workflow business services to trigger
	 */
	private List<String> getWorkflowsToTrigger(ProcessInstance processInstance, State resultantState) {
		List<String> configuredWorkflows = resultantState.getTriggerParallelWorkflows();
		String selectiveWorkflows = processInstance.getTriggerSelectiveParallelWorkflows();
		
		// If no workflows are configured, return empty list
		if (CollectionUtils.isEmpty(configuredWorkflows)) {
			return Collections.emptyList();
		}
		
		// If selective workflows are provided, use intersection of configured and selective
		if (!StringUtils.isEmpty(selectiveWorkflows)) {
			List<String> workflowsToTrigger = new LinkedList<>();
			String[] selectiveWorkflowsArr = selectiveWorkflows.split(",");
			for (String selectiveWorkflow : selectiveWorkflowsArr) {
				if (configuredWorkflows.contains(selectiveWorkflow)) {
					workflowsToTrigger.add(selectiveWorkflow);
				}
			}
			return workflowsToTrigger;
		}
		
		// If no selective workflows specified, use all configured workflows
		return configuredWorkflows;
	}

	private void triggerParallelWorkflows(RequestInfo requestInfo, ProcessStateAndAction processStateAndAction, List<String> parallelWorkflows) {
		for (String parallelWorkflow : parallelWorkflows) {
			List<Action> actions = getParallelWorkflowAction(processStateAndAction.getProcessInstanceFromRequest().getTenantId(), parallelWorkflow);
			if (actions.isEmpty() || actions.get(0).getAction() == null) {
				throw new CustomException("INVALID ACTION", "Action not found in the " + parallelWorkflow
						+ " business service config for the businessId: " + processStateAndAction.getProcessInstanceFromRequest().getBusinessId());
			}
			triggerParallelWorkflow(requestInfo, processStateAndAction.getProcessInstanceFromRequest(), parallelWorkflow, actions.get(0).getAction());
		}
	}

	private void triggerParallelWorkflow(RequestInfo requestInfo, ProcessInstance processInstanceFromRequest, String parallelWorkflow, String action) {
		List<User> filteredAssignees = null;
		BusinessService parallelBusinessService = businessUtil
				.getBusinessService(processInstanceFromRequest.getTenantId(), parallelWorkflow);

		if (parallelBusinessService != null) {
			State state = parallelBusinessService.getStates().stream().filter(s -> "INITIATED".equals(s.getState()))
					.findFirst().orElse(null);

			List<String> stateRoles = workflowUtil.getAllRolesFromState(state);

			log.info("State and roles for parallel workflow : " + state + "=>" + stateRoles);

			if (!CollectionUtils.isEmpty(stateRoles)) {
				List<User> assignees = processInstanceFromRequest.getAssignes();
				if (!CollectionUtils.isEmpty(assignees)) {
					filteredAssignees = assignees.stream().filter(
							user -> user.getRoles().stream().anyMatch(role -> stateRoles.contains(role.getCode())))
							.toList();
				}
			}
		}

		log.info("Assignee for parallel workflow : " + filteredAssignees);
		
		ProcessInstance processInstance = ProcessInstance.builder().businessService(parallelWorkflow)
				.businessId(processInstanceFromRequest.getBusinessId())
				.action(action)
				.moduleName(processInstanceFromRequest.getModuleName())
				.tenantId(processInstanceFromRequest.getTenantId())
				.assignes(filteredAssignees)
				.applicantUuid(processInstanceFromRequest.getApplicantUuid())
				.build();
		List<ProcessInstance> processInstances = new LinkedList<>();
		processInstances.add(processInstance);
		ProcessInstanceRequest processInstanceRequest = new ProcessInstanceRequest(requestInfo, processInstances);
		workflowService.transition(processInstanceRequest, true);
	}

	private List<Action> getParallelWorkflowAction(String tenantId, String businessServiceCode) {
		BusinessService businessService = businessUtil.getBusinessService(tenantId, businessServiceCode);
		for (State state : businessService.getStates()) {
			if (state.getState() == null) {
				return state.getActions();
			}
		}
		return Collections.emptyList();
	}

}