/*
 * Copyright (C) 2005-2007 Alfresco Software Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.

 * As a special exception to the terms and conditions of version 2.0 of 
 * the GPL, you may redistribute this Program in connection with Free/Libre 
 * and Open Source Software ("FLOSS") applications as described in Alfresco's 
 * FLOSS exception.  You should have recieved a copy of the text describing 
 * the FLOSS exception, and it is also available here: 
 * http://www.alfresco.com/legal/licensing"
 */
package org.alfresco.repo.invitation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.alfresco.service.cmr.invitation.Invitation;
import org.alfresco.service.cmr.invitation.InvitationExceptionForbidden;
import org.alfresco.service.cmr.invitation.InvitationExceptionNotFound;
import org.alfresco.service.cmr.invitation.InvitationExceptionUserError;
import org.alfresco.service.cmr.invitation.InvitationSearchCriteria;
import org.alfresco.service.cmr.invitation.ModeratedInvitation;
import org.alfresco.service.cmr.invitation.NominatedInvitation;
import org.alfresco.service.cmr.invitation.InvitationService;
import org.alfresco.service.cmr.invitation.InvitationException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.datatype.DefaultTypeConverter;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.cmr.security.PersonService;
import org.alfresco.service.cmr.site.SiteService;
import org.alfresco.service.cmr.workflow.WorkflowDefinition;
import org.alfresco.service.cmr.workflow.WorkflowInstance;
import org.alfresco.service.cmr.workflow.WorkflowPath;
import org.alfresco.service.cmr.workflow.WorkflowService;
import org.alfresco.service.cmr.workflow.WorkflowTask;
import org.alfresco.service.cmr.workflow.WorkflowTaskQuery;
import org.alfresco.service.cmr.workflow.WorkflowTaskState;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.GUID;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.invitation.site.*;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.MutableAuthenticationDao;
import org.alfresco.repo.security.authentication.PasswordGenerator;
import org.alfresco.repo.security.authentication.UserNameGenerator;
import org.alfresco.repo.security.authentication.AuthenticationUtil.RunAsWork;
import org.alfresco.repo.site.SiteModel;
import org.alfresco.repo.workflow.WorkflowModel;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Implementation of invitation service.
 * 
 * @see org.alfresco.service.cmr.invitation.Invitation
 * 
 * @author mrogers
 * 
 */
public class InvitationServiceImpl implements InvitationService 
{
	private static final Log logger = LogFactory
			.getLog(InvitationServiceImpl.class);

	// maximum number of tries to generate a invitee user name which
	// does not already belong to an existing person
	public static final int MAX_NUM_INVITEE_USER_NAME_GEN_TRIES = 10;

	/**
	 * Start the invitation process for a NominatedInvitation
	 * 
	 * @param inviteeFirstName
	 * @param inviteeLastName
	 * @param inviteeEmail
	 * @param inviteeUserName
	 *            optional Alfresco user name of the invitee, null if not on
	 *            system.
	 * @param Invitation
	 *            .ResourceType resourceType
	 * @param resourceName
	 * @param inviteeRole
	 * @param serverPath
	 * @param acceptUrl
	 * @param rejectUrl
	 * 
	 * @return the nominated invitation which will contain the invitationId and
	 *         ticket which will uniqely identify this invitation for the rest
	 *         of the workflow.
	 * 
	 * @throws InvitationException
	 * @throws InvitationExceptionUserError
	 * @throws InvitationExceptionForbidden
	 */
	public NominatedInvitation inviteNominated(String inviteeFirstName,
			String inviteeLastName, String inviteeEmail,
			String inviteeUserName, Invitation.ResourceType resourceType,
			String resourceName, String inviteeRole, String serverPath,
			String acceptUrl, String rejectUrl) 
	{
		// Validate the request

		// Check resource exists

		if (resourceType == Invitation.ResourceType.WEB_SITE) 
		{
			return startInvite(inviteeFirstName, inviteeLastName, inviteeEmail,
					inviteeUserName, resourceType, resourceName, inviteeRole,
					serverPath, acceptUrl, rejectUrl);
		}

		throw new InvitationException("unknown resource type");
	}

	/**
	 * Start the invitation process for a ModeratedInvitation
	 * 
	 * @param comments
	 *            why does the invitee want access to the resource ?
	 * @param inviteeUserName
	 *            who is to be invited
	 * @param Invitation
	 *            .ResourceType resourceType what resource type ?
	 * @param resourceName
	 *            which resource
	 * @param inviteeRole
	 *            which role ?
	 */
	public ModeratedInvitation inviteModerated(String inviteeComments,
			String inviteeUserName, Invitation.ResourceType resourceType,
			String resourceName, String inviteeRole) 
	{
		if (resourceType == Invitation.ResourceType.WEB_SITE) 
		{
			return startInvite(inviteeComments, inviteeUserName, resourceType,
					resourceName, inviteeRole);
		}
		throw new InvitationException("unknown resource type");
	}

