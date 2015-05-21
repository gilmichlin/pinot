/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.controller.helix.core;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.helix.HelixAdmin;
import org.apache.helix.ZNRecord;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.builder.CustomModeISBuilder;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.JsonMappingException;
import org.json.JSONException;

import com.linkedin.pinot.common.config.AbstractTableConfig;
import com.linkedin.pinot.common.config.RealtimeTableConfig;
import com.linkedin.pinot.common.config.TableNameBuilder;
import com.linkedin.pinot.common.metadata.ZKMetadataProvider;
import com.linkedin.pinot.common.metadata.instance.InstanceZKMetadata;
import com.linkedin.pinot.common.metadata.resource.OfflineDataResourceZKMetadata;
import com.linkedin.pinot.common.metadata.resource.RealtimeDataResourceZKMetadata;
import com.linkedin.pinot.common.metadata.stream.KafkaStreamMetadata;
import com.linkedin.pinot.common.segment.SegmentMetadata;
import com.linkedin.pinot.common.utils.BrokerRequestUtils;
import com.linkedin.pinot.common.utils.CommonConstants;
import com.linkedin.pinot.common.utils.CommonConstants.Helix;
import com.linkedin.pinot.common.utils.ControllerTenantNameBuilder;
import com.linkedin.pinot.common.utils.StringUtil;
import com.linkedin.pinot.controller.api.pojos.BrokerDataResource;
import com.linkedin.pinot.controller.helix.core.sharding.BrokerResourceAssignmentStrategy;
import com.linkedin.pinot.controller.helix.core.sharding.SegmentAssignmentStrategy;
import com.linkedin.pinot.controller.helix.core.sharding.SegmentAssignmentStrategyFactory;


/**
 * Pinot data server layer IdealState builder.
 *
 * @author xiafu
 *
 */
public class PinotResourceIdealStateBuilder {
  public static final String ONLINE = "ONLINE";
  public static final String OFFLINE = "OFFLINE";
  public static final String DROPPED = "DROPPED";

  public static final Map<String, SegmentAssignmentStrategy> SEGMENT_ASSIGNMENT_STRATEGY_MAP =
      new HashMap<String, SegmentAssignmentStrategy>();

  /**
   *
   * Building an empty idealState for a given resource.
   * Used when creating a new resource.
   *
   * @param resource
   * @param helixAdmin
   * @param helixClusterName
   * @return
   */
  public static IdealState buildEmptyIdealStateFor(String resourceName, int numCopies, HelixAdmin helixAdmin,
      String helixClusterName) {
    final CustomModeISBuilder customModeIdealStateBuilder = new CustomModeISBuilder(resourceName);
    final int replicas = numCopies;
    customModeIdealStateBuilder
        .setStateModel(PinotHelixSegmentOnlineOfflineStateModelGenerator.PINOT_SEGMENT_ONLINE_OFFLINE_STATE_MODEL)
        .setNumPartitions(0).setNumReplica(replicas).setMaxPartitionsPerNode(1);
    final IdealState idealState = customModeIdealStateBuilder.build();
    idealState.setInstanceGroupTag(resourceName);
    return idealState;
  }

  /**
   *
   * Building an empty idealState for a given resource.
   * Used when creating a new resource.
   *
   * @param resource
   * @param helixAdmin
   * @param helixClusterName
   * @return
   */
  public static IdealState buildEmptyIdealStateForBrokerResource(HelixAdmin helixAdmin, String helixClusterName) {
    final CustomModeISBuilder customModeIdealStateBuilder =
        new CustomModeISBuilder(CommonConstants.Helix.BROKER_RESOURCE_INSTANCE);
    customModeIdealStateBuilder
        .setStateModel(
            PinotHelixBrokerResourceOnlineOfflineStateModelGenerator.PINOT_BROKER_RESOURCE_ONLINE_OFFLINE_STATE_MODEL)
        .setMaxPartitionsPerNode(Integer.MAX_VALUE).setNumReplica(Integer.MAX_VALUE)
        .setNumPartitions(Integer.MAX_VALUE);
    final IdealState idealState = customModeIdealStateBuilder.build();
    return idealState;
  }

