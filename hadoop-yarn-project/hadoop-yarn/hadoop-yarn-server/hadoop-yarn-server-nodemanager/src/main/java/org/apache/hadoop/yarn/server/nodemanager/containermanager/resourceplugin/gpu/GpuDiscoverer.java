/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.nodemanager.containermanager.resourceplugin.gpu;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.Shell;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.server.nodemanager.webapp.dao.gpu.GpuDeviceInformation;
import org.apache.hadoop.yarn.server.nodemanager.webapp.dao.gpu.GpuDeviceInformationParser;
import org.apache.hadoop.yarn.server.nodemanager.webapp.dao.gpu.PerGpuDeviceInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@InterfaceAudience.Private
@InterfaceStability.Unstable
public class GpuDiscoverer {
  public static final Logger LOG = LoggerFactory.getLogger(
      GpuDiscoverer.class);
  @VisibleForTesting
  static final String DEFAULT_BINARY_NAME = "nvidia-smi";

  // When executable path not set, try to search default dirs
  // By default search /usr/bin, /bin, and /usr/local/nvidia/bin (when
  // launched by nvidia-docker.
  private static final Set<String> DEFAULT_BINARY_SEARCH_DIRS = ImmutableSet.of(
      "/usr/bin", "/bin", "/usr/local/nvidia/bin");

  // command should not run more than 10 sec.
  private static final int MAX_EXEC_TIMEOUT_MS = 10 * 1000;
  private static final int MAX_REPEATED_ERROR_ALLOWED = 10;
  private static GpuDiscoverer instance;

  static {
    instance = new GpuDiscoverer();
  }

  private Configuration conf = null;
  private String pathOfGpuBinary = null;
  private Map<String, String> environment = new HashMap<>();
  private GpuDeviceInformationParser parser = new GpuDeviceInformationParser();

  private int numOfErrorExecutionSinceLastSucceed = 0;
  private GpuDeviceInformation lastDiscoveredGpuInformation = null;

  private void validateConfOrThrowException() throws YarnException {
    if (conf == null) {
      throw new YarnException("Please initialize (call initialize) before use "
          + GpuDiscoverer.class.getSimpleName());
    }
  }

  /**
   * Get GPU device information from system.
   * This need to be called after initialize.
   *
   * Please note that this only works on *NIX platform, so external caller
   * need to make sure this.
   *
   * @return GpuDeviceInformation
   * @throws YarnException when any error happens
   */
  synchronized GpuDeviceInformation getGpuDeviceInformation()
      throws YarnException {
    validateConfOrThrowException();

    if (null == pathOfGpuBinary) {
      throw new YarnException(
          "Failed to find GPU discovery executable, please double check "
              + YarnConfiguration.NM_GPU_PATH_TO_EXEC + " setting.");
    }

    if (numOfErrorExecutionSinceLastSucceed == MAX_REPEATED_ERROR_ALLOWED) {
      String msg =
          "Failed to execute GPU device information detection script for "
              + MAX_REPEATED_ERROR_ALLOWED
              + " times, skip following executions.";
      LOG.error(msg);
      throw new YarnException(msg);
    }

    String output;
    try {
      output = Shell.execCommand(environment,
          new String[] { pathOfGpuBinary, "-x", "-q" }, MAX_EXEC_TIMEOUT_MS);
      lastDiscoveredGpuInformation = parser.parseXml(output);
      numOfErrorExecutionSinceLastSucceed = 0;
      return lastDiscoveredGpuInformation;
    } catch (IOException e) {
      numOfErrorExecutionSinceLastSucceed++;
      String msg =
          "Failed to execute " + pathOfGpuBinary + " exception message:" + e
              .getMessage() + ", continue ...";
      if (LOG.isDebugEnabled()) {
        LOG.debug(msg);
      }
      throw new YarnException(e);
    } catch (YarnException e) {
      numOfErrorExecutionSinceLastSucceed++;
      String msg = "Failed to parse xml output" + e.getMessage();
      if (LOG.isDebugEnabled()) {
        LOG.warn(msg, e);
      }
      throw e;
    }
  }

  /**
   * Get list of GPU devices usable by YARN.
   *
   * @return List of GPU devices
   * @throws YarnException when any issue happens
   */
  public synchronized List<GpuDevice> getGpusUsableByYarn()
      throws YarnException {
    validateConfOrThrowException();

    String allowedDevicesStr = conf.get(
        YarnConfiguration.NM_GPU_ALLOWED_DEVICES,
        YarnConfiguration.AUTOMATICALLY_DISCOVER_GPU_DEVICES);

    if (allowedDevicesStr.equals(
        YarnConfiguration.AUTOMATICALLY_DISCOVER_GPU_DEVICES)) {
      return parseGpuDevicesFromAutoDiscoveredGpuInfo();
    } else {
      return parseGpuDevicesFromUserDefinedValues(allowedDevicesStr);
    }
  }