	/**
	 * Invitee accepts this invitation
	 * 
	 * Nominated Invitaton process only
	 * 
	 * @param invitationId the invitation id
	 * @param ticket the ticket produced when creating the invitation.
	 */
	public Invitation accept(String invitationId, String ticket) {
		Invitation invitation = getInvitation(invitationId);

		if (invitation instanceof NominatedInvitation) {

			// Check invitationId and ticket match
			if (ticket == null
					|| (!ticket.equals(((NominatedInvitation) invitation)
							.getTicket()))) {
				throw new InvitationException(
						"Response to invite has supplied an invalid ticket. The response to the "
								+ "invitation could thus not be processed");
			}

			/**
			 * Nominated invitation complete the wf:invitePendingTask along the
			 * 'accept' transition because the invitation has been accepted
			 */
			InviteHelper
					.completeInviteTask(
							invitationId,
							WorkflowModelNominatedInvitation.WF_INVITE_TASK_INVITE_PENDING,
							WorkflowModelNominatedInvitation.WF_TRANSITION_ACCEPT,
							this.workflowService);

			return invitation;
		}
		throw new InvitationException(
				"State error, cannot call accept a moderated invitation");

	}

	/**
	 * Moderator approves this invitation
	 * 
	 * @param request the request to approve
	 * @param reason comments about the acceptance
	 */
	public Invitation approve(String invitationId, String reason) {
		Invitation invitation = getInvitation(invitationId);
		if(invitation instanceof ModeratedInvitation)
		{
			// Check approver is a site manager
			String approverUserName = this.authenticationService.getCurrentUserName();
			checkManagerRole(approverUserName, invitation.getResourceType(), invitation.getResourceName());
			
			WorkflowTaskQuery wfModeratedTaskQuery = new WorkflowTaskQuery();

			// Current Review Moderated Tasks
			wfModeratedTaskQuery.setActive(Boolean.TRUE);
			wfModeratedTaskQuery.setTaskState(WorkflowTaskState.IN_PROGRESS);		
			wfModeratedTaskQuery.setTaskName(WorkflowModelModeratedInvitation.WF_REVIEW_TASK);
			wfModeratedTaskQuery.setProcessName(WorkflowModelModeratedInvitation.WF_PROCESS_INVITATION_MODERATED);

			// query for invite review tasks
			List<WorkflowTask> wf_moderated_tasks = this.workflowService
				.queryTasks(wfModeratedTaskQuery);

			for (WorkflowTask workflowTask : wf_moderated_tasks) 
			{
				Map<QName, Serializable> wfReviewProps = new HashMap<QName, Serializable>();	
				wfReviewProps.put(ContentModel.PROP_OWNER, approverUserName);
				wfReviewProps.put(WorkflowModelModeratedInvitation.WF_PROP_REVIEW_COMMENTS, reason);
				workflowService.updateTask(workflowTask.id, wfReviewProps, null, null);
				workflowService.endTask(workflowTask.id, WorkflowModelModeratedInvitation.WF_TRANSITION_APPROVE);
			}
		}
		else
		{
		    throw new InvitationException("State error, cannot call approve");
		}
		return invitation;

	}

	/**
	 * User or moderator rejects this request
	 * 
	 * @param invitationId
	 * @param reason
	 *            , optional reason for rejection
	 */
	public Invitation reject(String invitationId, String reason) {
		Invitation invitation = getInvitation(invitationId);

		if (invitation instanceof NominatedInvitation) {
			
			/**
			 * Nominated invitation complete the wf:invitePendingTask along the
			 * 'reject' transition because the invitation has been rejected
			 */
			InviteHelper
					.completeInviteTask(
							invitationId,
							WorkflowModelNominatedInvitation.WF_INVITE_TASK_INVITE_PENDING,
							WorkflowModelNominatedInvitation.WF_TRANSITION_REJECT,
							this.workflowService);

			return invitation;
		}

		if (invitation instanceof ModeratedInvitation) {
			WorkflowTaskQuery wfModeratedTaskQuery = new WorkflowTaskQuery();
			HashMap<QName, Object> wfQueryModifiedProps = new HashMap<QName, Object>(3, 1.0f);

			// Check rejecter is a site manager and throw and exception if not
			String rejecterUserName = this.authenticationService.getCurrentUserName();
			checkManagerRole(rejecterUserName, invitation.getResourceType(), invitation.getResourceName());

			// Current Review Moderated Tasks
			wfModeratedTaskQuery.setActive(Boolean.TRUE);
			wfModeratedTaskQuery.setTaskState(WorkflowTaskState.IN_PROGRESS);		
			wfModeratedTaskQuery.setTaskName(WorkflowModelModeratedInvitation.WF_REVIEW_TASK);
			wfModeratedTaskQuery.setProcessName(WorkflowModelModeratedInvitation.WF_PROCESS_INVITATION_MODERATED);

			// query for invite review tasks
			List<WorkflowTask> wf_moderated_tasks = this.workflowService
				.queryTasks(wfModeratedTaskQuery);

			for (WorkflowTask workflowTask : wf_moderated_tasks) 
			{
				Map<QName, Serializable> wfReviewProps = new HashMap<QName, Serializable>();	
				wfReviewProps.put(ContentModel.PROP_OWNER, rejecterUserName);
				wfReviewProps.put(WorkflowModelModeratedInvitation.WF_PROP_REVIEW_COMMENTS, reason);
				workflowService.updateTask(workflowTask.id, wfReviewProps, null, null);
				this.workflowService.endTask(workflowTask.id, WorkflowModelModeratedInvitation.WF_TRANSITION_REJECT);
			}

			return invitation;
		}

		return invitation;
	}