  public static IdealState addNewRealtimeSegmentToIdealState(String segmentId, IdealState state, String instanceName) {
    state.setPartitionState(segmentId, instanceName, ONLINE);
    state.setNumPartitions(state.getNumPartitions() + 1);
    return state;
  }

  /**
   * For adding a new segment, we have to recompute the ideal states.
   *
   * @param segmentMetadata
   * @param helixAdmin
   * @param helixClusterName
   * @param serverTenant 
   * @param zkClient
   * @return
   */
  public static IdealState addNewOfflineSegmentToIdealStateFor(SegmentMetadata segmentMetadata, HelixAdmin helixAdmin,
      String helixClusterName, ZkHelixPropertyStore<ZNRecord> propertyStore, String serverTenant) {

    final String resourceName = segmentMetadata.getResourceName();
    final String offlineResourceName =
        BrokerRequestUtils.getOfflineResourceNameForResource(segmentMetadata.getResourceName());
    final String segmentName = segmentMetadata.getName();
    OfflineDataResourceZKMetadata offlineDataResourceZKMetadata =
        ZKMetadataProvider.getOfflineResourceZKMetadata(propertyStore, resourceName);
    if (!SEGMENT_ASSIGNMENT_STRATEGY_MAP.containsKey(resourceName)) {
      SEGMENT_ASSIGNMENT_STRATEGY_MAP.put(resourceName, SegmentAssignmentStrategyFactory
          .getSegmentAssignmentStrategy(offlineDataResourceZKMetadata.getSegmentAssignmentStrategy()));
    }
    final SegmentAssignmentStrategy segmentAssignmentStrategy = SEGMENT_ASSIGNMENT_STRATEGY_MAP.get(resourceName);

    final IdealState currentIdealState = helixAdmin.getResourceIdealState(helixClusterName, offlineResourceName);
    final Set<String> currentInstanceSet = currentIdealState.getInstanceSet(segmentName);
    if (currentInstanceSet.isEmpty()) {
      // Adding new Segments
      final int replicas = offlineDataResourceZKMetadata.getNumDataReplicas();
      final List<String> selectedInstances =
          segmentAssignmentStrategy.getAssignedInstances(helixAdmin, helixClusterName, segmentMetadata, replicas, serverTenant);
      for (final String instance : selectedInstances) {
        currentIdealState.setPartitionState(segmentName, instance, ONLINE);
      }
      currentIdealState.setNumPartitions(currentIdealState.getNumPartitions() + 1);
    } else {
      // Update new Segments
      for (final String instance : currentInstanceSet) {
        currentIdealState.setPartitionState(segmentName, instance, OFFLINE);
        currentIdealState.setPartitionState(segmentName, instance, ONLINE);
      }
    }
    return currentIdealState;
  }

  /**
   * Remove a segment is also required to recompute the ideal state.
   *
   * @param resourceName
   * @param segmentId
   * @param helixAdmin
   * @param helixClusterName
   * @return
   */
  public synchronized static IdealState dropSegmentFromIdealStateFor(String resourceName, String segmentId,
      HelixAdmin helixAdmin, String helixClusterName) {

    final IdealState currentIdealState = helixAdmin.getResourceIdealState(helixClusterName, resourceName);
    final Set<String> currentInstanceSet = currentIdealState.getInstanceSet(segmentId);
    if (!currentInstanceSet.isEmpty() && currentIdealState.getPartitionSet().contains(segmentId)) {
      for (String instanceName : currentIdealState.getInstanceSet(segmentId)) {
        currentIdealState.setPartitionState(segmentId, instanceName, "DROPPED");
      }
    } else {
      throw new RuntimeException("Cannot found segmentId - " + segmentId + " in resource - " + resourceName);
    }
    return currentIdealState;
  }

  /**
   * Remove a segment is also required to recompute the ideal state.
   *
   * @param resourceName
   * @param segmentId
   * @param helixAdmin
   * @param helixClusterName
   * @return
   */
  public synchronized static IdealState removeSegmentFromIdealStateFor(String resourceName, String segmentId,
      HelixAdmin helixAdmin, String helixClusterName) {

    final IdealState currentIdealState = helixAdmin.getResourceIdealState(helixClusterName, resourceName);
    if (currentIdealState != null && currentIdealState.getPartitionSet() != null
        && currentIdealState.getPartitionSet().contains(segmentId)) {
      currentIdealState.getPartitionSet().remove(segmentId);
    } else {
      throw new RuntimeException("Cannot found segmentId - " + segmentId + " in resource - " + resourceName);
    }
    return currentIdealState;
  }

