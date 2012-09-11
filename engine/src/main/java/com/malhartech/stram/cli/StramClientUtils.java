/**
 * Copyright (c) 2012-2012 Malhar, Inc.
 * All rights reserved.
 */
package com.malhartech.stram.cli;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.yarn.api.AMRMProtocol;
import org.apache.hadoop.yarn.api.ClientRMProtocol;
import org.apache.hadoop.yarn.api.ContainerManager;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationReportRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationReportResponse;
import org.apache.hadoop.yarn.api.protocolrecords.KillApplicationRequest;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnRemoteException;
import org.apache.hadoop.yarn.ipc.YarnRPC;
import org.apache.hadoop.yarn.util.Records;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * Collection of utility classes for command line interface package<p>
 * <br>
 * List includes<br>
 * Yarn Client Helper<br>
 * Resource Mgr Client Helper<br>
 * <br>
 *
 */

public class StramClientUtils {

/**
 *
 * TBD<p>
 * <br>
 *
 */

  public static class YarnClientHelper {
    private static final Logger LOG = LoggerFactory.getLogger(YarnClientHelper.class);

    // Configuration
    private final Configuration conf;

    // RPC to communicate to RM
    private final YarnRPC rpc;


    public YarnClientHelper(Configuration conf)  {
      // Set up the configuration and RPC
      this.conf = conf;
      this.rpc = YarnRPC.create(conf);
    }

    public Configuration getConf() {
      return this.conf;
    }

    public YarnRPC  getYarnRPC() {
      return rpc;
    }

    /**
     * Connect to the Resource Manager/Applications Manager<p>
     * @return Handle to communicate with the ASM
     * @throws IOException
     */
    public ClientRMProtocol connectToASM() throws IOException {

      /*
      UserGroupInformation user = UserGroupInformation.getCurrentUser();
      applicationsManager = user.doAs(new PrivilegedAction<ClientRMProtocol>() {
        public ClientRMProtocol run() {
          InetSocketAddress rmAddress = NetUtils.createSocketAddr(conf.get(
            YarnConfiguration.RM_SCHEDULER_ADDRESS,
            YarnConfiguration.DEFAULT_RM_SCHEDULER_ADDRESS));
          LOG.info("Connecting to ResourceManager at " + rmAddress);
          Configuration appsManagerServerConf = new Configuration(conf);
          appsManagerServerConf.setClass(YarnConfiguration.YARN_SECURITY_INFO,
          ClientRMSecurityInfo.class, SecurityInfo.class);
          ClientRMProtocol asm = ((ClientRMProtocol) rpc.getProxy(ClientRMProtocol.class, rmAddress, appsManagerServerConf));
          return asm;
        }
      });
       */
      YarnConfiguration yarnConf = new YarnConfiguration(conf);
      InetSocketAddress rmAddress = yarnConf.getSocketAddr(
          YarnConfiguration.RM_ADDRESS,
          YarnConfiguration.DEFAULT_RM_ADDRESS,
          YarnConfiguration.DEFAULT_RM_PORT);
      LOG.info("Connecting to ResourceManager at " + rmAddress);
      return  ((ClientRMProtocol) rpc.getProxy(
          ClientRMProtocol.class, rmAddress, conf));
    }


    /**
     * Connect to the Resource Manager<p>
     *
     * @return Handle to communicate with the RM
     */
    public AMRMProtocol connectToRM()
    {
      InetSocketAddress rmAddress = conf.getSocketAddr(
        YarnConfiguration.RM_SCHEDULER_ADDRESS,
        YarnConfiguration.DEFAULT_RM_SCHEDULER_ADDRESS,
        YarnConfiguration.DEFAULT_RM_SCHEDULER_PORT);
      LOG.info("Connecting to ResourceManager at " + rmAddress);
      return ((AMRMProtocol) rpc.getProxy(AMRMProtocol.class, rmAddress, conf));
    }