	/*
	 * cancel a pending request
	 */
	public Invitation cancel(String invitationId) {
		Invitation invitation = getInvitation(invitationId);

		if (invitation instanceof NominatedInvitation) {

			// TODO Who is allowed to cancel ??

			// Should you be allowed to cancel multiple times ?

			// complete the wf:invitePendingTask along the 'cancel' transition
			// because the invitation has been cancelled
			InviteHelper
					.completeInviteTask(
							invitationId,
							WorkflowModelNominatedInvitation.WF_INVITE_TASK_INVITE_PENDING,
							WorkflowModelNominatedInvitation.WF_TRANSITION_CANCEL,
							this.workflowService);
		}

		if (invitation instanceof ModeratedInvitation) 
		{
			// TODO Who is allowed to cancel ?
			workflowService.cancelWorkflow(invitationId);
		}

		return invitation;
	}

	/**
	 * Get an invitation from its invitation id
	 * 
	 * @throws InvitationExceptionNotFound
	 *             the invitation does not exist.
	 * @return the invitation.
	 */
	public Invitation getInvitation(String invitationId) {
		WorkflowInstance wi = workflowService.getWorkflowById(invitationId);
		if (wi == null) {
			Object objs[] = { invitationId };
			throw new InvitationExceptionNotFound("invitation.error.not_found",
					objs);
		}
		String workflowName = wi.definition.getName();

		if (workflowName
				.equals(WorkflowModelNominatedInvitation.WORKFLOW_DEFINITION_NAME)) {
			// This is a nominated invitation
			WorkflowTaskQuery wfTaskQuery = new WorkflowTaskQuery();
			wfTaskQuery.setProcessId(invitationId);

			// filter to find only the start task which contains the properties.
			wfTaskQuery.setTaskState(WorkflowTaskState.COMPLETED);
			wfTaskQuery
					.setTaskName(WorkflowModelNominatedInvitation.WF_INVITE_TASK_INVITE_TO_SITE);

			// query for invite workflow task associate
			List<WorkflowTask> inviteStartTasks = workflowService
					.queryTasks(wfTaskQuery);

			// should also be 0 or 1
			if (inviteStartTasks.size() < 1) {
				return null;
			} else {
				WorkflowTask task = inviteStartTasks.get(0);
				NominatedInvitationImpl result = new NominatedInvitationImpl(
						task.properties);
				result.setInviteId(invitationId);
				return result;
			}
		}
		if (workflowName
				.equals(WorkflowModelModeratedInvitation.WORKFLOW_DEFINITION_NAME)) {
			// This is a moderated invitation
			WorkflowTaskQuery wfTaskQuery = new WorkflowTaskQuery();
			wfTaskQuery.setProcessId(invitationId);

			// filter to find only the start task which contains the properties.
			wfTaskQuery.setTaskState(WorkflowTaskState.COMPLETED);
			wfTaskQuery.setTaskName(WorkflowModelModeratedInvitation.WF_START_TASK);

			List<WorkflowTask> inviteStartTasks = workflowService.queryTasks(wfTaskQuery);

			// should also be 0 or 1
			if (inviteStartTasks.size() < 1) {
				// No start task - workflow may have been completed
				Object objs[] = { invitationId };
				throw new InvitationExceptionNotFound("invitation.error.not_found",
						objs);
			} else {
				WorkflowTask task = inviteStartTasks.get(0);
				ModeratedInvitationImpl result = new ModeratedInvitationImpl(
						task.properties);
				result.setInviteId(invitationId);
				return result;
			}
		}

		// Unknown workflow type here
		return null;
	}

	/**
	 * list Invitations for a specific person/invitee
	 * 
	 * @param invitee
	 *            alfresco user id of person being invited
	 */
	public List<Invitation> listPendingInvitationsForInvitee(String invitee) {
		InvitationSearchCriteriaImpl crit = new InvitationSearchCriteriaImpl();
		crit.setInvitationType(InvitationSearchCriteria.InvitationType.ALL);
		crit.setInvitee(invitee);
		return searchInvitation(crit);
	}

	/**
	 * list Invitations for a specific resource
	 * 
	 * @param resourceType
	 * @param resourceName
	 */
	public List<Invitation> listPendingInvitationsForResource(
			Invitation.ResourceType resourceType, String resourceName) {
		InvitationSearchCriteriaImpl crit = new InvitationSearchCriteriaImpl();
		crit.setInvitationType(InvitationSearchCriteria.InvitationType.ALL);
		crit.setResourceType(resourceType);
		crit.setResourceName(resourceName);
		return searchInvitation(crit);
	}

