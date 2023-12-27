/*
 * Copyright (C) 2023 The Android Open Source Project
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
package android.net.thread;

import static android.net.thread.IntegrationTestUtils.waitFor;

import static com.google.common.io.BaseEncoding.base16;

import static org.junit.Assert.fail;

import android.net.InetAddresses;
import android.net.IpPrefix;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * A class that launches and controls a simulation Full Thread Device (FTD).
 *
 * <p>This class launches an `ot-cli-ftd` process and communicates with it via command line input
 * and output. See <a
 * href="https://github.com/openthread/openthread/blob/main/src/cli/README.md">this page</a> for
 * available commands.
 */
public final class FullThreadDevice {
    private final Process mProcess;
    private final BufferedReader mReader;
    private final BufferedWriter mWriter;

    private ActiveOperationalDataset mActiveOperationalDataset;

    /**
     * Constructs a {@link FullThreadDevice} for the given node ID.
     *
     * <p>It launches an `ot-cli-ftd` process using the given node ID. The node ID is an integer in
     * range [1, OPENTHREAD_SIMULATION_MAX_NETWORK_SIZE]. `OPENTHREAD_SIMULATION_MAX_NETWORK_SIZE`
     * is defined in `external/openthread/examples/platforms/simulation/platform-config.h`.
     *
     * @param nodeId the node ID for the simulation Full Thread Device.
     * @throws IllegalStateException the node ID is already occupied by another simulation Thread
     *     device.
     */
    public FullThreadDevice(int nodeId) {
        try {
            mProcess = Runtime.getRuntime().exec("/system/bin/ot-cli-ftd " + nodeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start ot-cli-ftd (id=" + nodeId + ")", e);
        }
        mReader = new BufferedReader(new InputStreamReader(mProcess.getInputStream()));
        mWriter = new BufferedWriter(new OutputStreamWriter(mProcess.getOutputStream()));
        mActiveOperationalDataset = null;
    }

    /**
     * Returns an OMR (Off-Mesh-Routable) address on this device if any.
     *
     * <p>This methods goes through all unicast addresses on the device and returns the first
     * address which is neither link-local nor mesh-local.
     */
    public Inet6Address getOmrAddress() {
        List<String> addresses = executeCommand("ipaddr");
        IpPrefix meshLocalPrefix = mActiveOperationalDataset.getMeshLocalPrefix();
        for (String address : addresses) {
            if (address.startsWith("fe80:")) {
                continue;
            }
            Inet6Address addr = (Inet6Address) InetAddresses.parseNumericAddress(address);
            if (!meshLocalPrefix.contains(addr)) {
                return addr;
            }
        }
        return null;
    }

    /**
     * Joins the Thread network using the given {@link ActiveOperationalDataset}.
     *
     * @param dataset the Active Operational Dataset
     */
    public void joinNetwork(ActiveOperationalDataset dataset) {
        mActiveOperationalDataset = dataset;
        executeCommand("dataset set active " + base16().lowerCase().encode(dataset.toThreadTlvs()));
        executeCommand("ifconfig up");
        executeCommand("thread start");
    }

    /** Stops the Thread network radio. */
    public void stopThreadRadio() {
        executeCommand("thread stop");
        executeCommand("ifconfig down");
    }

    /**
     * Waits for the Thread device to enter the any state of the given {@link List<String>}.
     *
     * @param states the list of states to wait for. Valid states are "disabled", "detached",
     *     "child", "router" and "leader".
     * @param timeoutSeconds the number of seconds to wait for.
     */
    public void waitForStateAnyOf(List<String> states, int timeoutSeconds) throws TimeoutException {
        waitFor(() -> states.contains(getState()), timeoutSeconds);
    }

    /**
     * Gets the state of the Thread device.
     *
     * @return a string representing the state.
     */
    public String getState() {
        return executeCommand("state").get(0);
    }

    /** Runs the "factoryreset" command on the device. */
    public void factoryReset() {
        try {
            mWriter.write("factoryreset\n");
            mWriter.flush();
            // fill the input buffer to avoid truncating next command
            for (int i = 0; i < 1000; ++i) {
                mWriter.write("\n");
            }
            mWriter.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run factoryreset on ot-cli-ftd", e);
        }
    }

    private List<String> executeCommand(String command) {
        try {
            mWriter.write(command + "\n");
            mWriter.flush();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to write the command " + command + " to ot-cli-ftd", e);
        }
        try {
            return readUntilDone();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read the ot-cli-ftd output of command: " + command, e);
        }
    }

    private List<String> readUntilDone() throws IOException {
        ArrayList<String> result = new ArrayList<>();
        String line;
        while ((line = mReader.readLine()) != null) {
            if (line.equals("Done")) {
                break;
            }
            if (line.startsWith("Error:")) {
                fail("ot-cli-ftd reported an error: " + line);
            }
            if (!line.startsWith("> ")) {
                result.add(line);
            }
        }
        return result;
    }
}