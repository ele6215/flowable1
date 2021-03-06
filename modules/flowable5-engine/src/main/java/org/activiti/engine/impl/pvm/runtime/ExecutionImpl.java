/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.activiti.engine.impl.pvm.runtime;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.activiti.engine.impl.persistence.entity.VariableInstanceEntity;
import org.activiti.engine.impl.pvm.PvmActivity;
import org.activiti.engine.impl.pvm.PvmException;
import org.activiti.engine.impl.pvm.PvmExecution;
import org.activiti.engine.impl.pvm.PvmProcessDefinition;
import org.activiti.engine.impl.pvm.PvmProcessElement;
import org.activiti.engine.impl.pvm.PvmProcessInstance;
import org.activiti.engine.impl.pvm.PvmTransition;
import org.activiti.engine.impl.pvm.delegate.ActivityExecution;
import org.activiti.engine.impl.pvm.delegate.ExecutionListenerExecution;
import org.activiti.engine.impl.pvm.delegate.SignallableActivityBehavior;
import org.activiti.engine.impl.pvm.process.ActivityImpl;
import org.activiti.engine.impl.pvm.process.ProcessDefinitionImpl;
import org.activiti.engine.impl.pvm.process.TransitionImpl;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.engine.delegate.ReadOnlyDelegateExecution;
import org.flowable.engine.impl.delegate.ReadOnlyDelegateExecutionImpl;
import org.flowable.engine.impl.util.ProcessDefinitionUtil;
import org.flowable.variable.api.persistence.entity.VariableInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Tom Baeyens
 * @author Joram Barrez
 * @author Daniel Meyer
 * @author Falko Menge
 */