	/**
	 * This is the general search invitation method
	 * 
	 * @param criteria
	 * @return the list of invitations
	 */
	public List<Invitation> searchInvitation(InvitationSearchCriteria criteria) {
		
		List<Invitation> ret = new ArrayList<Invitation>();

		// at least one of 'inviterUserName',
		// 'inviteeUserName', 'siteShortName',
		// URL request parameters has not been provided
		if (!(criteria.getInvitee() != null
				|| criteria.getResourceName() != null 
				|| criteria.getInviter() != null)) 
		{
			Object[] objs = {};
			throw new InvitationExceptionUserError(
					"search invitation: At least one of the following URL request parameters must be provided in URL "
					+ "'invite', 'inviter', 'resourceName'", objs);
		}

		InvitationSearchCriteria.InvitationType toSearch = criteria.getInvitationType();
		
		/**
		 * Nominated search below
		 */
		if(toSearch == InvitationSearchCriteria.InvitationType.ALL || toSearch == InvitationSearchCriteria.InvitationType.NOMINATED)
		{
			// query for nominated workflow tasks by given parameters
			// create workflow task query
			WorkflowTaskQuery wfTaskQuery = new WorkflowTaskQuery();

			// the invite URL request
			// parameters
			// - because this web script class will terminate with a web script
			// exception if none of the required
			// request parameters are provided, at least one of these query
			// properties will be set
			// at this point

			// workflow query properties
			HashMap<QName, Object> wfNominatedQueryProps = new HashMap<QName, Object>(10,
					1.0f);

			if (criteria.getInviter() != null) {
				wfNominatedQueryProps
						.put(
								WorkflowModelNominatedInvitation.WF_PROP_INVITER_USER_NAME,
								criteria.getInviter());
			}
			if (criteria.getInvitee() != null) {
				wfNominatedQueryProps
						.put(
								WorkflowModelNominatedInvitation.WF_PROP_INVITEE_USER_NAME,
								criteria.getInvitee());
			}
			if (criteria.getResourceName() != null) {
				wfNominatedQueryProps.put(
						WorkflowModelNominatedInvitation.WF_PROP_RESOURCE_NAME,
						criteria.getResourceName());

				wfNominatedQueryProps.put(
						WorkflowModelNominatedInvitation.WF_PROP_RESOURCE_TYPE,
						criteria.getResourceType().toString());
			}

			// set workflow task query parameters
			wfTaskQuery.setProcessCustomProps(wfNominatedQueryProps);

			// query only active workflows
			wfTaskQuery.setActive(Boolean.TRUE);

			// pick up the start task
			wfTaskQuery.setTaskState(WorkflowTaskState.IN_PROGRESS);
			wfTaskQuery.setTaskName(WorkflowModelNominatedInvitation.WF_INVITE_TASK_INVITE_PENDING);
			wfTaskQuery.setProcessName(WorkflowModelNominatedInvitation.WF_PROCESS_INVITE);

			// query for invite workflow tasks
			List<WorkflowTask> wf_invite_tasks = this.workflowService
					.queryTasks(wfTaskQuery);

			for (WorkflowTask workflowTask : wf_invite_tasks) {
				// get workflow instance (ID) that pendingInvite task (in query
				// result set)

				String workflowId = workflowTask.path.instance.id;
				//TODO ALFCOM-2597 workflowTask.properties does not contain custom process values 
				// NominatedInvitationImpl result = new NominatedInvitationImpl(workflowTask.properties);
				// result.setInviteId(workflowId);
				// ret.add(result);

				Invitation result = getInvitation(workflowId);
				
				// TODO ALFCOM-2598 records are being returned that do not match properties
				Set<QName>keys = wfNominatedQueryProps.keySet();
				boolean crap = false;
				for(QName key : keys)
				{
					if(key.equals(WorkflowModelNominatedInvitation.WF_PROP_RESOURCE_NAME))
					{
						Object val1 = wfNominatedQueryProps.get(key);
						Object val2 = result.getResourceName();
						if (!val1.equals(val2))
						{
							// Uh oh ... crap detected
							crap = true;
							System.out.println("ALFCOM-2598 key:" + key + "query:" + val1 +  "task:" + val2);
							break;
						}
					}
					if(key.equals(WorkflowModelNominatedInvitation.WF_PROP_RESOURCE_TYPE))
					{
						Object val1 = wfNominatedQueryProps.get(key);
						Object val2 = result.getResourceType().toString();
						if (!val1.equals(val2))
						{
							
							// Uh oh ... crap detected
							crap = true;
							System.out.println("ALFCOM-2598 key:" + key + "query:" + val1 +  "task:" + val2);
							break;
						}
					}
				}
				
				if(!crap)
				{
					ret.add(result);
				}
			}
		}

		/**
		 * Moderated search below
		 */
		if(toSearch == InvitationSearchCriteria.InvitationType.ALL || toSearch == InvitationSearchCriteria.InvitationType.MODERATED)
	    {
			// This is a moderated search
	    	WorkflowTaskQuery wfModeratedTaskQuery = new WorkflowTaskQuery();
			// workflow query properties
			HashMap<QName, Object> wfQueryModeratedProps = new HashMap<QName, Object>(3, 1.0f);

			if (criteria.getInvitee() != null) 
			{
				wfQueryModeratedProps.put(
				WorkflowModelModeratedInvitation.WF_PROP_INVITEE_USER_NAME,
						criteria.getInvitee());
			}
			if (criteria.getResourceName() != null) 
			{
				wfQueryModeratedProps.put(
						WorkflowModelModeratedInvitation.WF_PROP_RESOURCE_NAME,
						criteria.getResourceName());
				wfQueryModeratedProps.put(
						WorkflowModelModeratedInvitation.WF_PROP_RESOURCE_TYPE,
						criteria.getResourceType().toString());
			}

			// set workflow task query parameters
			wfModeratedTaskQuery.setProcessCustomProps(wfQueryModeratedProps);

			// Current Review Moderated Tasks
			wfModeratedTaskQuery.setActive(Boolean.TRUE);
			wfModeratedTaskQuery.setTaskState(WorkflowTaskState.IN_PROGRESS);		
			wfModeratedTaskQuery.setTaskName(WorkflowModelModeratedInvitation.WF_REVIEW_TASK);
			wfModeratedTaskQuery.setProcessName(WorkflowModelModeratedInvitation.WF_PROCESS_INVITATION_MODERATED);

			// query for invite workflow tasks
			List<WorkflowTask> wf_moderated_tasks = this.workflowService
				.queryTasks(wfModeratedTaskQuery);

			for (WorkflowTask workflowTask : wf_moderated_tasks) 
			{
				// Add moderated invitations
				String workflowId = workflowTask.path.instance.id;
				ModeratedInvitationImpl result = new  ModeratedInvitationImpl(workflowTask.properties);
				
				// TODO ALFCOM-2598 records are being returned that do not match properties
				Set<QName>keys = wfQueryModeratedProps.keySet();
				boolean crap = false;
				for(QName key : keys)
				{
					Object val1 = wfQueryModeratedProps.get(key);
					Object val2 = workflowTask.properties.get(key);
					if(!val1.equals(val2))
					{
						// crap detected
						crap = true;
						System.out.println("ALFCOM-2598 key:" + key + "query:" + val1 +  "task:" + val2);
						break;
					}
				}
				//TODO END ALFCOM-2598 Work-around
				
				result.setInviteId(workflowId);
				if(!crap)
				{
					ret.add(result);
				}
			}
		}

		// End moderated invitation

		return ret;
	}

