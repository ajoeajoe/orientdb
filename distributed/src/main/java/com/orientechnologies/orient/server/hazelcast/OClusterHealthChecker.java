/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */
package com.orientechnologies.orient.server.hazelcast;

import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.server.distributed.ODistributedRequest;
import com.orientechnologies.orient.server.distributed.ODistributedResponse;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog;
import com.orientechnologies.orient.server.distributed.ODistributedServerManager;
import com.orientechnologies.orient.server.distributed.impl.ODistributedStorage;
import com.orientechnologies.orient.server.distributed.impl.task.OHeartbeatTask;
import com.orientechnologies.orient.server.distributed.task.ODistributedOperationException;

import java.util.List;
import java.util.Map;
import java.util.TimerTask;

import static com.orientechnologies.orient.server.hazelcast.OHazelcastPlugin.CONFIG_DBSTATUS_PREFIX;

/**
 * Timer task that checks periodically the cluster health status.
 *
 * @author Luca Garulli (l.garulli--at--orientdb.com)
 */
public class OClusterHealthChecker extends TimerTask {
  private final ODistributedServerManager manager;

  public OClusterHealthChecker(final ODistributedServerManager manager) {
    this.manager = manager;
  }

  public void run() {
    // CHECK CURRENT STATUS OF DBS
    OLogManager.instance().debug(this, "Checking cluster health...");
    try {

      checkServerStatus();
      checkDatabaseStatuses();
      checkServerInStall();

    } catch (HazelcastInstanceNotActiveException e) {
      // IGNORE IT
    } catch (Throwable t) {
      OLogManager.instance().error(this, "Error on checking cluster health", t);
    } finally {
      OLogManager.instance().debug(this, "Cluster health checking completed");
    }
  }

  private void checkServerStatus() {
    if (manager.getNodeStatus() != ODistributedServerManager.NODE_STATUS.ONLINE)
      // ONLY ONLINE NODE CAN TRY TO RECOVER FOR SINGLE DB STATUS
      return;

    for (String dbName : manager.getMessageService().getDatabases()) {
      final ODistributedServerManager.DB_STATUS localNodeStatus = manager.getDatabaseStatus(manager.getLocalNodeName(), dbName);
      if (localNodeStatus != ODistributedServerManager.DB_STATUS.OFFLINE)
        // ONLY OFFLINE NODE/DB CAN BE RECOVERED
        continue;

      final List<String> servers = manager.getOnlineNodes(dbName);
      servers.remove(manager.getLocalNodeName());

      if (servers.isEmpty()) {
        ODistributedServerLog.info(this, manager.getLocalNodeName(), null, ODistributedServerLog.DIRECTION.NONE,
            "No server are ONLINE for database '%s'. Considering local copy of database as the good one. Setting status=ONLINE...",
            dbName);

        manager.setDatabaseStatus(manager.getLocalNodeName(), dbName, ODistributedServerManager.DB_STATUS.ONLINE);

      } else {
        ODistributedServerLog.info(this, manager.getLocalNodeName(), null, ODistributedServerLog.DIRECTION.NONE,
            "Trying to recover current server for database '%s'...", dbName);

        final boolean result = manager.installDatabase(true, dbName,
            ((ODistributedStorage) manager.getStorage(dbName)).getDistributedConfiguration().getDocument());

        if (result)
          ODistributedServerLog.info(this, manager.getLocalNodeName(), null, ODistributedServerLog.DIRECTION.NONE,
              "Recover complete for database '%s'...", dbName);
        else
          ODistributedServerLog.info(this, manager.getLocalNodeName(), null, ODistributedServerLog.DIRECTION.NONE,
              "Recover cannot be completed for database '%s'...", dbName);
      }
    }
  }

  private void checkServerInStall() {
    if (manager.getNodeStatus() != ODistributedServerManager.NODE_STATUS.ONLINE)
      // ONLY ONLINE NODE CAN CHECK FOR OTHERS
      return;

    for (String dbName : manager.getMessageService().getDatabases()) {
      final ODistributedServerManager.DB_STATUS localNodeStatus = manager.getDatabaseStatus(manager.getLocalNodeName(), dbName);
      if (localNodeStatus != ODistributedServerManager.DB_STATUS.ONLINE)
        // ONLY ONLINE NODE/DB CAN CHECK FOR OTHERS
        continue;

      final List<String> servers = manager.getOnlineNodes(dbName);
      servers.remove(manager.getLocalNodeName());

      if (servers.isEmpty())
        continue;

      if (ODistributedServerLog.isDebugEnabled())
        ODistributedServerLog.debug(this, manager.getLocalNodeName(), servers.toString(), ODistributedServerLog.DIRECTION.OUT,
            "Sending heartbeat message to servers (db=%s)", dbName);

      try {
        final ODistributedResponse response = manager.sendRequest(dbName, null, servers, new OHeartbeatTask(),
            manager.getNextMessageIdCounter(), ODistributedRequest.EXECUTION_MODE.RESPONSE, null, null);

        final Object payload = response.getPayload();
        if (payload instanceof Map) {
          final Map<String, Object> responses = (Map<String, Object>) payload;
          servers.removeAll(responses.keySet());
        }

      } catch (ODistributedOperationException e) {
        // NO SERVER RESPONDED, THE SERVER COULD BE ISOLATED: SET ALL THE SERVER AS OFFLINE
      }

      for (String server : servers) {
        setDatabaseOffline(dbName, server);
      }
    }
  }

  private void setDatabaseOffline(final String dbName, final String server) {
    if (manager.getDatabaseStatus(server, dbName) != ODistributedServerManager.DB_STATUS.ONLINE)
      return;

    if (OGlobalConfiguration.DISTRIBUTED_CHECK_HEALTH_CAN_OFFLINE_SERVER.getValueAsBoolean()) {
      ODistributedServerLog.warn(this, manager.getLocalNodeName(), server, ODistributedServerLog.DIRECTION.IN,
          "Server '%s' did not respond to the heartbeat message (db=%s, timeout=%dms). Setting the database as OFFLINE", server,
          dbName, OGlobalConfiguration.DISTRIBUTED_HEARTBEAT_TIMEOUT.getValueAsLong());

      manager.setDatabaseStatus(server, dbName, ODistributedServerManager.DB_STATUS.OFFLINE);

    } else {

      ODistributedServerLog.warn(this, manager.getLocalNodeName(), server, ODistributedServerLog.DIRECTION.IN,
          "Server '%s' did not respond to the heartbeat message (db=%s, timeout=%dms), but cannot be set OFFLINE by configuration",
          server, dbName, OGlobalConfiguration.DISTRIBUTED_HEARTBEAT_TIMEOUT.getValueAsLong());

    }
  }

  private void checkDatabaseStatuses() {
    for (String dbName : manager.getMessageService().getDatabases()) {
      final ODistributedServerManager.DB_STATUS status = (ODistributedServerManager.DB_STATUS) manager.getConfigurationMap()
          .get(CONFIG_DBSTATUS_PREFIX + manager.getLocalNodeName() + "." + dbName);
      if (status == null) {
        OLogManager.instance().warn(this, "Status of database '%s' on server '%s' is missing", dbName, manager.getLocalNodeName());
      }
    }
  }
}