public class ExecutionImpl implements
        Serializable,
        ActivityExecution,
        ExecutionListenerExecution,
        PvmExecution,
        InterpretableExecution {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionImpl.class);

    // current position /////////////////////////////////////////////////////////

    protected ProcessDefinitionImpl processDefinition;

    /**
     * current activity
     */
    protected ActivityImpl activity;

    protected FlowElement currentFlowElement;

    /**
     * current transition. is null when there is no transition being taken.
     */
    protected TransitionImpl transition;

    /**
     * the process instance. this is the root of the execution tree. the processInstance of a process instance is a self reference.
     */
    protected ExecutionImpl processInstance;

    /**
     * the parent execution
     */
    protected ExecutionImpl parent;

    /**
     * nested executions representing scopes or concurrent paths
     */
    protected List<ExecutionImpl> executions;

    /**
     * super execution, not-null if this execution is part of a subprocess
     */
    protected ExecutionImpl superExecution;

    /**
     * reference to a subprocessinstance, not-null if currently subprocess is started from this execution
     */
    protected ExecutionImpl subProcessInstance;

    /**
     * only available until the process instance is started
     */
    protected StartingExecution startingExecution;

    // state/type of execution //////////////////////////////////////////////////

    /**
     * indicates if this execution represents an active path of execution. Executions are made inactive in the following situations:
     * <ul>
     * <li>an execution enters a nested scope</li>
     * <li>an execution is split up into multiple concurrent executions, then the parent is made inactive.</li>
     * <li>an execution has arrived in a parallel gateway or join and that join has not yet activated/fired.</li>
     * <li>an execution is ended.</li>
     * </ul>
     */
    protected boolean isActive = true;
    protected boolean isScope = true;
    protected boolean isConcurrent;
    protected boolean isEnded;
    protected boolean isEventScope;

    protected Map<String, Object> variables;

    // events ///////////////////////////////////////////////////////////////////

    protected String eventName;
    protected PvmProcessElement eventSource;
    protected int executionListenerIndex;

    // cascade deletion ////////////////////////////////////////////////////////

    protected boolean deleteRoot;
    protected String deleteReason;

    // replaced by //////////////////////////////////////////////////////////////

    /**
     * when execution structure is pruned during a takeAll, then the original execution has to be resolved to the replaced execution.
     *
     * @see #takeAll(List, List) {@link OutgoingExecution}
     */
    protected ExecutionImpl replacedBy;

    // atomic operations ////////////////////////////////////////////////////////

    /**
     * next operation. process execution is in fact runtime interpretation of the process model. each operation is a logical unit of interpretation of the process. so sequentially processing the
     * operations drives the interpretation or execution of a process.
     *
     * @see AtomicOperation
     * @see #performOperation(AtomicOperation)
     */
    protected AtomicOperation nextOperation;
    protected boolean isOperating;

    /* Default constructor for ibatis/jpa/etc. */
    public ExecutionImpl() {
    }

    public ExecutionImpl(ActivityImpl initial) {
        startingExecution = new StartingExecution(initial);
    }

    // lifecycle methods ////////////////////////////////////////////////////////

    /**
     * creates a new execution. properties processDefinition, processInstance and activity will be initialized.
     */
    @Override
    public ExecutionImpl createExecution() {
        // create the new child execution
        ExecutionImpl createdExecution = newExecution();

        // manage the bidirectional parent-child relation
        ensureExecutionsInitialized();
        executions.add(createdExecution);
        createdExecution.setParent(this);

        // initialize the new execution
        createdExecution.setProcessDefinition(getProcessDefinition());
        createdExecution.setProcessInstance(getProcessInstance());
        createdExecution.setActivity(getActivity());

        return createdExecution;
    }

    /**
     * instantiates a new execution. can be overridden by subclasses
     */
    protected ExecutionImpl newExecution() {
        return new ExecutionImpl();
    }

    @Override
    public PvmProcessInstance createSubProcessInstance(PvmProcessDefinition processDefinition) {
        ExecutionImpl subProcessInstance = newExecution();

        // manage bidirectional super-subprocess relation
        subProcessInstance.setSuperExecution(this);
        this.setSubProcessInstance(subProcessInstance);

        // Initialize the new execution
        subProcessInstance.setProcessDefinition((ProcessDefinitionImpl) processDefinition);
        subProcessInstance.setProcessInstance(subProcessInstance);

        return subProcessInstance;
    }

    @Override
    public void initialize() {
    }

    @Override
    public void destroy() {
        setScope(false);
    }

    @Override
    public void remove() {
        ensureParentInitialized();
        if (parent != null) {
            parent.ensureExecutionsInitialized();
            parent.executions.remove(this);
        }

        // remove event scopes:
        List<InterpretableExecution> childExecutions = new ArrayList<>(getExecutions());
        for (InterpretableExecution childExecution : childExecutions) {
            if (childExecution.isEventScope()) {
                LOGGER.debug("removing eventScope {}", childExecution);
                childExecution.destroy();
                childExecution.remove();
            }
        }
    }

    @Override
    public void destroyScope(String reason) {

        LOGGER.debug("performing destroy scope behavior for execution {}", this);

        // remove all child executions and sub process instances:
        List<InterpretableExecution> executions = new ArrayList<>(getExecutions());
        for (InterpretableExecution childExecution : executions) {
            if (childExecution.getSubProcessInstance() != null) {
                childExecution.getSubProcessInstance().deleteCascade(reason);
            }
            childExecution.deleteCascade(reason);
        }

    }

    // parent ///////////////////////////////////////////////////////////////////

    /**
     * ensures initialization and returns the parent
     */
    @Override
    public ExecutionImpl getParent() {
        ensureParentInitialized();
        return parent;
    }

    @Override
    public String getSuperExecutionId() {
        ensureActivityInitialized();
        if (superExecution != null) {
            return superExecution.getId();
        }

        return null;
    }

    @Override
    public String getParentId() {
        ensureActivityInitialized();
        if (parent != null) {
            return parent.getId();
        }
        return null;
    }

    /**
     * all updates need to go through this setter as subclasses can override this method
     */
    @Override
    public void setParent(InterpretableExecution parent) {
        this.parent = (ExecutionImpl) parent;
    }

    /**
     * must be called before memberfield parent is used. can be used by subclasses to provide parent member field initialization.
     */
    protected void ensureParentInitialized() {
    }

    // executions ///////////////////////////////////////////////////////////////

    /**
     * ensures initialization and returns the non-null executions list
     */
    @Override
    public List<ExecutionImpl> getExecutions() {
        ensureExecutionsInitialized();
        return executions;
    }

    @Override
    public ExecutionImpl getSuperExecution() {
        ensureSuperExecutionInitialized();
        return superExecution;
    }

    public void setSuperExecution(ExecutionImpl superExecution) {
        this.superExecution = superExecution;
        if (superExecution != null) {
            superExecution.setSubProcessInstance(null);
        }
    }

    // Meant to be overridden by persistent subclasseses
    protected void ensureSuperExecutionInitialized() {
    }

    @Override
    public ExecutionImpl getSubProcessInstance() {
        ensureSubProcessInstanceInitialized();
        return subProcessInstance;
    }

    @Override
    public void setSubProcessInstance(InterpretableExecution subProcessInstance) {
        this.subProcessInstance = (ExecutionImpl) subProcessInstance;
    }

    // Meant to be overridden by persistent subclasses
    protected void ensureSubProcessInstanceInitialized() {
    }

    @Override
    public void deleteCascade(String deleteReason) {
        this.deleteReason = deleteReason;
        this.deleteRoot = true;
        performOperation(AtomicOperation.DELETE_CASCADE);
    }

    /**
     * removes an execution. if there are nested executions, those will be ended recursively. if there is a parent, this method removes the bidirectional relation between parent and this execution.
     */
    @Override
    public void end() {
        isActive = false;
        isEnded = true;
        performOperation(AtomicOperation.ACTIVITY_END);
    }

    /**
     * searches for an execution positioned in the given activity
     */
    @Override
    public ExecutionImpl findExecution(String activityId) {
        if ((getActivity() != null)
                && (getActivity().getId().equals(activityId))) {
            return this;
        }
        for (ExecutionImpl nestedExecution : getExecutions()) {
            ExecutionImpl result = nestedExecution.findExecution(activityId);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    @Override
    public List<String> findActiveActivityIds() {
        List<String> activeActivityIds = new ArrayList<>();
        collectActiveActivityIds(activeActivityIds);
        return activeActivityIds;
    }

    protected void collectActiveActivityIds(List<String> activeActivityIds) {
        ensureActivityInitialized();
        if (isActive && activity != null) {
            activeActivityIds.add(activity.getId());
        }
        ensureExecutionsInitialized();
        for (ExecutionImpl execution : executions) {
            execution.collectActiveActivityIds(activeActivityIds);
        }
    }

    /**
     * must be called before memberfield executions is used. can be used by subclasses to provide executions member field initialization.
     */
    protected void ensureExecutionsInitialized() {
        if (executions == null) {
            executions = new ArrayList<>();
        }
    }

    // process definition ///////////////////////////////////////////////////////

    /**
     * ensures initialization and returns the process definition.
     */
    @Override
    public ProcessDefinitionImpl getProcessDefinition() {
        ensureProcessDefinitionInitialized();
        return processDefinition;
    }

    @Override
    public String getProcessDefinitionId() {
        return getProcessDefinition().getId();
    }

    /* for setting the process definition, this setter must be used as subclasses can override */

    /*
     * must be called before memberfield processDefinition is used. can be used by subclasses to provide processDefinition member field initialization.
     */
    protected void ensureProcessDefinitionInitialized() {
    }

    // process instance /////////////////////////////////////////////////////////

    /**
     * ensures initialization and returns the process instance.
     */
    @Override
    public ExecutionImpl getProcessInstance() {
        ensureProcessInstanceInitialized();
        return processInstance;
    }

    @Override
    public String getProcessInstanceId() {
        return getProcessInstance().getId();
    }

    @Override
    public String getRootProcessInstanceId() {
        return getProcessInstance().getId();
    }

    public String getBusinessKey() {
        return getProcessInstance().getBusinessKey();
    }

    @Override
    public String getProcessInstanceBusinessKey() {
        return getProcessInstance().getBusinessKey();
    }
    
    @Override
    public String getProcessInstanceBusinessStatus() {
        return null;
    }

    /**
     * for setting the process instance, this setter must be used as subclasses can override
     */
    @Override
    public void setProcessInstance(InterpretableExecution processInstance) {
        this.processInstance = (ExecutionImpl) processInstance;
    }

    /**
     * must be called before memberfield processInstance is used. can be used by subclasses to provide processInstance member field initialization.
     */
    protected void ensureProcessInstanceInitialized() {
    }

    @Override
    public ReadOnlyDelegateExecution snapshotReadOnly() {
        return new ReadOnlyDelegateExecutionImpl(this);
    }

    // The current flow element, will be filled during operation execution

    @Override
    public FlowElement getCurrentFlowElement() {
        if (currentFlowElement == null) {
            String processDefinitionId = getProcessDefinitionId();
            if (processDefinitionId != null) {
                org.flowable.bpmn.model.Process process = ProcessDefinitionUtil.getProcess(processDefinitionId);
                currentFlowElement = process.getFlowElement(getCurrentActivityId(), true);
            }
        }
        return currentFlowElement;
    }

    @Override
    public void setCurrentFlowElement(FlowElement currentFlowElement) {
        this.currentFlowElement = currentFlowElement;
    }

    @Override
    public FlowableListener getCurrentFlowableListener() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setCurrentFlowableListener(FlowableListener currentListener) {
        throw new UnsupportedOperationException();
    }

    // activity /////////////////////////////////////////////////////////////////

    /**
     * ensures initialization and returns the activity
     */
    @Override
    public ActivityImpl getActivity() {
        ensureActivityInitialized();
        return activity;
    }

    /**
     * sets the current activity. can be overridden by subclasses. doesn't require initialization.
     */
    @Override
    public void setActivity(ActivityImpl activity) {
        this.activity = activity;
    }

    /**
     * must be called before the activity member field or getActivity() is called
     */
    protected void ensureActivityInitialized() {
    }

    // scopes ///////////////////////////////////////////////////////////////////

    protected void ensureScopeInitialized() {
    }

    @Override
    public boolean isScope() {
        return isScope;
    }

    @Override
    public void setScope(boolean isScope) {
        this.isScope = isScope;
    }

    // process instance start implementation ////////////////////////////////////

    @Override
    public void start() {
        if (startingExecution == null && isProcessInstanceType()) {
            startingExecution = new StartingExecution(processDefinition.getInitial());
        }
        performOperation(AtomicOperation.PROCESS_START);
    }

    // methods that translate to operations /////////////////////////////////////

    @Override
    public void signal(String signalName, Object signalData) {
        ensureActivityInitialized();
        SignallableActivityBehavior activityBehavior = (SignallableActivityBehavior) activity.getActivityBehavior();
        try {
            activityBehavior.signal(this, signalName, signalData);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new PvmException("couldn't process signal '" + signalName + "' on activity '" + activity.getId() + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void take(PvmTransition transition, boolean fireActivityCompletedEvent) {
        // No event firing on executionlevel impl
        take(transition);
    }

    @Override
    public void take(PvmTransition transition) {
        if (this.transition != null) {
            throw new PvmException("already taking a transition");
        }
        if (transition == null) {
            throw new PvmException("transition is null");
        }
        setTransition((TransitionImpl) transition);
        performOperation(AtomicOperation.TRANSITION_NOTIFY_LISTENER_END);
    }

    @Override
    public void executeActivity(PvmActivity activity) {
        setActivity((ActivityImpl) activity);
        performOperation(AtomicOperation.ACTIVITY_START);
    }

    @Override
    public List<ActivityExecution> findInactiveConcurrentExecutions(PvmActivity activity) {
        List<ActivityExecution> inactiveConcurrentExecutionsInActivity = new ArrayList<>();
        List<ActivityExecution> otherConcurrentExecutions = new ArrayList<>();
        if (isConcurrent()) {
            List<? extends ActivityExecution> concurrentExecutions = getParent().getExecutions();
            for (ActivityExecution concurrentExecution : concurrentExecutions) {
                if (concurrentExecution.getActivity() != null && concurrentExecution.getActivity().getId().equals(activity.getId())) {
                    if (concurrentExecution.isActive()) {
                        throw new PvmException("didn't expect active execution in " + activity + ". bug?");
                    }
                    inactiveConcurrentExecutionsInActivity.add(concurrentExecution);
                } else {
                    otherConcurrentExecutions.add(concurrentExecution);
                }
            }
        } else {
            if (!isActive()) {
                inactiveConcurrentExecutionsInActivity.add(this);
            } else {
                otherConcurrentExecutions.add(this);
            }
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("inactive concurrent executions in '{}': {}", activity, inactiveConcurrentExecutionsInActivity);
            LOGGER.debug("other concurrent executions: {}", otherConcurrentExecutions);
        }
        return inactiveConcurrentExecutionsInActivity;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void takeAll(List<PvmTransition> transitions, List<ActivityExecution> recyclableExecutions) {
        transitions = new ArrayList<>(transitions);
        recyclableExecutions = (recyclableExecutions != null ? new ArrayList<>(recyclableExecutions) : new ArrayList<>());

        if (recyclableExecutions.size() > 1) {
            for (ActivityExecution recyclableExecution : recyclableExecutions) {
                if (((ExecutionImpl) recyclableExecution).isScope()) {
                    throw new PvmException("joining scope executions is not allowed");
                }
            }
        }

        ExecutionImpl concurrentRoot = ((isConcurrent && !isScope) ? getParent() : this);
        List<ExecutionImpl> concurrentActiveExecutions = new ArrayList<>();
        for (ExecutionImpl execution : concurrentRoot.getExecutions()) {
            if (execution.isActive()) {
                concurrentActiveExecutions.add(execution);
            }
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("transitions to take concurrent: {}", transitions);
            LOGGER.debug("active concurrent executions: {}", concurrentActiveExecutions);
        }

        if ((transitions.size() == 1)
                && (concurrentActiveExecutions.isEmpty())) {

            @SuppressWarnings("rawtypes")
            List<ExecutionImpl> recyclableExecutionImpls = (List) recyclableExecutions;
            for (ExecutionImpl prunedExecution : recyclableExecutionImpls) {
                // End the pruned executions if necessary.
                // Some recyclable executions are inactivated (joined executions)
                // Others are already ended (end activities)
                if (!prunedExecution.isEnded()) {
                    LOGGER.debug("pruning execution {}", prunedExecution);
                    prunedExecution.remove();
                }
            }

            LOGGER.debug("activating the concurrent root {} as the single path of execution going forward", concurrentRoot);
            concurrentRoot.setActive(true);
            concurrentRoot.setActivity(activity);
            concurrentRoot.setConcurrent(false);
            concurrentRoot.take(transitions.get(0));

        } else {

            List<OutgoingExecution> outgoingExecutions = new ArrayList<>();

            recyclableExecutions.remove(concurrentRoot);

            LOGGER.debug("recyclable executions for reused: {}", recyclableExecutions);

            // first create the concurrent executions
            while (!transitions.isEmpty()) {
                PvmTransition outgoingTransition = transitions.remove(0);

                ExecutionImpl outgoingExecution = null;
                if (recyclableExecutions.isEmpty()) {
                    outgoingExecution = concurrentRoot.createExecution();
                    LOGGER.debug("new {} created to take transition {}", outgoingExecution, outgoingTransition);
                } else {
                    outgoingExecution = (ExecutionImpl) recyclableExecutions.remove(0);
                    LOGGER.debug("recycled {} to take transition {}", outgoingExecution, outgoingTransition);
                }

                outgoingExecution.setActive(true);
                outgoingExecution.setScope(false);
                outgoingExecution.setConcurrent(true);
                outgoingExecutions.add(new OutgoingExecution(outgoingExecution, outgoingTransition, true));
            }

            // prune the executions that are not recycled
            for (ActivityExecution prunedExecution : recyclableExecutions) {
                LOGGER.debug("pruning execution {}", prunedExecution);
                prunedExecution.end();
            }

            // then launch all the concurrent executions
            for (OutgoingExecution outgoingExecution : outgoingExecutions) {
                outgoingExecution.take();
            }
        }
    }

    @Override
    public void performOperation(AtomicOperation executionOperation) {
        this.nextOperation = executionOperation;
        if (!isOperating) {
            isOperating = true;
            while (nextOperation != null) {
                AtomicOperation currentOperation = this.nextOperation;
                this.nextOperation = null;
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("AtomicOperation: {} on {}", currentOperation, this);
                }
                currentOperation.execute(this);
            }
            isOperating = false;
        }
    }

    public boolean isActive(String activityId) {
        return findExecution(activityId) != null;
    }

    // variables ////////////////////////////////////////////////////////////////

    @Override
    public Object getVariable(String variableName) {
        ensureVariablesInitialized();

        // If value is found in this scope, return it
        if (variables.containsKey(variableName)) {
            return variables.get(variableName);
        }

        // If value not found in this scope, check the parent scope
        ensureParentInitialized();
        if (parent != null) {
            return parent.getVariable(variableName);
        }

        // Variable is nowhere to be found
        return null;
    }

    @Override
    public Object getVariable(String variableName, boolean fetchAllVariables) {
        return getVariable(variableName); // No support for fetchAllVariables on ExecutionImpl
    }

    @Override
    public Map<String, Object> getVariables() {
        Map<String, Object> collectedVariables = new HashMap<>();
        collectVariables(collectedVariables);
        return collectedVariables;
    }

    @Override
    public Map<String, Object> getVariables(Collection<String> variableNames) {
        Map<String, Object> allVariables = getVariables();
        Map<String, Object> filteredVariables = new HashMap<>();
        for (String variableName : variableNames) {
            filteredVariables.put(variableName, allVariables.get(variableName));
        }
        return filteredVariables;
    }

    @Override
    public Map<String, Object> getVariables(Collection<String> variableNames, boolean fetchAllVariables) {
        return getVariables(variableNames); // No support for the boolean param
    }

    protected void collectVariables(Map<String, Object> collectedVariables) {
        ensureParentInitialized();
        if (parent != null) {
            parent.collectVariables(collectedVariables);
        }
        ensureVariablesInitialized();
        for (String variableName : variables.keySet()) {
            collectedVariables.put(variableName, variables.get(variableName));
        }
    }

    @Override
    public void setVariables(Map<String, ? extends Object> variables) {
        ensureVariablesInitialized();
        if (variables != null) {
            for (String variableName : variables.keySet()) {
                setVariable(variableName, variables.get(variableName));
            }
        }
    }

    @Override
    public void setVariable(String variableName, Object value) {
        ensureVariablesInitialized();
        if (variables.containsKey(variableName)) {
            setVariableLocally(variableName, value);
        } else {
            ensureParentInitialized();
            if (parent != null) {
                parent.setVariable(variableName, value);
            } else {
                setVariableLocally(variableName, value);
            }
        }
    }

    @Override
    public void setVariable(String variableName, Object value, boolean fetchAllVariables) {
        setVariable(variableName, value);
    }

    public void setVariableLocally(String variableName, Object value) {
        LOGGER.debug("setting variable '{}' to value '{}' on {}", variableName, value, this);
        variables.put(variableName, value);
    }

    @Override
    public Object setVariableLocal(String variableName, Object value, boolean fetchAllVariables) {
        return setVariableLocal(variableName, value);
    }

    @Override
    public boolean hasVariable(String variableName) {
        ensureVariablesInitialized();
        if (variables.containsKey(variableName)) {
            return true;
        }
        ensureParentInitialized();
        if (parent != null) {
            return parent.hasVariable(variableName);
        }
        return false;
    }

    protected void ensureVariablesInitialized() {
        if (variables == null) {
            variables = new HashMap<>();
        }
    }

    // toString /////////////////////////////////////////////////////////////////

    @Override
    public String toString() {
        if (isProcessInstanceType()) {
            return "ProcessInstance[" + getToStringIdentity() + "]";
        } else {
            return (isEventScope ? "EventScope" : "") + (isConcurrent ? "Concurrent" : "") + (isScope() ? "Scope" : "") + "Execution[" + getToStringIdentity() + "]";
        }
    }

    protected String getToStringIdentity() {
        return Integer.toString(System.identityHashCode(this));
    }

    // customized getters and setters ///////////////////////////////////////////

    @Override
    public boolean isProcessInstanceType() {
        ensureParentInitialized();
        return parent == null;
    }

    @Override
    public void inactivate() {
        this.isActive = false;
    }

    // allow for subclasses to expose a real id /////////////////////////////////

    @Override
    public String getId() {
        return null;
    }

    // getters and setters //////////////////////////////////////////////////////

    @Override
    public TransitionImpl getTransition() {
        return transition;
    }

    @Override
    public void setTransition(TransitionImpl transition) {
        this.transition = transition;
    }

    @Override
    public Integer getExecutionListenerIndex() {
        return executionListenerIndex;
    }

    @Override
    public void setExecutionListenerIndex(Integer executionListenerIndex) {
        this.executionListenerIndex = executionListenerIndex;
    }

    @Override
    public boolean isConcurrent() {
        return isConcurrent;
    }

    @Override
    public void setConcurrent(boolean isConcurrent) {
        this.isConcurrent = isConcurrent;
    }

    @Override
    public boolean isActive() {
        return isActive;
    }

    @Override
    public void setActive(boolean isActive) {
        this.isActive = isActive;
    }

    @Override
    public boolean isEnded() {
        return isEnded;
    }

    @Override
    public void setEnded(boolean ended) {
        this.isEnded = ended;
    }

    @Override
    public void setProcessDefinition(ProcessDefinitionImpl processDefinition) {
        this.processDefinition = processDefinition;
    }

    @Override
    public String getEventName() {
        return eventName;
    }

    @Override
    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    @Override
    public PvmProcessElement getEventSource() {
        return eventSource;
    }

    @Override
    public void setEventSource(PvmProcessElement eventSource) {
        this.eventSource = eventSource;
    }

    @Override
    public String getDeleteReason() {
        return deleteReason;
    }

    public void setDeleteReason(String deleteReason) {
        this.deleteReason = deleteReason;
    }

    @Override
    public ExecutionImpl getReplacedBy() {
        return replacedBy;
    }

    @Override
    public void setReplacedBy(InterpretableExecution replacedBy) {
        this.replacedBy = (ExecutionImpl) replacedBy;
    }

    public void setExecutions(List<ExecutionImpl> executions) {
        this.executions = executions;
    }

    @Override
    public boolean isDeleteRoot() {
        return deleteRoot;
    }

    @Override
    public String getCurrentActivityId() {
        String currentActivityId = null;
        if (this.activity != null) {
            currentActivityId = activity.getId();
        }
        return currentActivityId;
    }

    public String getCurrentActivityName() {
        String currentActivityName = null;
        if (this.activity != null) {
            currentActivityName = (String) activity.getProperty("name");
        }
        return currentActivityName;
    }

    @Override
    public Map<String, VariableInstance> getVariableInstances() {
        return null;
    }

    @Override
    public Map<String, VariableInstance> getVariableInstances(Collection<String> variableNames) {
        return null;
    }

    @Override
    public Map<String, VariableInstance> getVariableInstances(Collection<String> variableNames, boolean fetchAllVariables) {
        return null;
    }

    @Override
    public Map<String, VariableInstance> getVariableInstancesLocal() {
        return null;
    }

    @Override
    public Map<String, VariableInstance> getVariableInstancesLocal(Collection<String> variableNames) {
        return null;
    }

    @Override
    public Map<String, VariableInstance> getVariableInstancesLocal(Collection<String> variableNames, boolean fetchAllVariables) {
        return null;
    }

    @Override
    public VariableInstance getVariableInstance(String variableName) {
        return null;
    }

    @Override
    public VariableInstance getVariableInstance(String variableName, boolean fetchAllVariables) {
        return null;
    }

    public void createVariableLocal(String variableName, Object value) {
    }

    public void createVariablesLocal(Map<String, ? extends Object> variables) {
    }

    @Override
    public Object getVariableLocal(String variableName) {
        return null;
    }

    @Override
    public VariableInstanceEntity getVariableInstanceLocal(String variableName) {
        return null;
    }

    @Override
    public Object getVariableLocal(String variableName, boolean fetchAllVariables) {
        return getVariableLocal(variableName); // No support for fetchAllVariables
    }

    @Override
    public VariableInstanceEntity getVariableInstanceLocal(String variableName, boolean fetchAllVariables) {
        return null;
    }

    @Override
    public <T> T getVariable(String variableName, Class<T> variableClass) {
        return variableClass.cast(getVariable(variableName));
    }

    @Override
    public <T> T getVariableLocal(String variableName, Class<T> variableClass) {
        return variableClass.cast(getVariableLocal(variableName));
    }

    @Override
    public Set<String> getVariableNames() {
        return null;
    }

    @Override
    public Set<String> getVariableNamesLocal() {
        return null;
    }

    @Override
    public Map<String, Object> getVariablesLocal() {
        return null;
    }

    @Override
    public Map<String, Object> getVariablesLocal(Collection<String> variableNames) {
        return null;
    }

    @Override
    public Map<String, Object> getVariablesLocal(Collection<String> variableNames, boolean fetchAllVariables) {
        return null;
    }

    @Override
    public boolean hasVariableLocal(String variableName) {
        return false;
    }

    @Override
    public boolean hasVariables() {
        return false;
    }

    @Override
    public boolean hasVariablesLocal() {
        return false;
    }

    @Override
    public void removeVariable(String variableName) {
    }

    @Override
    public void removeVariableLocal(String variableName) {
    }

    @Override
    public void removeVariables(Collection<String> variableNames) {
    }

    @Override
    public void removeVariablesLocal(Collection<String> variableNames) {
    }

    @Override
    public void removeVariables() {
    }

    @Override
    public void removeVariablesLocal() {
    }

    public void deleteVariablesLocal() {
    }

    @Override
    public Object setVariableLocal(String variableName, Object value) {
        return null;
    }

    @Override
    public void setVariablesLocal(Map<String, ? extends Object> variables) {
    }

    @Override
    public boolean isEventScope() {
        return isEventScope;
    }

    @Override
    public void setEventScope(boolean isEventScope) {
        this.isEventScope = isEventScope;
    }

    @Override
    public StartingExecution getStartingExecution() {
        return startingExecution;
    }

    @Override
    public void disposeStartingExecution() {
        startingExecution = null;
    }

    public String updateProcessBusinessKey(String bzKey) {
        return getProcessInstance().updateProcessBusinessKey(bzKey);
    }

    @Override
    public String getTenantId() {
        return null; // Not implemented
    }

    // NOT IN V5
    @Override
    public boolean isMultiInstanceRoot() {
        return false;
    }

    @Override
    public void setMultiInstanceRoot(boolean isMultiInstanceRoot) {

    }

    @Override
    public String getPropagatedStageInstanceId() {
        return null;
    }

    // No support for transient variables in v5

    @Override
    public void setTransientVariablesLocal(Map<String, Object> transientVariables) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTransientVariableLocal(String variableName, Object variableValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTransientVariables(Map<String, Object> transientVariables) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTransientVariable(String variableName, Object variableValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getTransientVariableLocal(String variableName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> getTransientVariablesLocal() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getTransientVariable(String variableName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> getTransientVariables() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeTransientVariableLocal(String variableName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeTransientVariablesLocal() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeTransientVariable(String variableName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeTransientVariables() {
        throw new UnsupportedOperationException();
    }

}