	// Implementation methods below
	/**
	 * Services
	 */
	private WorkflowService workflowService;
	private PersonService personService;
	private SiteService siteService;
	private AuthenticationService authenticationService;
	private PermissionService permissionService;
	private MutableAuthenticationDao mutableAuthenticationDao;
	private NamespaceService namespaceService;
	private NodeService nodeService;
	// user name and password generation beans
	private UserNameGenerator usernameGenerator;
	private PasswordGenerator passwordGenerator;

	/**
	 * Set the workflow service
	 * 
	 * @param workflowService
	 */
	public void setWorkflowService(WorkflowService workflowService) {
		this.workflowService = workflowService;
	}

	/**
	 * @return the workflow service
	 */
	public WorkflowService getWorkflowService() {
		return workflowService;
	}

	public void setPersonService(PersonService personService) {
		this.personService = personService;
	}

	public PersonService getPersonService() {
		return personService;
	}

	public void setSiteService(SiteService siteService) {
		this.siteService = siteService;
	}

	public SiteService getSiteService() {
		return siteService;
	}

	public void setAuthenticationService(
			AuthenticationService authenticationService) {
		this.authenticationService = authenticationService;
	}

	public AuthenticationService getAuthenticationService() {
		return authenticationService;
	}

	public void setUserNameGenerator(UserNameGenerator usernameGenerator) {
		this.usernameGenerator = usernameGenerator;
	}

	public UserNameGenerator getUserNameGenerator() {
		return usernameGenerator;
	}

	public void setPasswordGenerator(PasswordGenerator passwordGenerator) {
		this.passwordGenerator = passwordGenerator;
	}

	public PasswordGenerator getPasswordGenerator() {
		return passwordGenerator;
	}

	public void setNamespaceService(NamespaceService namespaceService) {
		this.namespaceService = namespaceService;
	}

	public NamespaceService getNamespaceService() {
		return namespaceService;
	}

	public void setPermissionService(PermissionService permissionService) {
		this.permissionService = permissionService;
	}

	public PermissionService getPermissionService() {
		return permissionService;
	}

	public void setMutableAuthenticationDao(
			MutableAuthenticationDao mutableAuthenticationDao) {
		this.mutableAuthenticationDao = mutableAuthenticationDao;
	}

	public MutableAuthenticationDao getMutableAuthenticationDao() {
		return mutableAuthenticationDao;
	}

	public void setNodeService(NodeService nodeService) {
		this.nodeService = nodeService;
	}

	public NodeService getNodeService() {
		return nodeService;
	}

	/**
	 * Creates a person for the invitee with a generated user name.
	 * 
	 * @param inviteeFirstName
	 *            first name of invitee
	 * @param inviteeLastName
	 *            last name of invitee
	 * @param inviteeEmail
	 *            email address of invitee
	 * @return invitee user name
	 */
	private String createInviteePerson(String inviteeFirstName,
			String inviteeLastName, String inviteeEmail) {
		// Attempt to generate user name for invitee
		// which does not belong to an existing person
		// Tries up to MAX_NUM_INVITEE_USER_NAME_GEN_TRIES
		// at which point a web script exception is thrown
		String inviteeUserName = null;
		int i = 0;
		do {
			inviteeUserName = usernameGenerator.generateUserName();
			i++;
		} while (this.personService.personExists(inviteeUserName)
				&& (i < MAX_NUM_INVITEE_USER_NAME_GEN_TRIES));

		// if after 10 tries is not able to generate a user name for a
		// person who doesn't already exist, then throw a web script exception
		if (this.personService.personExists(inviteeUserName)) {

			logger.debug("Failed - unable to generate username for invitee.");

			Object[] objs = { inviteeFirstName, inviteeLastName, inviteeEmail };
			throw new InvitationException(
					"invitation.invite.unable_generate_id", objs);
		}

		// create a person node for the invitee with generated invitee user name
		// and other provided person property values
		final Map<QName, Serializable> properties = new HashMap<QName, Serializable>();
		properties.put(ContentModel.PROP_USERNAME, inviteeUserName);
		properties.put(ContentModel.PROP_FIRSTNAME, inviteeFirstName);
		properties.put(ContentModel.PROP_LASTNAME, inviteeLastName);
		properties.put(ContentModel.PROP_EMAIL, inviteeEmail);

		final String finalUserName = inviteeUserName;
		AuthenticationUtil.runAs(new RunAsWork<Object>() {
			public Object doWork() throws Exception {
				NodeRef person = personService.createPerson(properties);
				permissionService.setPermission(person, finalUserName,
						PermissionService.ALL_PERMISSIONS, true);

				return null;
			}

		}, AuthenticationUtil.getSystemUserName());

		return inviteeUserName;
	}