  /**
   *
   * @param brokerResourceName
   * @param helixAdmin
   * @param helixClusterName
   * @return
   */
  public static IdealState removeBrokerResourceFromIdealStateFor(String brokerResourceName, HelixAdmin helixAdmin,
      String helixClusterName) {
    final IdealState currentIdealState =
        helixAdmin.getResourceIdealState(helixClusterName, CommonConstants.Helix.BROKER_RESOURCE_INSTANCE);
    final Set<String> currentInstanceSet = currentIdealState.getInstanceSet(brokerResourceName);
    if (!currentInstanceSet.isEmpty() && currentIdealState.getPartitionSet().contains(brokerResourceName)) {
      currentIdealState.getPartitionSet().remove(brokerResourceName);
    } else {
      throw new RuntimeException("Cannot found broker resource - " + brokerResourceName + " in broker resource ");
    }
    return currentIdealState;
  }

  /**
   * @param brokerResource
   * @param helixAdmin
   * @param helixClusterName
   * @return
   * @throws Exception
   */
  public static IdealState addBrokerResourceToIdealStateFor(BrokerDataResource brokerResource, HelixAdmin helixAdmin,
      String helixClusterName) throws Exception {
    final String resourceName = brokerResource.getResourceName();
    try {

      final IdealState currentIdealState =
          helixAdmin.getResourceIdealState(helixClusterName, CommonConstants.Helix.BROKER_RESOURCE_INSTANCE);
      final Set<String> currentInstanceSet = currentIdealState.getInstanceSet(resourceName);

      if (currentInstanceSet.isEmpty()) {
        // Adding new broker resources
        final List<String> selectedInstances =
            BrokerResourceAssignmentStrategy.getRandomAssignedInstances(helixAdmin, helixClusterName, brokerResource);
        for (final String instance : selectedInstances) {
          currentIdealState.setPartitionState(resourceName, instance, ONLINE);
        }
      } else {
        return updateBrokerResourceToIdealStateFor(currentIdealState, brokerResource, helixAdmin, helixClusterName);
      }
      return currentIdealState;
    } catch (final Exception ex) {
      throw ex;
    }
  }

  /**
   * @param currentIdealState
   * @param brokerDataResource
   * @param helixAdmin
   * @param helixClusterName
   * @return
   */
  private static IdealState updateBrokerResourceToIdealStateFor(IdealState currentIdealState,
      BrokerDataResource brokerDataResource, HelixAdmin helixAdmin, String helixClusterName) {

    final String resourceName = brokerDataResource.getResourceName();
    final int numBrokerInstancesToServeDataResource = brokerDataResource.getNumBrokerInstances();
    final Set<String> currentOnlineInstanceSet = new HashSet<String>();

    final Map<String, String> currentInstanceStateMap = currentIdealState.getInstanceStateMap(resourceName);

    // Update online/offline instances staus.
    for (final String instance : currentInstanceStateMap.keySet()) {
      if (currentInstanceStateMap.get(instance).equalsIgnoreCase(ONLINE)) {
        currentOnlineInstanceSet.add(instance);
      }
    }

    // Update broker resources
    if (currentOnlineInstanceSet.size() > numBrokerInstancesToServeDataResource) {
      // remove instances
      int numInstancesToRemove = currentOnlineInstanceSet.size() - numBrokerInstancesToServeDataResource;
      final Set<String> removedInstanceNames = new HashSet<String>();

      currentIdealState.getPartitionSet().remove(resourceName);
      for (final String instance : currentOnlineInstanceSet) {
        if (numInstancesToRemove > 0) {
          removedInstanceNames.add(instance);
          helixAdmin.enablePartition(false, helixClusterName, instance, CommonConstants.Helix.BROKER_RESOURCE_INSTANCE,
              Arrays.asList(resourceName));
          numInstancesToRemove--;
        } else {
          currentIdealState.setPartitionState(resourceName, instance, ONLINE);
        }
      }

      helixAdmin.setResourceIdealState(helixClusterName, CommonConstants.Helix.BROKER_RESOURCE_INSTANCE,
          currentIdealState);
      for (final String instance : removedInstanceNames) {
        helixAdmin.enablePartition(true, helixClusterName, instance, CommonConstants.Helix.BROKER_RESOURCE_INSTANCE,
            Arrays.asList(resourceName));
      }
      return null;
    }

    if (currentOnlineInstanceSet.size() < numBrokerInstancesToServeDataResource) {
      // Adding new instances
      int numInstancesToAdd = numBrokerInstancesToServeDataResource - currentOnlineInstanceSet.size();

      // Adding new instances.
      final List<String> selectedInstances =
          BrokerResourceAssignmentStrategy.getRandomAssignedInstances(helixAdmin, helixClusterName, brokerDataResource);

      for (final String instance : selectedInstances) {
        if (currentOnlineInstanceSet.contains(instance)) {
          continue;
        }
        currentIdealState.setPartitionState(resourceName, instance, ONLINE);
        numInstancesToAdd--;
        if (numInstancesToAdd == 0) {
          break;
        }
      }
    }
    return currentIdealState;
  }

