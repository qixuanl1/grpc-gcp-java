/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.grpc.gcp;

import com.google.common.base.MoreObjects;
import com.google.grpc.gcp.proto.AffinityConfig;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientCall.Listener;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * A wrapper of ClientCall that can fetch the affinitykey from the request/response message.
 *
 * <p>It stores the information such as method, calloptions, the ChannelRef which created it, etc to
 * facilitate creating new calls. It gets the affinitykey from the request/response message, and
 * defines the callback functions to manage the number of active streams and bind/unbind the
 * affinity key with the channel.
 */
public class GcpClientCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {

  private static final Logger logger = Logger.getLogger(GcpClientCall.class.getName());

  private Metadata cachedHeaders;
  private Listener<RespT> cachedListener;
  private final MethodDescriptor<ReqT, RespT> methodDescriptor;
  private final CallOptions callOptions;
  private final GcpManagedChannel delegateChannel;
  private final AffinityConfig affinity;

  private GcpManagedChannel.ChannelRef delegateChannelRef = null;
  private ClientCall<ReqT, RespT> delegateCall = null;
  private boolean isCompressed = true;
  private int msgRequested = 0;
  private boolean msgSent = false;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean received = new AtomicBoolean(false);
  private final AtomicBoolean decremented = new AtomicBoolean(false);

  private final Lock lock = new ReentrantLock();
  private final Condition msgCon = lock.newCondition();

  protected GcpClientCall(
      GcpManagedChannel delegateChannel,
      MethodDescriptor<ReqT, RespT> methodDescriptor,
      CallOptions callOptions,
      AffinityConfig affinity) {
    this.methodDescriptor = methodDescriptor;
    this.callOptions = callOptions;
    this.delegateChannel = delegateChannel;
    this.affinity = affinity;
  }

  /** Wait until the first sendMessage() is finished once it has started. */
  private void waitingForMessageSent() {
    if (!msgSent) {
      lock.lock();
      try {
        while (!msgSent) {
          msgCon.await();
        }
      } catch (InterruptedException e) {
        logger.warning(e.getMessage());
      } finally {
        lock.unlock();
      }
    }
  }

  private void setMessageSent() {
    if (!msgSent) {
      lock.lock();
      try {
        if (!msgSent) {
          msgSent = true;
          msgCon.signalAll();
        }
      } finally {
        lock.unlock();
      }
    }
  }

  @Override
  public void start(Listener<RespT> responseListener, Metadata headers) {
    cachedHeaders = headers;
    cachedListener = responseListener;
  }

  @Override
  public void request(int numMessages) {
    if (!started.get()) {
      // Messages might be requested before sendMessage()
      // See io.grpc.ClientCalls()
      msgRequested = numMessages;
    } else {
      waitingForMessageSent();
      delegateCall.request(numMessages);
    }
  }

  @Override
  public void setMessageCompression(boolean enabled) {
    if (started.get()) {
      waitingForMessageSent();
      delegateCall.setMessageCompression(enabled);
    } else if (enabled) {
      isCompressed = true;
    } else {
      isCompressed = false;
    }
  }

  /** DO NOT cancel the call before sendMessage(). */
  @Override
  public void cancel(String message, Throwable cause) {
    if (started.get()) {
      if (!decremented.getAndSet(true)) {
        delegateChannelRef.activeStreamsCountDecr();
      }
      waitingForMessageSent();
      delegateCall.cancel(message, cause);
    } else {
      throw new IllegalStateException("Calling cancel() before sendMessage() is not permitted.");
    }
  }

  /** DO NOT halfclose the call before sendMessage(). */
  @Override
  public void halfClose() {
    if (started.get()) {
      waitingForMessageSent();
      delegateCall.halfClose();
    } else {
      throw new IllegalStateException("Calling halfclose() before sendMessage() is not permitted.");
    }
  }