  private List<GpuDevice> parseGpuDevicesFromAutoDiscoveredGpuInfo()
          throws YarnException {
    if (lastDiscoveredGpuInformation == null) {
      String msg = YarnConfiguration.NM_GPU_ALLOWED_DEVICES + " is set to "
          + YarnConfiguration.AUTOMATICALLY_DISCOVER_GPU_DEVICES
          + ", however automatically discovering "
          + "GPU information failed, please check NodeManager log for more"
          + " details, as an alternative, admin can specify "
          + YarnConfiguration.NM_GPU_ALLOWED_DEVICES
          + " manually to enable GPU isolation.";
      LOG.error(msg);
      throw new YarnException(msg);
    }

    List<GpuDevice> gpuDevices = new ArrayList<>();
    if (lastDiscoveredGpuInformation.getGpus() != null) {
      int numberOfGpus = lastDiscoveredGpuInformation.getGpus().size();
      LOG.debug("Found {} GPU devices", numberOfGpus);
      for (int i = 0; i < numberOfGpus; i++) {
        List<PerGpuDeviceInformation> gpuInfos =
            lastDiscoveredGpuInformation.getGpus();
        gpuDevices.add(new GpuDevice(i, gpuInfos.get(i).getMinorNumber()));
      }
    }
    return gpuDevices;
  }

  /**
   * @param devices allowed devices coming from the config.
   *                          Individual devices should be separated by commas.
   *                          <br>The format of individual devices should be:
   *                           &lt;index:&gt;&lt;minorNumber&gt;
   * @return List of GpuDevices
   * @throws YarnException when a GPU device is defined as a duplicate.
   * The first duplicate GPU device will be added to the exception message.
   */
  private List<GpuDevice> parseGpuDevicesFromUserDefinedValues(String devices)
      throws YarnException {
    if (devices.trim().isEmpty()) {
      throw GpuDeviceSpecificationException.createWithEmptyValueSpecified();
    }
    List<GpuDevice> gpuDevices = Lists.newArrayList();
    for (String device : devices.split(",")) {
      if (device.trim().length() > 0) {
        String[] splitByColon = device.trim().split(":");
        if (splitByColon.length != 2) {
          throw GpuDeviceSpecificationException.
              createWithWrongValueSpecified(device, devices);
        }

        GpuDevice gpuDevice = parseGpuDevice(device, splitByColon, devices);
        if (!gpuDevices.contains(gpuDevice)) {
          gpuDevices.add(gpuDevice);
        } else {
          throw GpuDeviceSpecificationException
              .createWithDuplicateValueSpecified(device, devices);
        }
      }
    }
    LOG.info("Allowed GPU devices:" + gpuDevices);

    return gpuDevices;
  }

  private GpuDevice parseGpuDevice(String device, String[] splitByColon,
      String allowedDevicesStr) throws YarnException {
    try {
      int index = Integer.parseInt(splitByColon[0]);
      int minorNumber = Integer.parseInt(splitByColon[1]);
      return new GpuDevice(index, minorNumber);
    } catch (NumberFormatException e) {
      throw GpuDeviceSpecificationException.
          createWithWrongValueSpecified(device, allowedDevicesStr, e);
    }
  }

  public synchronized void initialize(Configuration conf) {
    this.conf = conf;
    numOfErrorExecutionSinceLastSucceed = 0;
    String pathToExecutable = conf.get(YarnConfiguration.NM_GPU_PATH_TO_EXEC,
        YarnConfiguration.DEFAULT_NM_GPU_PATH_TO_EXEC);
    if (pathToExecutable.isEmpty()) {
      pathToExecutable = DEFAULT_BINARY_NAME;
    }

    File binaryPath = new File(pathToExecutable);
    if (!binaryPath.exists()) {
      // When binary not exist, use default setting.
      boolean found = false;
      for (String dir : DEFAULT_BINARY_SEARCH_DIRS) {
        binaryPath = new File(dir, DEFAULT_BINARY_NAME);
        if (binaryPath.exists()) {
          found = true;
          pathOfGpuBinary = binaryPath.getAbsolutePath();
          break;
        }
      }

      if (!found) {
        LOG.warn("Failed to locate binary at:" + binaryPath.getAbsolutePath()
            + ", please double check [" + YarnConfiguration.NM_GPU_PATH_TO_EXEC
            + "] setting. Now use " + "default binary:" + DEFAULT_BINARY_NAME);
      }
    } else{
      // If path specified by user is a directory, use
      if (binaryPath.isDirectory()) {
        binaryPath = new File(binaryPath, DEFAULT_BINARY_NAME);
        LOG.warn("Specified path is a directory, use " + DEFAULT_BINARY_NAME
            + " under the directory, updated path-to-executable:" + binaryPath
            .getAbsolutePath());
      }
      // Validated
      pathOfGpuBinary = binaryPath.getAbsolutePath();
    }

    // Try to discover GPU information once and print
    try {
      LOG.info("Trying to discover GPU information ...");
      GpuDeviceInformation info = getGpuDeviceInformation();
      LOG.info(info.toString());
    } catch (YarnException e) {
      String msg =
          "Failed to discover GPU information from system, exception message:"
              + e.getMessage() + " continue...";
      LOG.warn(msg);
    }
  }

  @VisibleForTesting
  Map<String, String> getEnvironmentToRunCommand() {
    return environment;
  }

  @VisibleForTesting
  String getPathOfGpuBinary() {
    return pathOfGpuBinary;
  }

  public static GpuDiscoverer getInstance() {
    return instance;
  }
}