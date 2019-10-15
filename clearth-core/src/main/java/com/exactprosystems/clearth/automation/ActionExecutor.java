/******************************************************************************
 * Copyright 2009-2019 Exactpro Systems Limited
 * https://www.exactpro.com
 * Build Software to Test Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.exactprosystems.clearth.automation;

import com.exactprosystems.clearth.automation.actions.SchedulerPause;
import com.exactprosystems.clearth.automation.async.AsyncActionData;
import com.exactprosystems.clearth.automation.async.AsyncActionsManager;
import com.exactprosystems.clearth.automation.exceptions.FailoverException;
import com.exactprosystems.clearth.automation.report.ActionReportWriter;
import com.exactprosystems.clearth.automation.report.FailReason;
import com.exactprosystems.clearth.automation.report.Result;
import com.exactprosystems.clearth.automation.report.results.DefaultResult;
import com.exactprosystems.clearth.utils.Utils;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ActionExecutor implements Closeable
{
	private static final Logger logger = LoggerFactory.getLogger(ActionExecutor.class);
	public static final String PARAMS_IN = "in",
			PARAMS_OUT = "out",
			PARAMS_PREV_ACTION = "prevAction",
			PARAMS_THIS_ACTION = "thisAction",
			VARKEY_ACTION = "action";
	
	private final GlobalContext globalContext;
	private final ActionParamsCalculator calculator;
	private final ActionReportWriter reportWriter;
	private final FailoverStatus failoverStatus;
	private AsyncActionsManager asyncManager;
	
	private ActionsExecutionProgress executionProgress;
	private String actionsReportsDir;
	private boolean interrupted = false;
	
	public ActionExecutor(GlobalContext globalContext, ActionParamsCalculator calculator, ActionReportWriter reportWriter,
			FailoverStatus failoverStatus)
	{
		this.globalContext = globalContext;
		this.calculator = calculator;
		this.reportWriter = reportWriter;
		this.failoverStatus = failoverStatus;
		this.asyncManager = null;
	}
	
	
	@Override
	public void close() throws IOException
	{
		Utils.closeResource(asyncManager);
	}
	
	
	public ActionParamsCalculator getCalculator()
	{
		return calculator;
	}

	public ActionReportWriter getReportWriter()
	{
		return reportWriter;
	}
	
	
	/**
	 * Use this method to prepare action executor for actions from new step
	 */
	public void reset(String actionsReportsDir, ActionsExecutionProgress executionProgress)
	{
		this.executionProgress = executionProgress;
		reportWriter.reset();
		this.actionsReportsDir = actionsReportsDir;
	}


	public void prepareToAction(Action action)
	{
		Matrix matrix = action.getMatrix();
		String stepName = action.getStepName();
		if (!matrix.getStepSuccess().containsKey(stepName))
			matrix.setStepSuccessful(stepName, true);
	}
	
	public boolean prepareActionReplay(Action action)
	{
		//If we are replaying the step we need to replay only failed ReceiveXXX actions
		Matrix matrix = action.getMatrix();
		String stepName = action.getStepName();
		
		if (!isFailedReplayableAction(action))
		{
			if ((action.getResult() != null) && (!action.getResult().isSuccess()))
			{
				matrix.setStepSuccessful(stepName, false);
				matrix.addStepStatusComment(stepName, "One or more actions failed");
			}
			else if (!action.isSubaction())
			{
				executionProgress.incrementSuccessful();
				matrix.incActionsSuccess();
			}
			
			if (!action.isSubaction())
				executionProgress.incrementDone();
			return false;
		}
		
		action.setDone(false);
		matrix.setActionsDone(matrix.getActionsDone() - 1);
		return true;
	}
	
	public void executeAction(Action action, StepContext stepContext, AtomicBoolean canReplay)
	{
		String actionDesc = null;
		
		boolean stepExecutable = action.getStep().isExecute();
		try
		{
			if (getLogger().isDebugEnabled())
			{
				actionDesc = action.getDescForLog("");
				getLogger().debug("Calculating parameters of{}", actionDesc);
			}

			addActionParamsToMvel(action);
			List<String> errorsInParams = calculator.calculateParameters(action, stepExecutable);
			
			if (getLogger().isTraceEnabled())
				getLogger().trace("Finished calculation for{}", actionDesc != null ? actionDesc : action.getDescForLog(""));
			
			if (action.isExecutable())
			{
				if (!doExecuteAction(action, errorsInParams, stepContext, canReplay))  //Action may trigger step end due to aborted failover
					return;
			}
			else if (action.getFormulaExecutable() != null)
				handleNonExecutableAction(action);
		}
		catch (Exception e)
		{
			handleActionCrash(action, e);
		}
		
		if (!isAsyncAction(action))
			cleanContexts(action);
	}
	
	public void callActionAsync(Action action, StepContext stepContext, MatrixContext matrixContext) throws InterruptedException
	{
		if (!isAsyncEnabled())
			asyncManager = createAsyncManager();
		
		action.setFinished(new Date());
		asyncManager.addAsyncAction(createAsyncActionData(action, stepContext, matrixContext));
		
		String waitMsg;
		switch (action.getWaitAsyncEnd())
		{
		case STEP : waitMsg = "Will wait in end of step for this action to finish"; break;
		case SCHEDULER : waitMsg = "Will wait in end of scheduler for this action to finish"; break;
		default : waitMsg = "Won't wait for this action to finish"; break;
		}
		
		action.setResult(DefaultResult.passed("Action is executing asynchronously. "+waitMsg));
	}
	
	public void callAction(Action action, StepContext stepContext, MatrixContext matrixContext) throws FailoverException
	{
		//Execution may fail with FailoverException indicating a need to establish new connection
		action.execute(stepContext, matrixContext, globalContext);
		action.setFinished(new Date());
	}
	
	public void checkAsyncActions()
	{
		if (!isAsyncEnabled())
			return;
		
		AsyncActionData a;
		while ((a = asyncManager.getNextFinishedAction()) != null)
		{
			Action action = a.getAction();
			actionToMvel(action, globalContext);
			updateAsyncActionReport(a);
			afterAsyncAction(action);
			cleanAfterAsyncAction(action);
		}
	}
	
	public void waitForStepAsyncActions(String stepName) throws InterruptedException
	{
		if ((!isAsyncEnabled()) || (isExecutionInterrupted()))
			return;
		
		Set<AsyncActionData> actions = asyncManager.getStepActions(stepName);
		if ((actions == null) || (actions.isEmpty()))
			return;
		
		getLogger().debug("Waiting for step '"+stepName+"' async actions to finish");
		waitForAsyncActions(actions);
	}
	
	public void waitForSchedulerAsyncActions() throws InterruptedException
	{
		if ((!isAsyncEnabled()) || (isExecutionInterrupted()))
			return;
		
		Set<AsyncActionData> actions = asyncManager.getSchedulerActions();
		if ((actions == null) || (actions.isEmpty()))
			return;
		
		getLogger().debug("Waiting for scheduler async actions to finish");
		waitForAsyncActions(actions);
	}
	
	public void interruptExecution()
	{
		interrupted = true;
		if (isAsyncEnabled())
			asyncManager.interruptExecution();
	}
	
	public boolean isExecutionInterrupted()
	{
		return interrupted;
	}
	
	
	protected Logger getLogger()
	{
		return logger;
	}
	
	protected GlobalContext getGlobalContext()
	{
		return globalContext;
	}
	
	
	protected AsyncActionsManager getAsyncManager()
	{
		return asyncManager;
	}
	
	protected boolean isAsyncEnabled()
	{
		return asyncManager != null;
	}
	
	protected AsyncActionsManager createAsyncManager()
	{
		return new AsyncActionsManager(globalContext);
	}
	
	public static boolean isAsyncAction(Action action)
	{
		return (action.isAsync()) && (!action.isSubaction()) && (!(action instanceof SchedulerPause));
	}
	
	protected AsyncActionData createAsyncActionData(Action action, StepContext stepContext, MatrixContext matrixContext)
	{
		return new AsyncActionData(action, stepContext, matrixContext);
	}
	
	protected int getAsyncEndWaitInterval()
	{
		return 1000;
	}
	
	protected void updateAsyncActionReport(AsyncActionData actionData)
	{
		Action action = actionData.getAction();
		Result result = actionData.getResult();
		if (getLogger().isTraceEnabled())
			getLogger().trace(action.getDescForLog("Updating report of"));
		
		action.setStarted(actionData.getStarted());
		action.setFinished(actionData.getFinished());
		action.setResult(result);
		action.setPayloadFinished(true);
		applyActionResult(action, false);
		reportWriter.updateReports(action, actionsReportsDir, action.getStep().getSafeName());
		processActionResult(action);
		
		// If asynchronous action is finished and failed, need to decrement number of successful actions for certain step
		// because all async actions are treated as passed on start of their executions
		if (result != null && !result.isSuccess())
			action.getStep().getExecutionProgress().decrementSuccessful();
	}

	protected void afterAsyncAction(@SuppressWarnings("unused") Action action) { /* Nothing to do by default*/ }
	
	protected void cleanAfterAsyncAction(Action action)
	{
		action.dispose();
		cleanContexts(action);
	}
	
	protected void waitForAsyncActions(Set<AsyncActionData> actions) throws InterruptedException
	{
		int asyncEndWaitInterval = getAsyncEndWaitInterval();
		for (AsyncActionData action : actions)
		{
			while (!asyncManager.isActionFinished(action))
			{
				Thread.sleep(asyncEndWaitInterval);
				//InterruptedException may have occurred in other place (in callActionAsync(), for example) so that sleep ends correctly.
				//But if execution is interrupted, need to break the loop anyway
				if (isExecutionInterrupted())
					break;
			}
			
			if (isExecutionInterrupted())
				break;
		}
		actions.clear();  //All actions finished. Clearing history to free memory
	}
	
	
	protected void handleActionCrash(Action action, Exception e)
	{
		//Action crashed. Write error to log, store fact of crash and set Matrix to FAILED
		String actionId = action.getIdInMatrix();
		Matrix matrix = action.getMatrix();
		String stepName = action.getStepName();
		
		if (getLogger().isErrorEnabled())
			getLogger().error(action.getDescForLog("Error while running"), e);
		
		Result result = DefaultResult.failed(e);
		result.setCrashed(true);
		
		action.setResult(result);
		action.setFinished(new Date());
		
		if (action.isSubaction())
		{
			SubActionData subActionData = new SubActionData(action);
			matrix.getContext().setSubActionData(actionId, subActionData);
			subActionData.setException(e);
		}
		
		matrix.setStepSuccessful(stepName, false);
		matrix.addStepStatusComment(stepName, "One or more actions CRASHED");
		
		reportWriter.writeReport(action, actionsReportsDir, action.getStep().getSafeName(), true);
	}
	
	
	protected void handleDuplicateParameters(Action action, SubActionData subActionData)
	{
		StringBuilder comment = new StringBuilder("Duplicate parameters: ");
		for (int i = 0; i < action.getDuplicateParams().size(); i++)
		{
			if (i > 0)
				comment.append(", ");
			comment.append("'" + action.getDuplicateParams().get(i) + "'");
		}
		comment.append(". Check action string in matrix");

		if (!action.isSubaction())
		{
			Result actionResult = DefaultResult.failed(comment.toString());
			actionResult.setFailReason(FailReason.CALCULATION);
			
			action.setResult(actionResult);
		}
		else
			subActionData.setFailedComment(comment.toString());
	}
	
	protected void handleErrorsInParameters(Action action, SubActionData subActionData, List<String> errorsInParams)
	{
		StringBuilder comment = new StringBuilder("Could not calculate the following parameters: \r\n");
		for (String error : errorsInParams)
			comment.append(error).append("\r\n");
		comment.append("Check if all references and function calls are correct and all referenced actions are successful");
		
		if (!action.isSubaction())
		{
			Result actionResult = new DefaultResult();
			actionResult.setSuccess(false);
			actionResult.setFailReason(FailReason.CALCULATION);
			actionResult.setComment(comment.toString());
			
			action.setResult(actionResult);
		}
		else
			subActionData.setFailedComment(comment.toString());
	}
	
	protected void applyActionResult(Action action, boolean countSuccess)
	{
		Matrix matrix = action.getMatrix();
		Result result = action.getResult();
		if (result != null)
		{
			// If action is inverted - invert the result
			if (action.isInverted())
				result.setInverted(true);
			
			if (result.isSuccess())
			{
				if (!action.isSubaction() && countSuccess)
				{
					executionProgress.incrementSuccessful();
					matrix.incActionsSuccess();
				}
			}
			else
			{
				// If verification is not successful - mark this matrix and step as FAILED
				String stepName = action.getStepName();
				matrix.setStepSuccessful(stepName, false);
				matrix.addStepStatusComment(stepName, "One or more actions failed");
			}
			
			action.setPassed(result.isSuccess());
		}
		else
		{
			action.setPassed(true);
			if (!action.isSubaction() && countSuccess)
			{
				executionProgress.incrementSuccessful();
				matrix.incActionsSuccess();
			}
		}
	}
	
	protected void processActionResult(Action action)
	{
		Result result = action.getResult();
		if (result == null)
			return;
		
		if (globalContext.getLoadedContext(GlobalContext.TEST_MODE) == null)
			result.clearDetails();
	}
	
	protected SubActionData createSubActionData(Action action)
	{
		return new SubActionData(action);
	}

	@SuppressWarnings("unchecked")
	protected void addActionResultToMvel(Action action, MatrixFunctions matrixFunctions,
	                                     Map<String, Object> mvelVars)
	{
		String id = MatrixFunctions.resolveFixedId(action.getIdInMatrix(), calculator.getFixedIds());
		Map<String, Object> actionVars = (Map<String, Object>) mvelVars.get(id);

		if (action.getOutputParams() != null)
			addOutputParams(actionVars, action.getOutputParams());
		
		mvelVars.put(ActionExecutor.PARAMS_PREV_ACTION, actionVars);
		actionVars.put(VARKEY_ACTION, matrixFunctions.getExecutionResultParams(action));
	}

	protected void addActionParamsToMvel(Action action)
	{
		Map<String, Object> mvelVars = action.getMatrix().getMvelVars();
		mvelVars.put(ActionExecutor.PARAMS_THIS_ACTION, action.getInputParams());
	}
	
	protected void addOutputParams(Map<String, Object> destination, Map<String, String> outputParams)
	{
		Map<String, Object> commonParams = new LinkedHashMap<String, Object>(outputParams);
		Map<String, Object> justOutput = new LinkedHashMap<String, Object>(outputParams);
		commonParams.put(PARAMS_OUT, justOutput);
		destination.putAll(commonParams);
	}
	
	@SuppressWarnings("unchecked")
	protected void addSubParamsToMvel(LinkedHashMap<String, LinkedHashMap<String, String>> subParams, Map<String, Object> execResultParams, 
			Map<String, Object> mvelVars)
	{
		Map<String, String> fixedIds = calculator.getFixedIds();
		for (String subActionId : subParams.keySet())
		{
			if (subActionId == null)  //This may happen if sub-output parameters are based on some generated message, not on matrix actions
				continue;
			
			String subId = MatrixFunctions.resolveFixedId(subActionId, fixedIds);
			Map<String, Object> subVars = (Map<String, Object>) mvelVars.get(subId);
			if (subVars == null)  //Checking if reference is valid just in case
				continue;
			
			LinkedHashMap<String, String> subOutParams = subParams.get(subActionId);
			subVars.putAll(subOutParams);
			subVars.put(PARAMS_OUT, subOutParams);
			subVars.put(VARKEY_ACTION, execResultParams);
		}
	}
	
	@SuppressWarnings("unchecked")
	protected void addSubDataToMvel(LinkedHashMap<String, SubActionData> subData, Map<String, Object> execResultParams,
			Map<String, Object> mvelVars)
	{
		Map<String, String> fixedIds = calculator.getFixedIds();
		for (String subActionId : subData.keySet())
		{
			String subId = MatrixFunctions.resolveFixedId(subActionId, fixedIds);
			Map<String, Object> subVars = (Map<String, Object>) mvelVars.get(subId);
			subVars.put(VARKEY_ACTION, execResultParams);
		}
	}
	
	protected void actionToMvel(Action action, GlobalContext globalContext)
	{
		if (getLogger().isTraceEnabled())
			getLogger().trace(action.getDescForLog("Adding output params of"));
		
		Map<String, Object> mvelVars = action.getMatrix().getMvelVars();
		addActionResultToMvel(action, globalContext.getMatrixFunctions(), mvelVars);
		
		LinkedHashMap<String, LinkedHashMap<String, String>> subParams = action.getSubOutputParams();
		LinkedHashMap<String, SubActionData> subData = action.getSubActionData();
		//Need to put sub-output parameters to mvelVars to make them available by reference. 
		//Even if there is no sub-output parameters, action result should be available for sub-actions as well
		if ((subParams == null) && (subData == null))
			return;
		
		Map<String, Object> execResultParams = globalContext.getMatrixFunctions().getExecutionResultParams(action);
		if (subParams != null)
			addSubParamsToMvel(subParams, execResultParams, mvelVars);  //This does the same as addSubDataToMvel+something more
		else
			addSubDataToMvel(subData, execResultParams, mvelVars);
	}
	
	
	//*** Action execution methods ***
	protected boolean checkAction(Action action, SubActionData subActionData, List<String> errorsInParams, 
			StepContext stepContext, MatrixContext matrixContext)
	{
		if (action.getDuplicateParams() != null && action.duplicateParamsDisabled())
		{
			handleDuplicateParameters(action, subActionData);
			return false;
		}
		else
		{
			Result actionResult = checkAction(action, stepContext, matrixContext);
			if (actionResult != null)
			{
				if (!action.isSubaction())
					action.setResult(actionResult);
				else
					subActionData.setFailedComment(actionResult.getComment());
				
				return false;
			}
			
			if (!CollectionUtils.isEmpty(errorsInParams))
			{
				handleErrorsInParameters(action, subActionData, errorsInParams);
				return false;
			}
		}
		
		return true;
	}
	
	protected void prepareToAction(Action action, StepContext stepContext, MatrixContext matrixContext) throws FailoverException
	{
	}
	
	protected void handleTimeout(Action action) throws InterruptedException
	{
		//If you handle timeout in a special way, don't forget to amend ReportStatus to change its actualTimeout and waitBeforeAction fields
		if ((!(action instanceof TimeoutAwaiter) || !((TimeoutAwaiter)action).isUsesTimeout()) && (action.getTimeOut() > 0))
			Thread.sleep(action.getTimeOut());
	}
	
	protected void checkActionResult(Action action) throws FailoverException
	{
		Result result = action.getResult();
		if ((result != null) && (result.getFailoverData() != null))
			throw result.getFailoverData();
		
		String pauseDesc = null;
		if (action instanceof SchedulerPause)
			pauseDesc = ((SchedulerPause) action).getPauseDescription();
		else if (action.isSuspendIfFailed() && result != null && !result.isSuccess())
			pauseDesc = "Action with ID '" + action.getIdInMatrix() + "' from matrix '" + action.getMatrix().getName() + "' failed";
		
		if (pauseDesc != null)
			action.getStep().pauseAction(pauseDesc);
	}

	protected boolean doExecuteAction(Action action, List<String> errorsInParams, StepContext stepContext,	AtomicBoolean canReplay) throws InterruptedException
	{
		String actionId = action.getIdInMatrix();
		Matrix matrix = action.getMatrix();
		MatrixContext matrixContext = matrix.getContext();
		SubActionData subActionData = null;
		String actionDesc = null;
		
		action.setDone(true);
		if (getLogger().isInfoEnabled())
		{
			actionDesc = action.getDescForLog("");
			getLogger().info("Running{}", actionDesc);
		}
		
		//Run action
		if (!action.isSubaction())
		{
			executionProgress.incrementDone();
			matrix.incActionsDone();
		}
		else
		{
			subActionData = createSubActionData(action);
			matrixContext.setSubActionData(actionId, subActionData);
		}
		
		boolean needReturn = false;  //Indicates whether this method should end after action execution.
		//Usable when action has failed due to failover and has been aborted by user, but should be visible in report
		//If action shouldn't be executed due to some reason which needs to be shown in report - form appropriate result and skip action execution
		if (checkAction(action, subActionData, errorsInParams, stepContext, matrixContext))
		{
			//No errors found, action should be executed
			boolean passed;
			do
			{
				passed = true;
				try
				{
					prepareToAction(action, stepContext, matrixContext);  //Creating connections according to action type, if needed
					action.setStarted(new Date());
					handleTimeout(action);
					if (isAsyncAction(action))
						callActionAsync(action, stepContext, matrixContext);
					else
						callAction(action, stepContext, matrixContext);
					
					if (isFailedReplayableAction(action))
						canReplay.set(true);
					checkActionResult(action);
				}
				catch (FailoverException e)
				{
					passed = false;
					
					action.getStep().actionFailover(action, e, stepContext, matrixContext, globalContext);  //Disposing connections according to action type, if needed
					
					try
					{
						synchronized (failoverStatus)
						{
							failoverStatus.failover = true;
							failoverStatus.actionType = action.getActionType();
							failoverStatus.reason = e.getReason();
							failoverStatus.needRestartAction = true;
							failoverStatus.connection = e.getConnection();
							failoverStatus.setFailoverInfo(action, e);
							failoverStatus.wait(); // failoverStatus.needRestartAction may be changed in automationBean
							passed = !failoverStatus.needRestartAction;
						}
					}
					catch (InterruptedException e1)
					{
						getLogger().warn("Wait interrupted", e1);
						action.getStep().interruptExecution();
						synchronized (failoverStatus)
						{
							failoverStatus.failover = false;
						}
						
						if (action.getResult() == null)
							return false;
						else
						{
							needReturn = true;
							break;
						}
					}
				}
			}
			while (!passed);
		}
		
		applyActionResult(action, true);
		if (!action.isSubaction())
		{
			actionToMvel(action, globalContext);
			reportWriter.writeReport(action, actionsReportsDir, action.getStep().getSafeName(), !action.isPassed());
		}
		else
		{
			if (action.getSubActionData() != null && action.getSubActionData().size() > 0)
				matrixContext.getSubActionData(actionId).setSubActionData(action.getSubActionData());
		
			if (!action.isPassed())
				reportWriter.writeReport(action, actionsReportsDir, action.getStep().getSafeName(), true);
		}
		
		processActionResult(action);
		
		if (getLogger().isDebugEnabled())
			getLogger().debug("Finished{}", actionDesc != null ? actionDesc : action.getDescForLog(""));
		
		if (needReturn)  //If action had failed due to failover and has been aborted by user, but returned a result: result is written to report, now it's time to end the step due to abortation
			return false;
		return true;
	}
	
	protected void handleNonExecutableAction(Action action)
	{
		Result actionResult = new DefaultResult();
		actionResult.setSuccess(false);
		actionResult.setFailReason(FailReason.NOT_EXECUTED);
		
		action.setResult(actionResult);
		addActionResultToMvel(action, globalContext.getMatrixFunctions(), action.getMatrix().getMvelVars());
		
		reportWriter.writeReport(action, actionsReportsDir, action.getStep().getSafeName(), true);
	}
	
	protected void cleanContexts(Action action)
	{
		if (action.getCleanableContext() == null)
			return;
		
		MatrixContext mc = action.getMatrix().getContext();
		for (String clCont : action.getCleanableContext())
			mc.setContext(clCont, null);
	}
	
	
	protected boolean isFailedReplayableAction(Action action)
	{
		return false;
	}
	
	protected Result checkAction(Action action, StepContext stepContext, MatrixContext matrixContext)
	{
		return null;
	}

	public void afterActionsExecution(Step step)
	{
		reportWriter.makeReportsEnding(actionsReportsDir, step.getSafeName());
	}
}