  public static IdealState updateExpandedDataResourceIdealStateFor(String resourceName, int numCopies,
      HelixAdmin helixAdmin, String helixClusterName) {
    IdealState idealState = helixAdmin.getResourceIdealState(helixClusterName, resourceName);
    // Increase number of replicas
    if (Integer.parseInt(idealState.getReplicas()) < numCopies) {
      Random randomSeed = new Random(System.currentTimeMillis());
      int currentReplicas = Integer.parseInt(idealState.getReplicas());
      idealState.setReplicas(numCopies + "");
      Set<String> segmentSet = idealState.getPartitionSet();
      List<String> instanceList = helixAdmin.getInstancesInClusterWithTag(helixClusterName, resourceName);
      for (String segmentName : segmentSet) {
        // TODO(xiafu) : current just random assign one more replica.
        // In future, has to implement read segmentMeta from PropertyStore then use segmentAssignmentStrategy to assign.
        Set<String> selectedInstanceSet = idealState.getInstanceSet(segmentName);
        int numInstancesToAssign = numCopies - currentReplicas;
        int numInstancesAvailable = instanceList.size() - selectedInstanceSet.size();
        for (String instance : instanceList) {
          if (selectedInstanceSet.contains(instance)) {
            continue;
          }
          if (randomSeed.nextInt(numInstancesAvailable) < numInstancesToAssign) {
            idealState.setPartitionState(segmentName, instance,
                PinotHelixSegmentOnlineOfflineStateModelGenerator.ONLINE_STATE);
            numInstancesToAssign--;
          }
          if (numInstancesToAssign == 0) {
            break;
          }
          numInstancesAvailable--;
        }
      }

      return idealState;
    }
    // Decrease number of replicas
    if (Integer.parseInt(idealState.getReplicas()) > numCopies) {
      int replicas = numCopies;
      int currentReplicas = Integer.parseInt(idealState.getReplicas());
      idealState.setReplicas(replicas + "");
      Set<String> segmentSet = idealState.getPartitionSet();

      for (String segmentName : segmentSet) {
        Set<String> instanceSet = idealState.getInstanceSet(segmentName);
        int cnt = 1;
        for (final String instance : instanceSet) {
          idealState.setPartitionState(segmentName, instance,
              PinotHelixSegmentOnlineOfflineStateModelGenerator.DROPPED_STATE);
          if (cnt++ > (currentReplicas - replicas)) {
            break;
          }
        }
      }

      return idealState;
    }
    return idealState;
  }

  /**
   * For adding a new segment, we have to recompute the ideal states.
   *
   * @param segmentMetadata
   * @param helixAdmin
   * @param helixClusterName
   * @return
   */
  public static IdealState updateExistedSegmentToIdealStateFor(SegmentMetadata segmentMetadata, HelixAdmin helixAdmin,
      String helixClusterName) {

    final String resourceName = segmentMetadata.getResourceName();
    final String segmentName = segmentMetadata.getName();

    final IdealState currentIdealState = helixAdmin.getResourceIdealState(helixClusterName, resourceName);
    final Set<String> currentInstanceSet = currentIdealState.getInstanceSet(segmentName);
    for (final String instance : currentInstanceSet) {
      currentIdealState.setPartitionState(segmentName, instance, OFFLINE);
    }
    helixAdmin.setResourceIdealState(helixClusterName, resourceName, currentIdealState);
    for (final String instance : currentInstanceSet) {
      currentIdealState.setPartitionState(segmentName, instance, ONLINE);
    }
    return currentIdealState;
  }