  /**
   * Delay executing operations until call.sendMessage() is called, switch the channel, start the
   * call, and finally do sendMessage().
   *
   * <p>call.start(), call.request() and setMessageCompression() are permitted to be called multiple
   * times prior to sendMessage(), but only the last one will be valid. Do not call cancel(),
   * halfclose() before sendMessage().
   */
  @Override
  public void sendMessage(ReqT message) {
    synchronized (this) {
      if (!started.getAndSet(true)) {
        // Check if the current channelRef is bound with the key and change it if necessary.
        // If no channel is bound with the key, use the least busy one.
        String key = delegateChannel.checkKey(message, true, methodDescriptor);
        if (key != null && key != "" && delegateChannel.getChannelRef(key) != null) {
          delegateChannelRef = delegateChannel.getChannelRef(key);
        } else {
          delegateChannelRef = delegateChannel.getChannelRef();
        }
        if (key != null && affinity.getCommand() == AffinityConfig.Command.UNBIND) {
          delegateChannel.unbind(key);
        }
        delegateChannelRef.activeStreamsCountIncr();

        // Create the client call and do the previous operations.
        delegateCall = delegateChannelRef.getChannel().newCall(methodDescriptor, callOptions);
        delegateCall.start(getListener(cachedListener), cachedHeaders);
        delegateCall.setMessageCompression(isCompressed);
        if (msgRequested != 0) {
          delegateCall.request(msgRequested);
        }
      }
    }
    delegateCall.sendMessage(message);
    setMessageSent();
  }

  @Override
  public boolean isReady() {
    if (delegateCall != null) {
      return delegateCall.isReady();
    }
    return true;
  }

  /** May only be called after Listener#onHeaders or Listener#onClose. */
  @Override
  public Attributes getAttributes() {
    if (!started.get()) {
      throw new IllegalStateException(
          "Calling getAttributes() before Listener#onHeaders or Listener#onClose.");
    }
    return delegateCall.getAttributes();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("delegate", delegateCall).toString();
  }

  private Listener<RespT> getListener(final Listener<RespT> responseListener) {

    return new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
        responseListener) {
      // Decrement the stream number by one when the call is closed.
      @Override
      public void onClose(Status status, Metadata trailers) {
        if (!decremented.getAndSet(true)) {
          delegateChannelRef.activeStreamsCountDecr();
        }
        responseListener.onClose(status, trailers);
      }

      // If the command is "BIND", fetch the affinitykey from the response message and bind it
      // with the channelRef.
      @Override
      public void onMessage(RespT message) {
        if (!received.getAndSet(true)) {
          String key = delegateChannel.checkKey(message, false, methodDescriptor);
          if (key != null) {
            delegateChannel.bind(delegateChannelRef, key);
          }
        }
        responseListener.onMessage(message);
      }
    };
  }

  /**
   * A simple wrapper of ClientCall.
   *
   * <p>It defines the callback function to manage the number of active streams of a ChannelRef
   * everytime a call is started/closed.
   */
  public static class SimpleGcpClientCall<ReqT, RespT> extends ForwardingClientCall<ReqT, RespT> {
    private final AtomicBoolean decremented = new AtomicBoolean(false);

    private final GcpManagedChannel.ChannelRef channelRef;
    private final ClientCall<ReqT, RespT> delegateCall;

    protected SimpleGcpClientCall(
        GcpManagedChannel.ChannelRef channelRef,
        MethodDescriptor<ReqT, RespT> methodDescriptor,
        CallOptions callOptions) {
      this.channelRef = channelRef;
      this.delegateCall = channelRef.getChannel().newCall(methodDescriptor, callOptions);
    }

    @Override
    protected ClientCall<ReqT, RespT> delegate() {
      return delegateCall;
    }

    @Override
    public void start(Listener<RespT> responseListener, Metadata headers) {

      Listener<RespT> listener =
          new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
              responseListener) {
            @Override
            public void onClose(Status status, Metadata trailers) {
              if (!decremented.getAndSet(true)) {
                channelRef.activeStreamsCountDecr();
              }
              super.onClose(status, trailers);
            }
          };

      channelRef.activeStreamsCountIncr();
      delegateCall.start(listener, headers);
    }

    @Override
    public void cancel(String message, Throwable cause) {
      if (!decremented.getAndSet(true)) {
        channelRef.activeStreamsCountDecr();
      }
      delegateCall.cancel(message, cause);
    }
  }
}
