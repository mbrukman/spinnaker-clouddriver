/*
 * Copyright 2015 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.kato.gce.deploy.handlers

import com.google.api.services.compute.model.InstanceGroupManager
import com.google.api.services.compute.model.InstanceProperties
import com.google.api.services.compute.model.InstanceTemplate
import com.netflix.spinnaker.kato.config.GceConfig
import com.netflix.spinnaker.kato.data.task.Task
import com.netflix.spinnaker.kato.data.task.TaskRepository
import com.netflix.spinnaker.kato.deploy.DeployDescription
import com.netflix.spinnaker.kato.deploy.DeployHandler
import com.netflix.spinnaker.kato.deploy.DeploymentResult
import com.netflix.spinnaker.kato.gce.deploy.GoogleOperationPoller
import com.netflix.spinnaker.kato.gce.deploy.GCEUtil
import com.netflix.spinnaker.kato.gce.deploy.description.BasicGoogleDeployDescription
import com.netflix.spinnaker.mort.gce.provider.view.GoogleSecurityGroupProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class BasicGoogleDeployHandler implements DeployHandler<BasicGoogleDeployDescription> {

  // TODO(duftler): This should move to a common location.
  private static final String BASE_PHASE = "DEPLOY"

  // TODO(duftler): These should be exposed/configurable.
  private static final String DEFAULT_NETWORK_NAME = "default"
  private static final String ACCESS_CONFIG_NAME = "External NAT"
  private static final String ACCESS_CONFIG_TYPE = "ONE_TO_ONE_NAT"

  @Autowired
  private GceConfig.DeployDefaults gceDeployDefaults

  @Autowired
  private GoogleOperationPoller googleOperationPoller

  @Autowired
  GoogleSecurityGroupProvider googleSecurityGroupProvider

  @Autowired
  String googleApplicationName

  private static Task getTask() {
    TaskRepository.threadLocalTask.get()
  }

  @Override
  boolean handles(DeployDescription description) {
    description instanceof BasicGoogleDeployDescription
  }

  /**
   * curl -X POST -H "Content-Type: application/json" -d '[ { "createServerGroup": { "application": "myapp", "stack": "dev", "image": "ubuntu-1404-trusty-v20150909a", "targetSize": 3, "instanceType": "f1-micro", "zone": "us-central1-f", "credentials": "my-account-name" }} ]' localhost:7002/gce/ops
   * curl -X POST -H "Content-Type: application/json" -d '[ { "createServerGroup": { "application": "myapp", "stack": "dev", "freeFormDetails": "something", "image": "ubuntu-1404-trusty-v20150909a", "targetSize": 3, "instanceType": "f1-micro", "zone": "us-central1-f", "credentials": "my-account-name" }} ]' localhost:7002/gce/ops
   * curl -X POST -H "Content-Type: application/json" -d '[ { "createServerGroup": { "application": "myapp", "stack": "dev", "image": "ubuntu-1404-trusty-v20150909a", "targetSize": 3, "instanceType": "f1-micro", "zone": "us-central1-f", "loadBalancers": ["testlb"], "instanceMetadata": { "load-balancer-names": "myapp-testlb" }, "credentials": "my-account-name" }} ]' localhost:7002/gce/ops
   * curl -X POST -H "Content-Type: application/json" -d '[ { "createServerGroup": { "application": "myapp", "stack": "dev", "image": "ubuntu-1404-trusty-v20150909a", "targetSize": 3, "instanceType": "f1-micro", "zone": "us-central1-f", "tags": ["my-tag-1", "my-tag-2"], "credentials": "my-account-name" }} ]' localhost:7002/gce/ops
   *
   * @param description
   * @param priorOutputs
   * @return
   */
  @Override
  DeploymentResult handle(BasicGoogleDeployDescription description, List priorOutputs) {
    def clusterName = GCEUtil.combineAppStackDetail(description.application, description.stack, description.freeFormDetails)

    task.updateStatus BASE_PHASE, "Initializing creation of server group for cluster $clusterName in " +
      "$description.zone..."

    def compute = description.credentials.compute
    def project = description.credentials.project
    def zone = description.zone

    task.updateStatus BASE_PHASE, "Looking up next sequence..."

    def region = GCEUtil.getRegionFromZone(project, zone, compute)

    def nextSequence = GCEUtil.getNextSequence(clusterName, project, region, description.credentials)
    task.updateStatus BASE_PHASE, "Found next sequence ${nextSequence}."

    def serverGroupName = "${clusterName}-v${nextSequence}".toString()
    task.updateStatus BASE_PHASE, "Produced server group name: $serverGroupName"

    def machineType = GCEUtil.queryMachineType(project, zone, description.instanceType, compute, task, BASE_PHASE)

    def sourceImage = GCEUtil.querySourceImage(project, description.image, compute, task, BASE_PHASE, googleApplicationName)

    def network = GCEUtil.queryNetwork(project, description.network ?: DEFAULT_NETWORK_NAME, compute, task, BASE_PHASE)

    def networkLoadBalancers = []

    // We need the full url for each referenced network load balancer.
    if (description.loadBalancers) {
      def forwardingRules =
        GCEUtil.queryForwardingRules(project, region, description.loadBalancers, compute, task, BASE_PHASE)

      networkLoadBalancers = forwardingRules.collect { it.target }
    }

    def securityGroupTags = GCEUtil.querySecurityGroupTags(description.securityGroups, description.accountName,
        googleSecurityGroupProvider, task, BASE_PHASE)

    if (securityGroupTags) {
      description.tags = GCEUtil.mergeDescriptionAndSecurityGroupTags(description.tags, securityGroupTags)
    }

    task.updateStatus BASE_PHASE, "Composing server group $serverGroupName..."

    def attachedDisks = GCEUtil.buildAttachedDisks(project,
                                                   zone,
                                                   sourceImage,
                                                   description.disks,
                                                   false,
                                                   description.instanceType,
                                                   gceDeployDefaults)

    def networkInterface = GCEUtil.buildNetworkInterface(network, ACCESS_CONFIG_NAME, ACCESS_CONFIG_TYPE)

    def metadata = GCEUtil.buildMetadataFromMap(description.instanceMetadata)

    def tags = GCEUtil.buildTagsFromList(description.tags)

    def serviceAccount = GCEUtil.buildServiceAccount(description.authScopes)

    def scheduling = GCEUtil.buildScheduling(description)

    def instanceProperties = new InstanceProperties(machineType: machineType.name,
                                                    disks: attachedDisks,
                                                    networkInterfaces: [networkInterface],
                                                    metadata: metadata,
                                                    tags: tags,
                                                    scheduling: scheduling,
                                                    serviceAccounts: [serviceAccount])

    def instanceTemplate = new InstanceTemplate(name: "$serverGroupName-${System.currentTimeMillis()}",
                                                properties: instanceProperties)
    def instanceTemplateCreateOperation = compute.instanceTemplates().insert(project, instanceTemplate).execute()
    def instanceTemplateUrl = instanceTemplateCreateOperation.targetLink

    // Before building the managed instance group we must check and wait until the instance template is built.
    googleOperationPoller.waitForGlobalOperation(compute, project, instanceTemplateCreateOperation.getName(),
        null, task, "instance template " + GCEUtil.getLocalName(instanceTemplateUrl), BASE_PHASE)

    compute.instanceGroupManagers().insert(project,
                                           zone,
                                           new InstanceGroupManager()
                                               .setName(serverGroupName)
                                               .setBaseInstanceName(serverGroupName)
                                               .setInstanceTemplate(instanceTemplateUrl)
                                               .setTargetSize(description.targetSize)
                                               .setTargetPools(networkLoadBalancers)).execute()

    task.updateStatus BASE_PHASE, "Done creating server group $serverGroupName in $zone."

    DeploymentResult deploymentResult = new DeploymentResult()
    deploymentResult.serverGroupNames = ["$region:$serverGroupName".toString()]
    deploymentResult.serverGroupNameByRegion[region] = serverGroupName
    deploymentResult
  }
}