  public static IdealState buildInitialRealtimeIdealStateFor(String realtimeResourceName,
      RealtimeDataResourceZKMetadata realtimeDataResource, HelixAdmin helixAdmin, String helixClusterName,
      ZkHelixPropertyStore<ZNRecord> zkHelixPropertyStore) {
    switch (realtimeDataResource.getStreamType()) {
      case kafka:
        KafkaStreamMetadata kafkaStreamMetadata = (KafkaStreamMetadata) realtimeDataResource.getStreamMetadata();
        switch (kafkaStreamMetadata.getConsumerType()) {
          case highLevel:
            return buildInitialKafkaHighLevelConsumerRealtimeIdealStateFor(realtimeResourceName, realtimeDataResource,
                helixAdmin, helixClusterName, zkHelixPropertyStore);
          case simple:
            return buildInitialKafkaSimpleConsumerRealtimeIdealStateFor(realtimeResourceName, realtimeDataResource,
                helixAdmin, helixClusterName, zkHelixPropertyStore);
          default:
            throw new UnsupportedOperationException("Not support kafka consumer type: "
                + kafkaStreamMetadata.getConsumerType());
        }

      default:
        throw new UnsupportedOperationException("Not support realtime stream type: "
            + realtimeDataResource.getStreamType());
    }
  }

  private static IdealState buildInitialKafkaSimpleConsumerRealtimeIdealStateFor(String realtimeResourceName,
      RealtimeDataResourceZKMetadata realtimeDataResource, HelixAdmin helixAdmin, String helixClusterName,
      ZkHelixPropertyStore<ZNRecord> zkHelixPropertyStore) {
    // TODO: Adding ideal states for kafka simple consumer
    return null;
  }

  public static IdealState buildInitialKafkaHighLevelConsumerRealtimeIdealStateFor(String realtimeResourceName,
      RealtimeDataResourceZKMetadata realtimeDataResource, HelixAdmin helixAdmin, String helixClusterName,
      ZkHelixPropertyStore<ZNRecord> zkHelixPropertyStore) {
    final CustomModeISBuilder customModeIdealStateBuilder = new CustomModeISBuilder(realtimeResourceName);
    customModeIdealStateBuilder
        .setStateModel(PinotHelixSegmentOnlineOfflineStateModelGenerator.PINOT_SEGMENT_ONLINE_OFFLINE_STATE_MODEL)
        .setNumPartitions(0).setNumReplica(1).setMaxPartitionsPerNode(1);
    final IdealState idealState = customModeIdealStateBuilder.build();
    idealState.setInstanceGroupTag(realtimeResourceName);
    setupInstanceConfigForKafkaHighLevelConsumer(realtimeResourceName, realtimeDataResource, zkHelixPropertyStore,
        helixAdmin.getInstancesInClusterWithTag(helixClusterName, realtimeResourceName));
    return idealState;
  }

  private static void setupInstanceConfigForKafkaHighLevelConsumer(String realtimeResourceName,
      RealtimeDataResourceZKMetadata realtimeDataResource, ZkHelixPropertyStore<ZNRecord> zkHelixPropertyStore,
      List<String> instanceList) {
    int numInstancesPerReplica = realtimeDataResource.getNumDataInstances() / realtimeDataResource.getNumDataReplicas();
    int partitionId = 0;
    int replicaId = 0;

    String groupId = getGroupIdFromRealtimeDataResource(realtimeDataResource);
    realtimeDataResource.getStreamProviderConfig().get(
        StringUtil
            .join(".", Helix.DataSource.STREAM_PREFIX, Helix.DataSource.Realtime.Kafka.HighLevelConsumer.GROUP_ID));
    for (String instance : instanceList) {
      InstanceZKMetadata instanceZKMetadata = ZKMetadataProvider.getInstanceZKMetadata(zkHelixPropertyStore, instance);
      if (instanceZKMetadata == null) {
        instanceZKMetadata = new InstanceZKMetadata();
        String[] instanceConfigs = instance.split("_");
        assert (instanceConfigs.length == 3);
        instanceZKMetadata.setInstanceType(instanceConfigs[0]);
        instanceZKMetadata.setInstanceName(instanceConfigs[1]);
        instanceZKMetadata.setInstancePort(Integer.parseInt(instanceConfigs[2]));
      }
      instanceZKMetadata.setGroupId(realtimeResourceName, groupId + "_" + replicaId);
      instanceZKMetadata.setPartition(realtimeResourceName, Integer.toString(partitionId));
      partitionId = (partitionId + 1) % numInstancesPerReplica;
      if (partitionId == 0) {
        replicaId++;
      }
      ZKMetadataProvider.setInstanceZKMetadata(zkHelixPropertyStore, instanceZKMetadata);
    }
  }

