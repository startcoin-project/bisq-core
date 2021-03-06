/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.node.lite.network;

import bisq.core.dao.node.messages.GetBsqBlocksRequest;
import bisq.core.dao.node.messages.GetBsqBlocksResponse;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.network.CloseConnectionReason;
import bisq.network.p2p.network.Connection;
import bisq.network.p2p.network.MessageListener;
import bisq.network.p2p.network.NetworkNode;
import bisq.network.p2p.peers.PeerManager;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.app.Log;
import bisq.common.proto.network.NetworkEnvelope;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;

import java.util.Random;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Sends a GetBsqBlocksRequest to a full node and listens on corresponding GetBsqBlocksResponse from the full node.
 */
@Slf4j
public class RequestBlocksHandler implements MessageListener {
    private static final long TIMEOUT = 120;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listener
    ///////////////////////////////////////////////////////////////////////////////////////////

    public interface Listener {
        void onComplete(GetBsqBlocksResponse getBsqBlocksResponse);

        @SuppressWarnings("UnusedParameters")
        void onFault(String errorMessage, @SuppressWarnings("SameParameterValue") @Nullable Connection connection);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Class fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final NetworkNode networkNode;
    private final PeerManager peerManager;
    @Getter
    private final NodeAddress nodeAddress;
    @Getter
    private final int startBlockHeight;
    private final Listener listener;
    private Timer timeoutTimer;
    private final int nonce = new Random().nextInt();
    private boolean stopped;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public RequestBlocksHandler(NetworkNode networkNode,
                                PeerManager peerManager,
                                NodeAddress nodeAddress,
                                int startBlockHeight,
                                Listener listener) {
        this.networkNode = networkNode;
        this.peerManager = peerManager;
        this.nodeAddress = nodeAddress;
        this.startBlockHeight = startBlockHeight;
        this.listener = listener;
    }

    public void cancel() {
        cleanup();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void requestBlocks() {
        if (!stopped) {
            GetBsqBlocksRequest getBsqBlocksRequest = new GetBsqBlocksRequest(startBlockHeight, nonce);
            log.debug("getBsqBlocksRequest " + getBsqBlocksRequest);
            if (timeoutTimer == null) {
                timeoutTimer = UserThread.runAfter(() -> {  // setup before sending to avoid race conditions
                            if (!stopped) {
                                String errorMessage = "A timeout occurred at sending getBsqBlocksRequest:" + getBsqBlocksRequest +
                                        " on peersNodeAddress:" + nodeAddress;
                                log.debug(errorMessage + " / RequestDataHandler=" + RequestBlocksHandler.this);
                                handleFault(errorMessage, nodeAddress, CloseConnectionReason.SEND_MSG_TIMEOUT);
                            } else {
                                log.trace("We have stopped already. We ignore that timeoutTimer.run call. " +
                                        "Might be caused by an previous networkNode.sendMessage.onFailure.");
                            }
                        },
                        TIMEOUT);
            }

            log.debug("We send a {} to peer {}. ", getBsqBlocksRequest.getClass().getSimpleName(), nodeAddress);
            networkNode.addMessageListener(this);
            SettableFuture<Connection> future = networkNode.sendMessage(nodeAddress, getBsqBlocksRequest);
            Futures.addCallback(future, new FutureCallback<Connection>() {
                @Override
                public void onSuccess(Connection connection) {
                    if (!stopped) {
                        log.trace("Send " + getBsqBlocksRequest + " to " + nodeAddress + " succeeded.");
                    } else {
                        log.trace("We have stopped already. We ignore that networkNode.sendMessage.onSuccess call." +
                                "Might be caused by an previous timeout.");
                    }
                }

                @Override
                public void onFailure(@NotNull Throwable throwable) {
                    if (!stopped) {
                        String errorMessage = "Sending getBsqBlocksRequest to " + nodeAddress +
                                " failed. That is expected if the peer is offline.\n\t" +
                                "getBsqBlocksRequest=" + getBsqBlocksRequest + "." +
                                "\n\tException=" + throwable.getMessage();
                        log.error(errorMessage);
                        handleFault(errorMessage, nodeAddress, CloseConnectionReason.SEND_MSG_FAILURE);
                    } else {
                        log.trace("We have stopped already. We ignore that networkNode.sendMessage.onFailure call. " +
                                "Might be caused by an previous timeout.");
                    }
                }
            });
        } else {
            log.warn("We have stopped already. We ignore that requestData call.");
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        if (networkEnvelope instanceof GetBsqBlocksResponse) {
            if (connection.getPeersNodeAddressOptional().isPresent() && connection.getPeersNodeAddressOptional().get().equals(nodeAddress)) {
                Log.traceCall(networkEnvelope.toString() + "\n\tconnection=" + connection);
                if (!stopped) {
                    GetBsqBlocksResponse getBsqBlocksResponse = (GetBsqBlocksResponse) networkEnvelope;
                    if (getBsqBlocksResponse.getRequestNonce() == nonce) {
                        stopTimeoutTimer();
                        checkArgument(connection.getPeersNodeAddressOptional().isPresent(),
                                "RequestDataHandler.onMessage: connection.getPeersNodeAddressOptional() must be present " +
                                        "at that moment");
                        cleanup();
                        listener.onComplete(getBsqBlocksResponse);
                    } else {
                        log.warn("Nonce not matching. That can happen rarely if we get a response after a canceled " +
                                        "handshake (timeout causes connection close but peer might have sent a msg before " +
                                        "connection was closed).\n\t" +
                                        "We drop that message. nonce={} / requestNonce={}",
                                nonce, getBsqBlocksResponse.getRequestNonce());
                    }
                } else {
                    log.warn("We have stopped already. We ignore that onDataRequest call.");
                }
            } else {
                log.warn("We got a message from another connection and ignore it. That should never happen.");
            }
        }
    }

    public void stop() {
        cleanup();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////


    @SuppressWarnings("UnusedParameters")
    private void handleFault(String errorMessage, NodeAddress nodeAddress, CloseConnectionReason closeConnectionReason) {
        cleanup();
        peerManager.handleConnectionFault(nodeAddress);
        listener.onFault(errorMessage, null);
    }

    private void cleanup() {
        Log.traceCall();
        stopped = true;
        networkNode.removeMessageListener(this);
        stopTimeoutTimer();
    }

    private void stopTimeoutTimer() {
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }
}
