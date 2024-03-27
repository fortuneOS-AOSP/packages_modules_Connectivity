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

package com.android.server.thread;

import static android.net.thread.ActiveOperationalDataset.CHANNEL_PAGE_24_GHZ;
import static android.net.thread.ThreadNetworkController.STATE_DISABLED;
import static android.net.thread.ThreadNetworkController.STATE_ENABLED;
import static android.net.thread.ThreadNetworkException.ERROR_FAILED_PRECONDITION;
import static android.net.thread.ThreadNetworkException.ERROR_INTERNAL_ERROR;
import static android.net.thread.ThreadNetworkException.ERROR_THREAD_DISABLED;
import static android.net.thread.ThreadNetworkManager.DISALLOW_THREAD_NETWORK;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;

import static com.android.server.thread.openthread.IOtDaemon.ErrorCode.OT_ERROR_INVALID_STATE;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkAgent;
import android.net.NetworkProvider;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.IActiveOperationalDatasetReceiver;
import android.net.thread.IOperationReceiver;
import android.net.thread.ThreadNetworkException;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserManager;
import android.os.test.TestLooper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.connectivity.resources.R;
import com.android.server.connectivity.ConnectivityResources;
import com.android.server.thread.openthread.MeshcopTxtAttributes;
import com.android.server.thread.openthread.testing.FakeOtDaemon;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Unit tests for {@link ThreadNetworkControllerService}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class ThreadNetworkControllerServiceTest {
    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset new":
    // Active Timestamp: 1
    // Channel: 19
    // Channel Mask: 0x07FFF800
    // Ext PAN ID: ACC214689BC40BDF
    // Mesh Local Prefix: fd64:db12:25f4:7e0b::/64
    // Network Key: F26B3153760F519A63BAFDDFFC80D2AF
    // Network Name: OpenThread-d9a0
    // PAN ID: 0xD9A0
    // PSKc: A245479C836D551B9CA557F7B9D351B4
    // Security Policy: 672 onrcb
    private static final byte[] DEFAULT_ACTIVE_DATASET_TLVS =
            base16().decode(
                            "0E080000000000010000000300001335060004001FFFE002"
                                    + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                                    + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                                    + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                                    + "B9D351B40C0402A0FFF8");
    private static final ActiveOperationalDataset DEFAULT_ACTIVE_DATASET =
            ActiveOperationalDataset.fromThreadTlvs(DEFAULT_ACTIVE_DATASET_TLVS);
    private static final String DEFAULT_NETWORK_NAME = "thread-wpan0";
    private static final int OT_ERROR_NONE = 0;
    private static final int DEFAULT_SUPPORTED_CHANNEL_MASK = 0x07FFF800; // from channel 11 to 26
    private static final int DEFAULT_PREFERRED_CHANNEL_MASK = 0x00000800; // channel 11
    private static final int DEFAULT_SELECTED_CHANNEL = 11;
    private static final byte[] DEFAULT_SUPPORTED_CHANNEL_MASK_ARRAY = base16().decode("001FFFE0");

    private static final String TEST_VENDOR_OUI = "AC-DE-48";
    private static final byte[] TEST_VENDOR_OUI_BYTES = new byte[] {(byte) 0xAC, (byte) 0xDE, 0x48};
    private static final String TEST_VENDOR_NAME = "test vendor";
    private static final String TEST_MODEL_NAME = "test model";

    @Mock private ConnectivityManager mMockConnectivityManager;
    @Mock private NetworkAgent mMockNetworkAgent;
    @Mock private TunInterfaceController mMockTunIfController;
    @Mock private ParcelFileDescriptor mMockTunFd;
    @Mock private InfraInterfaceController mMockInfraIfController;
    @Mock private ThreadPersistentSettings mMockPersistentSettings;
    @Mock private NsdPublisher mMockNsdPublisher;
    @Mock private UserManager mMockUserManager;
    @Mock private IBinder mIBinder;
    @Mock Resources mResources;
    @Mock ConnectivityResources mConnectivityResources;

    private Context mContext;
    private TestLooper mTestLooper;
    private FakeOtDaemon mFakeOtDaemon;
    private ThreadNetworkControllerService mService;
    @Captor private ArgumentCaptor<ActiveOperationalDataset> mActiveDatasetCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = spy(ApplicationProvider.getApplicationContext());
        doNothing()
                .when(mContext)
                .enforceCallingOrSelfPermission(
                        eq(PERMISSION_THREAD_NETWORK_PRIVILEGED), anyString());

        mTestLooper = new TestLooper();
        final Handler handler = new Handler(mTestLooper.getLooper());
        NetworkProvider networkProvider =
                new NetworkProvider(mContext, mTestLooper.getLooper(), "ThreadNetworkProvider");

        mFakeOtDaemon = new FakeOtDaemon(handler);
        when(mMockTunIfController.getTunFd()).thenReturn(mMockTunFd);

        when(mMockPersistentSettings.get(any())).thenReturn(true);
        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(false);

        when(mConnectivityResources.get()).thenReturn(mResources);
        when(mResources.getString(eq(R.string.config_thread_vendor_name)))
                .thenReturn(TEST_VENDOR_NAME);
        when(mResources.getString(eq(R.string.config_thread_vendor_oui)))
                .thenReturn(TEST_VENDOR_OUI);
        when(mResources.getString(eq(R.string.config_thread_model_name)))
                .thenReturn(TEST_MODEL_NAME);

        mService =
                new ThreadNetworkControllerService(
                        mContext,
                        handler,
                        networkProvider,
                        () -> mFakeOtDaemon,
                        mMockConnectivityManager,
                        mMockTunIfController,
                        mMockInfraIfController,
                        mMockPersistentSettings,
                        mMockNsdPublisher,
                        mMockUserManager,
                        mConnectivityResources);
        mService.setTestNetworkAgent(mMockNetworkAgent);
    }

    @Test
    public void initialize_tunInterfaceAndNsdPublisherSetToOtDaemon() throws Exception {
        when(mMockTunIfController.getTunFd()).thenReturn(mMockTunFd);

        mService.initialize();
        mTestLooper.dispatchAll();

        verify(mMockTunIfController, times(1)).createTunInterface();
        assertThat(mFakeOtDaemon.getTunFd()).isEqualTo(mMockTunFd);
        assertThat(mFakeOtDaemon.getNsdPublisher()).isEqualTo(mMockNsdPublisher);
    }

    @Test
    public void initialize_vendorAndModelNameInResourcesAreSetToOtDaemon() throws Exception {
        when(mResources.getString(eq(R.string.config_thread_vendor_name)))
                .thenReturn(TEST_VENDOR_NAME);
        when(mResources.getString(eq(R.string.config_thread_vendor_oui)))
                .thenReturn(TEST_VENDOR_OUI);
        when(mResources.getString(eq(R.string.config_thread_model_name)))
                .thenReturn(TEST_MODEL_NAME);

        mService.initialize();
        mTestLooper.dispatchAll();

        MeshcopTxtAttributes meshcopTxts = mFakeOtDaemon.getOverriddenMeshcopTxtAttributes();
        assertThat(meshcopTxts.vendorName).isEqualTo(TEST_VENDOR_NAME);
        assertThat(meshcopTxts.vendorOui).isEqualTo(TEST_VENDOR_OUI_BYTES);
        assertThat(meshcopTxts.modelName).isEqualTo(TEST_MODEL_NAME);
    }

    @Test
    public void getMeshcopTxtAttributes_emptyVendorName_accepted() {
        when(mResources.getString(eq(R.string.config_thread_vendor_name))).thenReturn("");

        MeshcopTxtAttributes meshcopTxts =
                ThreadNetworkControllerService.getMeshcopTxtAttributes(mResources);

        assertThat(meshcopTxts.vendorName).isEqualTo("");
    }

    @Test
    public void getMeshcopTxtAttributes_tooLongVendorName_throwsIllegalStateException() {
        when(mResources.getString(eq(R.string.config_thread_vendor_name)))
                .thenReturn("vendor name is 25 bytes!!");

        assertThrows(
                IllegalStateException.class,
                () -> ThreadNetworkControllerService.getMeshcopTxtAttributes(mResources));
    }

    @Test
    public void getMeshcopTxtAttributes_tooLongModelName_throwsIllegalStateException() {
        when(mResources.getString(eq(R.string.config_thread_model_name)))
                .thenReturn("model name is 25 bytes!!!");

        assertThrows(
                IllegalStateException.class,
                () -> ThreadNetworkControllerService.getMeshcopTxtAttributes(mResources));
    }

    @Test
    public void getMeshcopTxtAttributes_emptyModelName_accepted() {
        when(mResources.getString(eq(R.string.config_thread_model_name))).thenReturn("");

        var meshcopTxts = ThreadNetworkControllerService.getMeshcopTxtAttributes(mResources);
        assertThat(meshcopTxts.modelName).isEqualTo("");
    }

    @Test
    public void getMeshcopTxtAttributes_invalidVendorOui_throwsIllegalStateException() {
        assertThrows(
                IllegalStateException.class, () -> getMeshcopTxtAttributesWithVendorOui("ABCDEFA"));
        assertThrows(
                IllegalStateException.class, () -> getMeshcopTxtAttributesWithVendorOui("ABCDEG"));
        assertThrows(
                IllegalStateException.class, () -> getMeshcopTxtAttributesWithVendorOui("ABCD"));
        assertThrows(
                IllegalStateException.class,
                () -> getMeshcopTxtAttributesWithVendorOui("AB.CD.EF"));
    }

    @Test
    public void getMeshcopTxtAttributes_validVendorOui_accepted() {
        assertThat(getMeshcopTxtAttributesWithVendorOui("010203")).isEqualTo(new byte[] {1, 2, 3});
        assertThat(getMeshcopTxtAttributesWithVendorOui("01-02-03"))
                .isEqualTo(new byte[] {1, 2, 3});
        assertThat(getMeshcopTxtAttributesWithVendorOui("01:02:03"))
                .isEqualTo(new byte[] {1, 2, 3});
        assertThat(getMeshcopTxtAttributesWithVendorOui("ABCDEF"))
                .isEqualTo(new byte[] {(byte) 0xAB, (byte) 0xCD, (byte) 0xEF});
        assertThat(getMeshcopTxtAttributesWithVendorOui("abcdef"))
                .isEqualTo(new byte[] {(byte) 0xAB, (byte) 0xCD, (byte) 0xEF});
    }

    private byte[] getMeshcopTxtAttributesWithVendorOui(String vendorOui) {
        when(mResources.getString(eq(R.string.config_thread_vendor_oui))).thenReturn(vendorOui);
        return ThreadNetworkControllerService.getMeshcopTxtAttributes(mResources).vendorOui;
    }

    @Test
    public void join_otDaemonRemoteFailure_returnsInternalError() throws Exception {
        mService.initialize();
        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);
        mFakeOtDaemon.setJoinException(new RemoteException("ot-daemon join() throws"));

        mService.join(DEFAULT_ACTIVE_DATASET, mockReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, never()).onSuccess();
        verify(mockReceiver, times(1)).onError(eq(ERROR_INTERNAL_ERROR), anyString());
    }

    @Test
    public void join_succeed_threadNetworkRegistered() throws Exception {
        mService.initialize();
        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);

        mService.join(DEFAULT_ACTIVE_DATASET, mockReceiver);
        // Here needs to call Testlooper#dispatchAll twices because TestLooper#moveTimeForward
        // operates on only currently enqueued messages but the delayed message is posted from
        // another Handler task.
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(FakeOtDaemon.JOIN_DELAY.toMillis() + 100);
        mTestLooper.dispatchAll();

        verify(mockReceiver, times(1)).onSuccess();
        verify(mMockNetworkAgent, times(1)).register();
    }

    @Test
    public void userRestriction_initWithUserRestricted_otDaemonNotStarted() {
        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(true);

        mService.initialize();
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.isInitialized()).isFalse();
    }

    @Test
    public void userRestriction_initWithUserNotRestricted_threadIsEnabled() {
        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(false);

        mService.initialize();
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.getEnabledState()).isEqualTo(STATE_ENABLED);
    }

    @Test
    public void userRestriction_userBecomesRestricted_stateIsDisabledButNotPersisted() {
        AtomicReference<BroadcastReceiver> receiverRef = new AtomicReference<>();
        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(false);
        doAnswer(
                        invocation -> {
                            receiverRef.set((BroadcastReceiver) invocation.getArguments()[0]);
                            return null;
                        })
                .when(mContext)
                .registerReceiver(any(BroadcastReceiver.class), any(), any(), any());
        mService.initialize();
        mTestLooper.dispatchAll();

        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(true);
        receiverRef.get().onReceive(mContext, new Intent());
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.getEnabledState()).isEqualTo(STATE_DISABLED);
        verify(mMockPersistentSettings, never())
                .put(eq(ThreadPersistentSettings.THREAD_ENABLED.key), eq(false));
    }

    @Test
    public void userRestriction_userBecomesNotRestricted_stateIsEnabledButNotPersisted() {
        AtomicReference<BroadcastReceiver> receiverRef = new AtomicReference<>();
        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(true);
        doAnswer(
                        invocation -> {
                            receiverRef.set((BroadcastReceiver) invocation.getArguments()[0]);
                            return null;
                        })
                .when(mContext)
                .registerReceiver(any(BroadcastReceiver.class), any(), any(), any());
        mService.initialize();
        mTestLooper.dispatchAll();

        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(false);
        receiverRef.get().onReceive(mContext, new Intent());
        mTestLooper.dispatchAll();

        assertThat(mFakeOtDaemon.getEnabledState()).isEqualTo(STATE_ENABLED);
        verify(mMockPersistentSettings, never())
                .put(eq(ThreadPersistentSettings.THREAD_ENABLED.key), eq(true));
    }

    @Test
    public void userRestriction_setEnabledWhenUserRestricted_failedPreconditionError() {
        when(mMockUserManager.hasUserRestriction(eq(DISALLOW_THREAD_NETWORK))).thenReturn(true);
        mService.initialize();

        CompletableFuture<Void> setEnabledFuture = new CompletableFuture<>();
        mService.setEnabled(true, newOperationReceiver(setEnabledFuture));
        mTestLooper.dispatchAll();

        var thrown = assertThrows(ExecutionException.class, () -> setEnabledFuture.get());
        ThreadNetworkException failure = (ThreadNetworkException) thrown.getCause();
        assertThat(failure.getErrorCode()).isEqualTo(ERROR_FAILED_PRECONDITION);
    }

    private static IOperationReceiver newOperationReceiver(CompletableFuture<Void> future) {
        return new IOperationReceiver.Stub() {
            @Override
            public void onSuccess() {
                future.complete(null);
            }

            @Override
            public void onError(int errorCode, String errorMessage) {
                future.completeExceptionally(new ThreadNetworkException(errorCode, errorMessage));
            }
        };
    }

    @Test
    public void createRandomizedDataset_succeed_activeDatasetCreated() throws Exception {
        final IActiveOperationalDatasetReceiver mockReceiver =
                mock(IActiveOperationalDatasetReceiver.class);
        mFakeOtDaemon.setChannelMasks(
                DEFAULT_SUPPORTED_CHANNEL_MASK, DEFAULT_PREFERRED_CHANNEL_MASK);
        mFakeOtDaemon.setChannelMasksReceiverOtError(OT_ERROR_NONE);

        mService.createRandomizedDataset(DEFAULT_NETWORK_NAME, mockReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, never()).onError(anyInt(), anyString());
        verify(mockReceiver, times(1)).onSuccess(mActiveDatasetCaptor.capture());
        ActiveOperationalDataset activeDataset = mActiveDatasetCaptor.getValue();
        assertThat(activeDataset.getNetworkName()).isEqualTo(DEFAULT_NETWORK_NAME);
        assertThat(activeDataset.getChannelMask().size()).isEqualTo(1);
        assertThat(activeDataset.getChannelMask().get(CHANNEL_PAGE_24_GHZ))
                .isEqualTo(DEFAULT_SUPPORTED_CHANNEL_MASK_ARRAY);
        assertThat(activeDataset.getChannel()).isEqualTo(DEFAULT_SELECTED_CHANNEL);
    }

    @Test
    public void createRandomizedDataset_otDaemonRemoteFailure_returnsPreconditionError()
            throws Exception {
        final IActiveOperationalDatasetReceiver mockReceiver =
                mock(IActiveOperationalDatasetReceiver.class);
        mFakeOtDaemon.setChannelMasksReceiverOtError(OT_ERROR_INVALID_STATE);
        when(mockReceiver.asBinder()).thenReturn(mIBinder);

        mService.createRandomizedDataset(DEFAULT_NETWORK_NAME, mockReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, never()).onSuccess(any(ActiveOperationalDataset.class));
        verify(mockReceiver, times(1)).onError(eq(ERROR_INTERNAL_ERROR), anyString());
    }

    @Test
    public void forceStopOtDaemonForTest_noPermission_throwsSecurityException() {
        doThrow(new SecurityException(""))
                .when(mContext)
                .enforceCallingOrSelfPermission(eq(PERMISSION_THREAD_NETWORK_PRIVILEGED), any());

        assertThrows(
                SecurityException.class,
                () -> mService.forceStopOtDaemonForTest(true, new IOperationReceiver.Default()));
    }

    @Test
    public void forceStopOtDaemonForTest_enabled_otDaemonDiesAndJoinFails() throws Exception {
        mService.initialize();
        IOperationReceiver mockReceiver = mock(IOperationReceiver.class);
        IOperationReceiver mockJoinReceiver = mock(IOperationReceiver.class);

        mService.forceStopOtDaemonForTest(true, mockReceiver);
        mTestLooper.dispatchAll();
        mService.join(DEFAULT_ACTIVE_DATASET, mockJoinReceiver);
        mTestLooper.dispatchAll();

        verify(mockReceiver, times(1)).onSuccess();
        assertThat(mFakeOtDaemon.isInitialized()).isFalse();
        verify(mockJoinReceiver, times(1)).onError(eq(ERROR_THREAD_DISABLED), anyString());
    }

    @Test
    public void forceStopOtDaemonForTest_disable_otDaemonRestartsAndJoinSccess() throws Exception {
        mService.initialize();
        IOperationReceiver mockReceiver = mock(IOperationReceiver.class);
        IOperationReceiver mockJoinReceiver = mock(IOperationReceiver.class);

        mService.forceStopOtDaemonForTest(true, mock(IOperationReceiver.class));
        mTestLooper.dispatchAll();
        mService.forceStopOtDaemonForTest(false, mockReceiver);
        mTestLooper.dispatchAll();
        mService.join(DEFAULT_ACTIVE_DATASET, mockJoinReceiver);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(FakeOtDaemon.JOIN_DELAY.toMillis() + 100);
        mTestLooper.dispatchAll();

        verify(mockReceiver, times(1)).onSuccess();
        assertThat(mFakeOtDaemon.isInitialized()).isTrue();
        verify(mockJoinReceiver, times(1)).onSuccess();
    }

    @Test
    public void onOtDaemonDied_joinedNetwork_interfaceStateBackToUp() throws Exception {
        mService.initialize();
        final IOperationReceiver mockReceiver = mock(IOperationReceiver.class);
        mService.join(DEFAULT_ACTIVE_DATASET, mockReceiver);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(FakeOtDaemon.JOIN_DELAY.toMillis() + 100);
        mTestLooper.dispatchAll();

        Mockito.reset(mMockInfraIfController);
        mFakeOtDaemon.terminate();
        mTestLooper.dispatchAll();

        verify(mMockTunIfController, times(1)).onOtDaemonDied();
        InOrder inOrder = Mockito.inOrder(mMockTunIfController);
        inOrder.verify(mMockTunIfController, times(1)).setInterfaceUp(false);
        inOrder.verify(mMockTunIfController, times(1)).setInterfaceUp(true);
    }
}