    /**
     * Helper function to connect to CM
     */
    public ContainerManager connectToCM(Container container)
    {
      LOG.debug("Connecting to ContainerManager for containerid=" + container.getId());
      String cmIpPortStr = container.getNodeId().getHost() + ":"
                           + container.getNodeId().getPort();
      InetSocketAddress cmAddress = NetUtils.createSocketAddr(cmIpPortStr);
      LOG.info("Connecting to ContainerManager at " + cmIpPortStr);
      return ((ContainerManager) rpc.getProxy(ContainerManager.class, cmAddress, conf));
    }

  }

  /**
   *
   * Bunch of utilities that ease repeating interactions with {@link ClientRMProtocol}<p>
   *
   */
  public static class ClientRMHelper {
    private static final Logger LOG = LoggerFactory.getLogger(ClientRMHelper.class);

    public final ClientRMProtocol clientRM;

    public ClientRMHelper(YarnClientHelper yarnClient) throws IOException {
      this.clientRM = yarnClient.connectToASM();
    }

    public ApplicationReport getApplicationReport(ApplicationId appId) throws YarnRemoteException {
      // Get application report for the appId we are interested in
      GetApplicationReportRequest reportRequest = Records.newRecord(GetApplicationReportRequest.class);
      reportRequest.setApplicationId(appId);
      GetApplicationReportResponse reportResponse = clientRM.getApplicationReport(reportRequest);
      ApplicationReport report = reportResponse.getApplicationReport();
      return report;
    }

    /**
     * Kill a submitted application by sending a call to the ASM
     *
     * @param appId Application Id to be killed.
     * @throws YarnRemoteException
     */
    public void killApplication(ApplicationId appId) throws YarnRemoteException
    {
      KillApplicationRequest request = Records.newRecord(KillApplicationRequest.class);
      // TODO clarify whether multiple jobs with the same app id can be submitted and be running at
      // the same time.
      // If yes, can we kill a particular attempt only?
      request.setApplicationId(appId);
      // KillApplicationResponse response = applicationsManager.forceKillApplication(request);
      // Response can be ignored as it is non-null on success or
      // throws an exception in case of failures
      clientRM.forceKillApplication(request);
    }


    public static interface AppStatusCallback {
      boolean exitLoop(ApplicationReport report);
    }


    /**
     * Monitor the submitted application for completion. Kill application if time expires.
     *
     * @param appId Application Id of application to be monitored
     * @return true if application completed successfully
     * @throws YarnRemoteException
     */
    public boolean waitForCompletion(ApplicationId appId, AppStatusCallback callback, long timeoutMillis) throws YarnRemoteException
    {
      long startMillis = System.currentTimeMillis();
      while (true) {

        // Check app status every 1 second.
        try {
          Thread.sleep(1000);
        }
        catch (InterruptedException e) {
          LOG.debug("Thread sleep in monitoring loop interrupted");
        }

        ApplicationReport report = getApplicationReport(appId);
        if (callback.exitLoop(report) == true) {
          return true;
        }

        YarnApplicationState state = report.getYarnApplicationState();
        FinalApplicationStatus dsStatus = report.getFinalApplicationStatus();
        if (YarnApplicationState.FINISHED == state) {
          if (FinalApplicationStatus.SUCCEEDED == dsStatus) {
            LOG.info("Application has completed successfully. Breaking monitoring loop");
            return true;
          }
          else {
            LOG.info("Application did finished unsuccessfully."
                     + " YarnState=" + state.toString() + ", DSFinalStatus=" + dsStatus.toString()
                     + ". Breaking monitoring loop");
            return false;
          }
        }
        else if (YarnApplicationState.KILLED == state
                 || YarnApplicationState.FAILED == state) {
          LOG.info("Application did not finish."
                   + " YarnState=" + state.toString() + ", DSFinalStatus=" + dsStatus.toString()
                   + ". Breaking monitoring loop");
          return false;
        }

        if (System.currentTimeMillis() - startMillis > timeoutMillis) {
          LOG.info("Reached specified timeout. Killing application");
          killApplication(appId);
          return false;
        }
      }
    }
  }

  public static File getSettingsRootDir() {
    return new File(FileUtils.getUserDirectory(), ".stram");
  }

}
