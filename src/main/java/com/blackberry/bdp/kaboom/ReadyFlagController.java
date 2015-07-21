/*
 * Copyright 2015 BlackBerry, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package com.blackberry.bdp.kaboom;

import com.blackberry.bdp.kaboom.api.KaBoomTopicConfig;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.curator.framework.CuratorFramework;
import org.apache.hadoop.fs.Path;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReadyFlagController {
	private static final Logger LOG = LoggerFactory.getLogger(ReadyFlagController.class);	
	protected static final Random rand = new Random();
	protected static final Charset UTF8 = Charset.forName("UTF-8");	
	
	private final CuratorFramework curator;
	private final static String flagAssignmentsPath = "/kaboom/flag-assignments";
	private final Map<String, KaBoomNodeInfo> clientIdToNodeInfo;
	private final List<String> topicsWithUniqueHdfsRoot;
	
	public ReadyFlagController(StartupConfig config,
		 Map<String, KaBoomNodeInfo> clientIdToNodeInfo,
		 ArrayList<KaBoomTopicConfig> topicConfigs) throws Exception {
		this.curator = config.getCurator();
		this.clientIdToNodeInfo = clientIdToNodeInfo;
		this.topicsWithUniqueHdfsRoot = new ArrayList<>();
		/**
		 * Our topics will for a single service are generally within the same HDFS root path
		 * and we don't want to create a flag propagator for each one.  Let's get a unique 
		 * list of HDFS root paths from all the topics and ensure we only load balance those
		 */
		Map<Path, String> tempHdfsPathToTopic = new HashMap<>();		
		for (KaBoomTopicConfig topicConfig : topicConfigs) {
			Path tempPath = getRootFromPathTemplate(topicConfig.getHdfsRootDir());
			if (!tempHdfsPathToTopic.containsKey(tempPath)) {				
				LOG.info("Topic {} is the first instance with a unique HDFS root path {}", topicConfig.getId(), tempPath);
				tempHdfsPathToTopic.put(tempPath, topicConfig.getId());
				topicsWithUniqueHdfsRoot.add(topicConfig.getId());
			}			
		}
	}
	
	public static Path getRootFromPathTemplate(String pathTemplate) throws Exception {
		Pattern hdfsRootPattern = Pattern.compile("(.*?)/%y%M%d/%H/.*?");
		Matcher hdfsRootMatcher = hdfsRootPattern.matcher(pathTemplate);
		if (hdfsRootMatcher.matches()) {
			return new Path(hdfsRootMatcher.group(1));
		} else {
			throw new Exception("Failed to parse hdfs root from path template: " +  pathTemplate);
		}
	}
	
	public static List<String> getAssignments(StartupConfig config) throws Exception {
		List<String> assignments = new ArrayList<>();
		for (String topic : config.getCurator().getChildren().forPath(flagAssignmentsPath)) {
			try {
				Stat stat = config.getCurator().checkExists().forPath(flagAssignmentsPath + "/" + topic);
				if (stat != null) {
					String assignedClientId = new String(config.getCurator().getData().forPath(
						 flagAssignmentsPath + "/" + topic), UTF8);
					if (assignedClientId.equals(String.valueOf(config.getKaboomId()))) {
						assignments.add(topic);
					}					
				}
			} catch (Exception e) {
				LOG.error("Error tying to determine flag propagator assignment for topic {}", topic);
			}
		}
		return assignments;
	}
	
	public void balance() {
		int totalWeight = 0;
		for (Map.Entry<String, KaBoomNodeInfo> e : clientIdToNodeInfo.entrySet()) {
			totalWeight += e.getValue().getWeight();
		}
		
		// Determine how many topicsWithUniqueHdfsRoot each KaBoom clientId should propagate flags for
		for (Map.Entry<String, KaBoomNodeInfo> e : clientIdToNodeInfo.entrySet()) {
			String clientId = e.getKey();
			KaBoomNodeInfo info = e.getValue();
			info.setFlagPropagatorTargetLoad(topicsWithUniqueHdfsRoot.size() 
				 * (1.0 * info.getWeight() / totalWeight));
		}
		
		// Build the topic:assigned clientId mappings and delete 
		// assignments for clients that are no longer connected
		Map<String, String> topicToClient = new HashMap<>();
		Map<String, List<String>> clientToTopics = new HashMap<>();		
		try {
			for (String topic : curator.getChildren().forPath(flagAssignmentsPath)) {
				try{
					Stat stat = curator.checkExists().forPath(flagAssignmentsPath + "/" + topic);
					if (stat != null) {
						
						if (!topicsWithUniqueHdfsRoot.contains(topic)) {
							curator.delete().forPath(flagAssignmentsPath + "/" + topic);
							LOG.info("{}/{} is no longer a topic of interest, assignment deleted", 
								 flagAssignmentsPath, topic);
							continue;
						}
						
						String client = new String(curator.getData().forPath(flagAssignmentsPath 
							 + "/" + topic), UTF8);
						if (clientIdToNodeInfo.containsKey(client)) {
							List<String> clientsTopics = clientToTopics.get(client);
							if (clientsTopics == null) {
								clientsTopics = new ArrayList<>();
								clientToTopics.put(client, clientsTopics);
							}
							clientsTopics.add(topic);
							topicToClient.put(topic, client);
						} else {
							LOG.debug("[{}] Flag propagator client {} is not connected", topic, client);
							curator.delete().forPath(flagAssignmentsPath + "/" + topic);
						}
					}
				} catch (Exception e) {
					LOG.error("[{}] Failed to grab current client flag propagator assignment for topic", topic);
				}
			}
		} catch (Exception ex) {
			LOG.error("There was a failure iterating over the children of the flagAssignmentPath", ex);
			return;
		}
		
		// Update our clients to reflect our current flag propagator load...		
		for (Map.Entry<String, KaBoomNodeInfo> e : clientIdToNodeInfo.entrySet()) {
			String clientId = e.getKey();
			KaBoomNodeInfo clientInfo = e.getValue();			
			if (clientToTopics.get(clientId) == null) {
				clientInfo.setFlagPropagatorLoad(0);
			} else {
				clientInfo.setFlagPropagatorLoad(clientToTopics.get(clientId).size());
			}
			// ...and delete assignments for clients that are over-assigned their target load 
			if (clientInfo.getFlagPropagatorLoad() >= clientInfo.getFlagPropagatorTargetLoad() + 1) {
				LOG.info("Client {}'s flag propagator load is {} and target load is {}, need to  unassign work",
					 clientId, clientInfo.getFlagPropagatorLoad(), clientInfo.getFlagPropagatorTargetLoad());
				while (clientInfo.getFlagPropagatorLoad() > clientInfo.getFlagPropagatorTargetLoad()) {
					// Find a random topic in the assigned topicsWithUniqueHdfsRoot and delete it
					String topicToDelete = clientToTopics.get(clientId).get(
						 rand.nextInt(clientToTopics.get(clientId).size()));					
					clientToTopics.get(clientId).remove(topicToDelete);
					topicToClient.remove(topicToDelete);
					LOG.info("Client {} deleted map entry for topic {}, still need to delete ZK path...", 
						 clientId, topicToDelete);
					try {
						curator.delete().forPath(flagAssignmentsPath + "/" + topicToDelete);
						LOG.info("Deleted flag propagator assignment ZK path {} for clientId {}:", 
							 flagAssignmentsPath + "/" + topicToDelete, clientId);
					} catch (Exception ex) {
						LOG.error("Failed to delete lag propagator assignment ZK path {} for clientId {}:", 
							 flagAssignmentsPath + "/" + topicToDelete, clientId, ex);
					}
				}
			}
		}	
		
		// Sort the clients based on their number of assignments
		List<String> sortedClients = new ArrayList<>();
		Comparator<String> comparator = new Comparator<String>() {
			@Override
			public int compare(String a, String b) {
				KaBoomNodeInfo infoA = clientIdToNodeInfo.get(a);
				double valA = infoA.getFlagPropagatorLoad() / infoA.getFlagPropagatorTargetLoad();
				KaBoomNodeInfo infoB = clientIdToNodeInfo.get(b);
				double valB = infoB.getFlagPropagatorLoad() / infoB.getFlagPropagatorTargetLoad();
				if (valA == valB) {
					return 0;
				} else {
					if (valA > valB) {
						return 1;
					} else {
						return -1;
					}
				}
			}
		};
		
		sortedClients.addAll(clientIdToNodeInfo.keySet());
		
		/**
		 * Loop through all  topics, sorting the clients based on load each time.
		 * Every unassigned topic should be assigned the least loaded client
		 */
		
		for (String topic : topicsWithUniqueHdfsRoot) {			
			if (topicToClient.get(topic) != null) {				
				continue;
			}
			Collections.sort(sortedClients, comparator);
			String leastLoadedClientId = sortedClients.get(0);
			String assignmentPath = flagAssignmentsPath + "/" + topic;
			try {
				curator.create().withMode(CreateMode.PERSISTENT)
					 .forPath(assignmentPath, leastLoadedClientId.getBytes(UTF8));
				LOG.info("[{}] Flag propagation assigned to {}", topic, leastLoadedClientId);
			} catch (Exception e) {
				LOG.error("[{}] Failed to create assignment {} for clientId {}", 
					 topic, assignmentPath, leastLoadedClientId, e);
			}			
			List<String> clientTopics = clientToTopics.get(leastLoadedClientId);
			if (clientTopics == null) {
				clientTopics = new ArrayList<>();
				clientToTopics.put(leastLoadedClientId, clientTopics);
			}
			clientTopics.add(topic);
			topicToClient.put(topic, leastLoadedClientId);
			clientIdToNodeInfo.get(leastLoadedClientId).setFlagPropagatorLoad(
				 clientIdToNodeInfo.get(leastLoadedClientId).getFlagPropagatorLoad()+ 1);	
		}
	}

}