  private static String getGroupIdFromRealtimeDataResource(RealtimeDataResourceZKMetadata realtimeDataResource) {
    String keyOfGroupId =
        StringUtil
            .join(".", Helix.DataSource.STREAM_PREFIX, Helix.DataSource.Realtime.Kafka.HighLevelConsumer.GROUP_ID);
    String groupId = StringUtil.join("_", realtimeDataResource.getResourceName(), System.currentTimeMillis() + "");
    if (realtimeDataResource.getStreamProviderConfig().containsKey(keyOfGroupId)
        && !realtimeDataResource.getStreamProviderConfig().get(keyOfGroupId).isEmpty()) {
      groupId = realtimeDataResource.getStreamProviderConfig().get(keyOfGroupId);
    }
    return groupId;
  }

  public static IdealState buildInitialRealtimeIdealStateForV2(String realtimeTableName,
      RealtimeTableConfig realtimeTableConfig, HelixAdmin helixAdmin, String helixClusterName, ZkHelixPropertyStore<ZNRecord> zkHelixPropertyStore) {
    KafkaStreamMetadata kafkaStreamMetadata = (KafkaStreamMetadata) (realtimeTableConfig.getIndexingConfig().getStreamConfigs());
    String realtimeServerTenant = ControllerTenantNameBuilder.getRealtimeTenantNameForTenant(realtimeTableConfig.getTenantConfig().getServer());
    switch (kafkaStreamMetadata.getConsumerType()) {
      case highLevel:
        IdealState idealState =
            buildInitialKafkaHighLevelConsumerRealtimeIdealStateForV2(realtimeTableName, helixAdmin, helixClusterName, zkHelixPropertyStore);
        List<String> realtimeInstances = helixAdmin.getInstancesInClusterWithTag(helixClusterName, realtimeServerTenant);
        setupInstanceConfigForKafkaHighLevelConsumerV2(realtimeTableName, realtimeInstances.size(),
            Integer.parseInt(realtimeTableConfig.getValidationConfig().getReplication()),
            realtimeTableConfig.getIndexingConfig().getStreamConfigs(), zkHelixPropertyStore,
            realtimeInstances);
        return idealState;
      case simple:
      default:
        throw new UnsupportedOperationException("Not support kafka consumer type: "
            + kafkaStreamMetadata.getConsumerType());
    }
  }

  public static IdealState buildInitialKafkaHighLevelConsumerRealtimeIdealStateForV2(String realtimeTableName,
      HelixAdmin helixAdmin, String helixClusterName, ZkHelixPropertyStore<ZNRecord> zkHelixPropertyStore) {
    final CustomModeISBuilder customModeIdealStateBuilder = new CustomModeISBuilder(realtimeTableName);
    customModeIdealStateBuilder
        .setStateModel(PinotHelixSegmentOnlineOfflineStateModelGenerator.PINOT_SEGMENT_ONLINE_OFFLINE_STATE_MODEL)
        .setNumPartitions(0).setNumReplica(1).setMaxPartitionsPerNode(1);
    final IdealState idealState = customModeIdealStateBuilder.build();
    idealState.setInstanceGroupTag(realtimeTableName);

    return idealState;
  }

