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
package org.flowable.variable.service.impl.persistence.entity.data;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.flowable.common.engine.impl.persistence.entity.data.DataManager;
import org.flowable.variable.api.persistence.entity.VariableInstance;
import org.flowable.variable.service.impl.InternalVariableInstanceQueryImpl;
import org.flowable.variable.service.impl.VariableInstanceQueryImpl;
import org.flowable.variable.service.impl.persistence.entity.VariableInstanceEntity;

/**
 * @author Joram Barrez
 */
public interface VariableInstanceDataManager extends DataManager<VariableInstanceEntity> {

    List<VariableInstanceEntity> findVariablesInstancesByQuery(InternalVariableInstanceQueryImpl internalVariableInstanceQuery);

    VariableInstanceEntity findVariablesInstanceByQuery(InternalVariableInstanceQueryImpl internalVariableInstanceQuery);
    
    long findVariableInstanceCountByQueryCriteria(VariableInstanceQueryImpl variableInstanceQuery);

    List<VariableInstance> findVariableInstancesByQueryCriteria(VariableInstanceQueryImpl variableInstanceQuery);

    List<VariableInstance> findVariableInstancesByNativeQuery(Map<String, Object> parameterMap);

    long findVariableInstanceCountByNativeQuery(Map<String, Object> parameterMap);
    
    void deleteVariablesByTaskId(String taskId);
    
    void deleteVariablesByExecutionId(String executionId);
    
    void deleteByScopeIdAndScopeType(String scopeId, String scopeType);

    void deleteByScopeIdAndScopeTypes(String scopeId, Collection<String> scopeTypes);

    void deleteBySubScopeIdAndScopeTypes(String subScopeId, Collection<String> scopeTypes);

}