	/**
	 * Creates a disabled user account for the given invitee user name with a
	 * generated password
	 * 
	 * @param inviteeUserName
	 * @return password generated for invitee user account
	 */
	private String createInviteeDisabledAccount(String inviteeUserName) {
		// generate password using password generator
		char[] generatedPassword = passwordGenerator.generatePassword()
				.toCharArray();

		// create disabled user account for invitee user name with generated
		// password
		this.mutableAuthenticationDao.createUser(inviteeUserName,
				generatedPassword);
		this.mutableAuthenticationDao.setEnabled(inviteeUserName, false);

		return String.valueOf(generatedPassword);
	}

	/**
	 * Moderated invitation implementation
	 * 
	 * @param inviteeComments
	 * @param inviteeUserName
	 * @param resourceType
	 * @param resourceName
	 * @param inviteeRole
	 * @return the new moderated invitation
	 */
	private ModeratedInvitation startInvite(String inviteeComments,
			String inviteeUserName, Invitation.ResourceType resourceType,
			String resourceName, String inviteeRole) 
	{

		// Get invitee person NodeRef to add as assignee
		NodeRef inviteeNodeRef = this.personService.getPerson(inviteeUserName);
		
		siteService.getSite(resourceName);

		if (siteService.isMember(resourceName, inviteeUserName)) {
			if (logger.isDebugEnabled())
				logger
				.debug("Failed - invitee user is already a member of the site.");

			Object objs[] = { inviteeUserName, "", resourceName };
			throw new InvitationExceptionUserError(
					"invitation.invite.already_member", objs);
		}
		
		String roleGroup = siteService.getSiteRoleGroup(resourceName,
				SiteModel.SITE_MANAGER);
		
		NodeRef wfPackage = this.workflowService.createPackage(null);

		Map<QName, Serializable> workflowProps = new HashMap<QName, Serializable>(
				16);
		workflowProps.put(WorkflowModel.ASSOC_PACKAGE, wfPackage);
		workflowProps.put(WorkflowModel.ASSOC_ASSIGNEE, inviteeNodeRef);
		workflowProps.put(
				WorkflowModelModeratedInvitation.ASSOC_GROUP_ASSIGNEE,
				roleGroup);
		workflowProps.put(
				WorkflowModelModeratedInvitation.WF_PROP_INVITEE_COMMENTS,
				inviteeComments);
		workflowProps.put(
				WorkflowModelModeratedInvitation.WF_PROP_INVITEE_ROLE,
				inviteeRole);
		workflowProps.put(
				WorkflowModelModeratedInvitation.WF_PROP_INVITEE_USER_NAME,
				inviteeUserName);
		workflowProps.put(
				WorkflowModelNominatedInvitation.WF_PROP_RESOURCE_NAME,
				resourceName);
		workflowProps.put(
				WorkflowModelNominatedInvitation.WF_PROP_RESOURCE_TYPE,
				resourceType.toString());

		// get the moderated workflow

		WorkflowDefinition wfDefinition = this.workflowService
				.getDefinitionByName(WorkflowModelModeratedInvitation.WORKFLOW_DEFINITION_NAME);
		if (wfDefinition == null) {
			// handle workflow definition does not exist
			Object objs[] = { WorkflowModelModeratedInvitation.WORKFLOW_DEFINITION_NAME };
			throw new InvitationException("invitation.error.noworkflow", objs);
		}

		// start the workflow
		WorkflowPath wfPath = this.workflowService.startWorkflow(wfDefinition
				.getId(), workflowProps);

		String workflowId = wfPath.instance.id;
		String wfPathId = wfPath.id;
		List<WorkflowTask> wfTasks = this.workflowService
				.getTasksForWorkflowPath(wfPathId);

		// throw an exception if no tasks where found on the workflow path
		if (wfTasks.size() == 0) 
		{
			Object objs[] = { WorkflowModelModeratedInvitation.WORKFLOW_DEFINITION_NAME };
			throw new InvitationException("invitation.error.notasks", objs);
		}

		try {
			WorkflowTask wfStartTask = wfTasks.get(0);
			this.workflowService.endTask(wfStartTask.id, null);
		} catch (RuntimeException err) {
			if (logger.isDebugEnabled())
				logger.debug("Failed - caught error during Invite workflow transition: "
					+ err.getMessage());
			throw err;
		}

		ModeratedInvitationImpl result = new ModeratedInvitationImpl(
				workflowProps);
		result.setInviteId(workflowId);
		return result;
	}