  private static void setupInstanceConfigForKafkaHighLevelConsumerV2(String realtimeTableName,
      int numDataInstances, int numDataReplicas, Map<String, String> streamProviderConfig,
      ZkHelixPropertyStore<ZNRecord> zkHelixPropertyStore, List<String> instanceList) {
    int numInstancesPerReplica = numDataInstances / numDataReplicas;
    int partitionId = 0;
    int replicaId = 0;

    String groupId = getGroupIdFromRealtimeDataResourceV2(realtimeTableName, streamProviderConfig);
    for (String instance : instanceList) {
      InstanceZKMetadata instanceZKMetadata = ZKMetadataProvider.getInstanceZKMetadata(zkHelixPropertyStore, instance);
      if (instanceZKMetadata == null) {
        instanceZKMetadata = new InstanceZKMetadata();
        String[] instanceConfigs = instance.split("_");
        assert (instanceConfigs.length == 3);
        instanceZKMetadata.setInstanceType(instanceConfigs[0]);
        instanceZKMetadata.setInstanceName(instanceConfigs[1]);
        instanceZKMetadata.setInstancePort(Integer.parseInt(instanceConfigs[2]));
      }
      instanceZKMetadata.setGroupId(realtimeTableName, groupId + "_" + replicaId);
      instanceZKMetadata.setPartition(realtimeTableName, Integer.toString(partitionId));
      partitionId = (partitionId + 1) % numInstancesPerReplica;
      if (partitionId == 0) {
        replicaId++;
      }
      ZKMetadataProvider.setInstanceZKMetadata(zkHelixPropertyStore, instanceZKMetadata);
    }
  }

  private static String getGroupIdFromRealtimeDataResourceV2(String realtimeTableName, Map<String, String> streamProviderConfig) {
    String keyOfGroupId = StringUtil.join(".", Helix.DataSource.STREAM_PREFIX, Helix.DataSource.Realtime.Kafka.HighLevelConsumer.GROUP_ID);
    String groupId = StringUtil.join("_", realtimeTableName, System.currentTimeMillis() + "");
    if (streamProviderConfig.containsKey(keyOfGroupId) && !streamProviderConfig.get(keyOfGroupId).isEmpty()) {
      groupId = streamProviderConfig.get(keyOfGroupId);
    }
    return groupId;
  }

  public static IdealState addNewOfflineSegmentToIdealStateForV2(SegmentMetadata segmentMetadata, HelixAdmin helixAdmin,
      String helixClusterName, ZkHelixPropertyStore<ZNRecord> propertyStore, String serverTenant) throws JsonParseException, JsonMappingException, JsonProcessingException,
      JSONException, IOException {

    final String offlineTableName = TableNameBuilder.OFFLINE_TABLE_NAME_BUILDER.forTable(segmentMetadata.getResourceName());

    final String segmentName = segmentMetadata.getName();
    AbstractTableConfig offlineTableConfig =
        ZKMetadataProvider.getOfflineTableConfig(propertyStore, offlineTableName);

    if (!SEGMENT_ASSIGNMENT_STRATEGY_MAP.containsKey(offlineTableName)) {
      SEGMENT_ASSIGNMENT_STRATEGY_MAP.put(offlineTableName, SegmentAssignmentStrategyFactory
          .getSegmentAssignmentStrategy(offlineTableConfig.getValidationConfig().getSegmentAssignmentStrategy()));
    }
    final SegmentAssignmentStrategy segmentAssignmentStrategy = SEGMENT_ASSIGNMENT_STRATEGY_MAP.get(offlineTableName);

    final IdealState currentIdealState = helixAdmin.getResourceIdealState(helixClusterName, offlineTableName);
    final Set<String> currentInstanceSet = currentIdealState.getInstanceSet(segmentName);
    if (currentInstanceSet.isEmpty()) {
      // Adding new Segments
      final int replicas = Integer.parseInt(offlineTableConfig.getValidationConfig().getReplication());
      final List<String> selectedInstances =
          segmentAssignmentStrategy.getAssignedInstances(helixAdmin, helixClusterName, segmentMetadata, replicas, serverTenant);
      for (final String instance : selectedInstances) {
        currentIdealState.setPartitionState(segmentName, instance, ONLINE);
      }
      currentIdealState.setNumPartitions(currentIdealState.getNumPartitions() + 1);
    } else {
      // Update new Segments
      for (final String instance : currentInstanceSet) {
        currentIdealState.setPartitionState(segmentName, instance, OFFLINE);
        currentIdealState.setPartitionState(segmentName, instance, ONLINE);
      }
    }
    return currentIdealState;
  }
}