	/**
	 * Starts the Invite workflow
	 * 
	 * @param inviteeFirstName
	 *            first name of invitee
	 * @param inviteeLastNamme
	 *            last name of invitee
	 * @param inviteeEmail
	 *            email address of invitee
	 * @param siteShortName
	 *            short name of site that the invitee is being invited to by the
	 *            inviter
	 * @param inviteeSiteRole
	 *            role under which invitee is being invited to the site by the
	 *            inviter
	 * @param serverPath
	 *            externally accessible server address of server hosting invite
	 *            web scripts
	 */
	private NominatedInvitation startInvite(String inviteeFirstName,
			String inviteeLastName, String inviteeEmail,
			String inviteeUserName, Invitation.ResourceType resourceType,
			String siteShortName, String inviteeSiteRole, String serverPath,
			String acceptUrl, String rejectUrl) {
		
		// get the inviter user name (the name of user web script is executed
		// under)
		String inviterUserName = this.authenticationService
				.getCurrentUserName();
	
		checkManagerRole(inviterUserName, resourceType, siteShortName);

		if (logger.isDebugEnabled()) {
			logger.debug("startInvite() inviterUserName=" + inviterUserName
					+ " inviteeUserName=" + inviteeUserName
					+ " inviteeFirstName=" + inviteeFirstName
					+ " inviteeLastName=" + inviteeLastName + " inviteeEmail="
					+ inviteeEmail + " siteShortName=" + siteShortName
					+ " inviteeSiteRole=" + inviteeSiteRole);
		}
		//
		// if we have not explicitly been passed an existing user's user name
		// then ....
		//
		// if a person already exists who has the given invitee email address
		//
		// 1) obtain invitee user name from first person found having the
		// invitee email address (there
		// should only be one)
		// 2) handle error conditions - (invitee already has an invitation in
		// progress for the given site,
		// or he/she is already a member of the given site
		//        
		if (inviteeUserName == null || inviteeUserName.trim().length() == 0) {
			Set<NodeRef> peopleWithInviteeEmail = this.personService
					.getPeopleFilteredByProperty(ContentModel.PROP_EMAIL,
							inviteeEmail);
			if (peopleWithInviteeEmail.isEmpty() == false) {
				// get person already existing who has the given
				// invitee email address (there should only be one, so just take
				// the first from the set of people).
				NodeRef person = (NodeRef) peopleWithInviteeEmail.toArray()[0];

				// get invitee user name of that person
				Serializable userNamePropertyVal = this.getNodeService()
						.getProperty(person, ContentModel.PROP_USERNAME);
				inviteeUserName = DefaultTypeConverter.INSTANCE.convert(
						String.class, userNamePropertyVal);

				if (logger.isDebugEnabled())
					logger
							.debug("not explictly passed username - found matching email, resolved inviteeUserName="
									+ inviteeUserName);
			}
			// else there are no existing people who have the given invitee
			// email address
			// so create invitee person
			else {
				inviteeUserName = createInviteePerson(inviteeFirstName,
						inviteeLastName, inviteeEmail);

				if (logger.isDebugEnabled())
					logger
							.debug("not explictly passed username - created new person, inviteeUserName="
									+ inviteeUserName);
			}
		}

		// throw web script exception if person is already a member of the given
		// site
		if (this.siteService.isMember(siteShortName, inviteeUserName)) {
			if (logger.isDebugEnabled())
				logger
						.debug("Failed - invitee user is already a member of the site.");

			Object objs[] = { inviteeUserName, inviteeEmail, siteShortName };
			throw new InvitationExceptionUserError(
					"invitation.invite.already_member", objs);
		}

		//
		// If a user account does not already exist for invitee user name
		// then create a disabled user account for the invitee.
		// Hold a local reference to generated password if disabled invitee
		// account
		// is created, otherwise if a user account already exists for invitee
		// user name, then local reference to invitee password will be "null"
		//
		String inviteePassword = null;
		if (this.mutableAuthenticationDao.userExists(inviteeUserName) == false) {
			if (logger.isDebugEnabled())
				logger
						.debug("Invitee user account does not exist, creating disabled account.");
			inviteePassword = createInviteeDisabledAccount(inviteeUserName);
		}

		// create a ticket for the invite - this is used
		String inviteTicket = GUID.generate();

		//
		// Start the invite workflow with inviter, invitee and site properties
		//

		WorkflowDefinition wfDefinition = this.workflowService
				.getDefinitionByName(WorkflowModelNominatedInvitation.WORKFLOW_DEFINITION_NAME);

		if (wfDefinition == null) {
			// handle workflow definition does not exist
			Object objs[] = { WorkflowModelNominatedInvitation.WORKFLOW_DEFINITION_NAME };
			throw new InvitationException("invitation.error.noworkflow", objs);
		}

		// Get invitee person NodeRef to add as assignee
		NodeRef inviteeNodeRef = this.personService.getPerson(inviteeUserName);

		// create workflow properties
		Map<QName, Serializable> workflowProps = new HashMap<QName, Serializable>(
				16);
		workflowProps.put(
				WorkflowModelNominatedInvitation.WF_PROP_INVITER_USER_NAME,
				inviterUserName);
		workflowProps.put(
				WorkflowModelNominatedInvitation.WF_PROP_INVITEE_USER_NAME,
				inviteeUserName);
		workflowProps.put(WorkflowModel.ASSOC_ASSIGNEE, inviteeNodeRef);
		workflowProps.put(
				WorkflowModelNominatedInvitation.WF_PROP_INVITEE_FIRSTNAME,
				inviteeFirstName);
		workflowProps.put(
				WorkflowModelNominatedInvitation.WF_PROP_INVITEE_LASTNAME,
				inviteeLastName);
		workflowProps.put(
				WorkflowModelNominatedInvitation.WF_PROP_INVITEE_GEN_PASSWORD,
				inviteePassword);
		workflowProps.put(
				WorkflowModelNominatedInvitation.WF_PROP_RESOURCE_NAME,
				siteShortName);
		workflowProps.put(
				WorkflowModelNominatedInvitation.WF_PROP_RESOURCE_TYPE,
				resourceType.toString());
		workflowProps.put(
				WorkflowModelNominatedInvitation.WF_PROP_INVITEE_SITE_ROLE,
				inviteeSiteRole);
		workflowProps.put(WorkflowModelNominatedInvitation.WF_PROP_SERVER_PATH,
				serverPath);
		workflowProps.put(WorkflowModelNominatedInvitation.WF_PROP_ACCEPT_URL,
				acceptUrl);
		workflowProps.put(WorkflowModelNominatedInvitation.WF_PROP_REJECT_URL,
				rejectUrl);
		workflowProps.put(
				WorkflowModelNominatedInvitation.WF_PROP_INVITE_TICKET,
				inviteTicket);

		// start the workflow
		WorkflowPath wfPath = this.workflowService.startWorkflow(wfDefinition
				.getId(), workflowProps);

		//
		// complete invite workflow start task to send out the invite email
		//

		// get the workflow tasks
		String workflowId = wfPath.instance.id;
		String wfPathId = wfPath.id;
		List<WorkflowTask> wfTasks = this.workflowService
				.getTasksForWorkflowPath(wfPathId);

		// throw an exception if no tasks where found on the workflow path
		if (wfTasks.size() == 0) {
			Object objs[] = { WorkflowModelNominatedInvitation.WORKFLOW_DEFINITION_NAME };
			throw new InvitationException("invitation.error.notasks", objs);
		}

		//
		// first task in workflow task list (there should only be one)
		// associated
		// with the workflow path id (above) should be "wf:inviteToSiteTask",
		// otherwise
		// throw web script exception
		//
		String wfTaskName = wfTasks.get(0).name;
		QName wfTaskNameQName = QName.createQName(wfTaskName,
				this.namespaceService);
		QName inviteToSiteTaskQName = WorkflowModelNominatedInvitation.WF_INVITE_TASK_INVITE_TO_SITE;
		if (!wfTaskNameQName.equals(inviteToSiteTaskQName)) {
			Object objs[] = {
					wfPathId,
					WorkflowModelNominatedInvitation.WF_INVITE_TASK_INVITE_TO_SITE };
			throw new InvitationException("invitation.error.wrong_first_task",
					objs);
		}

		// get "inviteToSite" task
		WorkflowTask wfStartTask = wfTasks.get(0);

		// attach empty package to start task, end it and follow with transition
		// that sends out the invite
		if (logger.isDebugEnabled())
			logger
					.debug("Starting Invite workflow task by attaching empty package...");
		NodeRef wfPackage = this.workflowService.createPackage(null);
		Map<QName, Serializable> wfTaskProps = new HashMap<QName, Serializable>(
				1, 1.0f);
		wfTaskProps.put(WorkflowModel.ASSOC_PACKAGE, wfPackage);

		if (logger.isDebugEnabled())
			logger.debug("Updating Invite workflow task...");
		this.workflowService
				.updateTask(wfStartTask.id, wfTaskProps, null, null);

		if (logger.isDebugEnabled())
			logger.debug("Transitioning Invite workflow task...");
		try {
			this.workflowService.endTask(wfStartTask.id,
					WorkflowModelNominatedInvitation.WF_TRANSITION_SEND_INVITE);
		} catch (RuntimeException err) {
			if (logger.isDebugEnabled())
				logger
						.debug("Failed - caught error during Invite workflow transition: "
								+ err.getMessage());
			throw err;
		}

		NominatedInvitationImpl result = new NominatedInvitationImpl(
				workflowProps);
		result.setTicket(inviteTicket);
		result.setInviteId(workflowId);
		return result;
	}
	
	
	/**
	 * Check that the specified user has manager role over the resource.
	 * @param userId
	 * @throws InvitationException
	 */
	private void checkManagerRole(String userId, Invitation.ResourceType resourceType, String siteShortName)
	{
		// if inviter is not the site manager then throw web script exception
		String inviterRole = this.siteService.getMembersRole(siteShortName,
				userId);
		if ((inviterRole == null)
				|| (inviterRole.equals(SiteModel.SITE_MANAGER) == false)) {

			Object objs[] = { userId, siteShortName };
			throw new InvitationExceptionForbidden(
					"invitation.invite.not_site_manager", objs);
		}
	}
}
