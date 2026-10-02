/**
 * Copyright 2026 StreamNative
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.lightproto.tests;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sends small and large messages through every RPC type over the in-process transport, which
 * reads the marshalled stream, and over Netty on localhost, which drains it, with and without
 * compression.
 */
public class GrpcTransportTest {

    enum Transport { IN_PROCESS, NETTY, NETTY_GZIP }

    // 2 MiB is above gRPC's 1 MiB transport buffers, so the message is drained across
    // several of them
    private static final int[] PAYLOAD_SIZES = {0, 100, 128 * 1024, 2 * 1024 * 1024};
    private static final int LARGE_PAYLOAD = 128 * 1024;
    private static final int STREAMED_MESSAGES = 3;

    private Transport transport;
    private Server server;
    private ManagedChannel channel;

    static Stream<Arguments> transportsAndPayloadSizes() {
        List<Arguments> args = new ArrayList<>();
        for (Transport transport : Transport.values()) {
            for (int size : PAYLOAD_SIZES) {
                args.add(Arguments.of(transport, size));
            }
        }
        return args.stream();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @MethodSource("transportsAndPayloadSizes")
    void testUnary(Transport transport, int payloadSize) throws Exception {
        start(transport);
        GrpcRequest request = request("unary", payloadSize);

        GrpcResponse response = blockingStub().unary(request);
        assertEquals("echo: unary", response.getMessage());
        assertEquals(payloadSize * 2, response.getResult());
        assertArrayEquals(request.getPayload(), response.getPayload());
    }

    @ParameterizedTest
    @MethodSource("transportsAndPayloadSizes")
    void testServerStreaming(Transport transport, int payloadSize) throws Exception {
        start(transport);
        GrpcRequest request = request("server-stream", payloadSize);

        Iterator<GrpcResponse> responses = blockingStub().serverStream(request);
        for (int i = 0; i < STREAMED_MESSAGES; i++) {
            GrpcResponse response = responses.next();
            assertEquals(i, response.getResult());
            assertArrayEquals(request.getPayload(), response.getPayload());
        }
        assertFalse(responses.hasNext());
    }

    @ParameterizedTest
    @MethodSource("transportsAndPayloadSizes")
    void testClientStreaming(Transport transport, int payloadSize) throws Exception {
        start(transport);
        ResponseFuture response = new ResponseFuture();
        StreamObserver<GrpcRequest> requests = asyncStub().clientStream(response);

        CRC32 crc = new CRC32();
        for (int i = 0; i < STREAMED_MESSAGES; i++) {
            GrpcRequest request = request("client-stream-" + i, payloadSize);
            crc.update(request.getPayload());
            requests.onNext(request);
        }
        requests.onCompleted();

        List<GrpcResponse> received = response.get(30, TimeUnit.SECONDS);
        assertEquals(1, received.size());
        assertEquals("received " + STREAMED_MESSAGES + " messages", received.get(0).getMessage());
        assertEquals((int) crc.getValue(), received.get(0).getResult());
    }

    @ParameterizedTest
    @MethodSource("transportsAndPayloadSizes")
    void testBidiStreaming(Transport transport, int payloadSize) throws Exception {
        start(transport);
        ResponseFuture response = new ResponseFuture();
        StreamObserver<GrpcRequest> requests = asyncStub().bidiStream(response);

        List<GrpcRequest> sent = new ArrayList<>();
        for (int i = 0; i < STREAMED_MESSAGES; i++) {
            GrpcRequest request = request("bidi-" + i, payloadSize);
            sent.add(request);
            requests.onNext(request);
        }
        requests.onCompleted();

        List<GrpcResponse> received = response.get(30, TimeUnit.SECONDS);
        assertEquals(STREAMED_MESSAGES, received.size());
        for (int i = 0; i < STREAMED_MESSAGES; i++) {
            assertEquals("echo: bidi-" + i, received.get(i).getMessage());
            assertArrayEquals(sent.get(i).getPayload(), received.get(i).getPayload());
        }
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void testCallsThatDropTheirStreams(Transport transport) throws Exception {
        start(transport);
        GrpcRequest request = request("dropped", LARGE_PAYLOAD);

        // The deadline has passed, so gRPC serializes the request but writes it to a
        // FailingClientStream instead of a transport
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> blockingStub().withDeadlineAfter(-1, TimeUnit.SECONDS).unary(request));
        assertEquals(Status.Code.DEADLINE_EXCEEDED, e.getStatus().getCode());

        // The request is queued while the channel connects; cancelling the call hands it
        // to NoopClientStream
        ManagedChannel unconnectable = unconnectableChannel();
        try {
            ResponseFuture response = new ResponseFuture();
            ClientCall<GrpcRequest, GrpcResponse> call = unconnectable.newCall(
                    TestServiceGrpc.getUnaryMethod(), CallOptions.DEFAULT.withWaitForReady());
            call.start(response.asListener(), new Metadata());
            call.sendMessage(request);
            call.cancel("dropping the queued request", null);
            assertEquals(Status.Code.CANCELLED, response.awaitStatus(30, TimeUnit.SECONDS).getCode());
        } finally {
            unconnectable.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        }

        // However those streams ended, this thread's next message must go out intact
        GrpcRequest next = request("next", LARGE_PAYLOAD);
        assertArrayEquals(next.getPayload(), blockingStub().unary(next).getPayload());
    }

    @ParameterizedTest
    @EnumSource(value = Transport.class, names = {"NETTY", "NETTY_GZIP"})
    void testStreamedMessagesDoNotAllocateTheirSerializedSize(Transport transport) throws Exception {
        // Over Netty, gRPC drains and closes each stream inside onNext(), so every message
        // after the first is serialized into the array the previous one handed back.
        // (The in-process transport closes the stream on the receiving thread instead.)
        start(transport);
        GrpcRequest request = request("streamed", LARGE_PAYLOAD);
        blockingStub().unary(request("connect", 0));

        ResponseFuture response = new ResponseFuture();
        StreamObserver<GrpcRequest> requests = asyncStub().clientStream(response);
        requests.onNext(request);

        int messages = 20;
        long before = allocatedBytes();
        for (int i = 0; i < messages; i++) {
            requests.onNext(request);
        }
        long perMessage = (allocatedBytes() - before) / messages;
        requests.onCompleted();
        response.get(30, TimeUnit.SECONDS);

        assertTrue(perMessage < LARGE_PAYLOAD / 4,
                "Allocated " + perMessage + " bytes per " + request.getSerializedSize() + "-byte message");
    }

    @ParameterizedTest
    @EnumSource(value = Transport.class, names = {"NETTY", "NETTY_GZIP"})
    void testFailedSendHandsBackItsArray(Transport transport) throws Exception {
        start(transport);
        GrpcRequest request = request("failing", LARGE_PAYLOAD);
        blockingStub().unary(request("connect", 0));

        ResponseFuture response = new ResponseFuture();
        StreamObserver<GrpcRequest> requests = asyncStub().clientStream(response);
        requests.onNext(request);

        // The message is above the call's limit: gRPC fails it before draining it, or after
        // compressing it, and closes the stream either way
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> blockingStub().withMaxOutboundMessageSize(1024).unary(request));
        assertEquals(Status.Code.CANCELLED, e.getStatus().getCode());
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, Status.fromThrowable(e.getCause()).getCode());

        long before = allocatedBytes();
        requests.onNext(request);
        long allocated = allocatedBytes() - before;
        requests.onCompleted();
        response.get(30, TimeUnit.SECONDS);

        assertTrue(allocated < LARGE_PAYLOAD / 4,
                "Allocated " + allocated + " bytes for a " + request.getSerializedSize() + "-byte message");
    }

    private void start(Transport transport) throws IOException {
        this.transport = transport;
        if (transport == Transport.IN_PROCESS) {
            String name = InProcessServerBuilder.generateName();
            server = InProcessServerBuilder.forName(name)
                    .addService(new TestServiceImpl())
                    .build()
                    .start();
            channel = InProcessChannelBuilder.forName(name).build();
        } else {
            server = NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                    .addService(transport == Transport.NETTY_GZIP
                            ? ServerInterceptors.intercept(new TestServiceImpl(), new GzipResponses())
                            : new TestServiceImpl().bindService())
                    .build()
                    .start();
            channel = NettyChannelBuilder.forAddress(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()))
                    .usePlaintext()
                    .build();
        }
    }

    private ManagedChannel unconnectableChannel() throws IOException {
        if (transport == Transport.IN_PROCESS) {
            return InProcessChannelBuilder.forName(InProcessServerBuilder.generateName()).build();
        }
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unusedPort = socket.getLocalPort();
        }
        return NettyChannelBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), unusedPort))
                .usePlaintext()
                .build();
    }

    private TestServiceGrpc.TestServiceBlockingStub blockingStub() {
        TestServiceGrpc.TestServiceBlockingStub stub = TestServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(30, TimeUnit.SECONDS);
        return transport == Transport.NETTY_GZIP ? stub.withCompression("gzip") : stub;
    }

    private TestServiceGrpc.TestServiceStub asyncStub() {
        TestServiceGrpc.TestServiceStub stub = TestServiceGrpc.newStub(channel)
                .withDeadlineAfter(30, TimeUnit.SECONDS);
        return transport == Transport.NETTY_GZIP ? stub.withCompression("gzip") : stub;
    }

    private static GrpcRequest request(String name, int payloadSize) {
        byte[] payload = new byte[payloadSize];
        new Random(name.hashCode()).nextBytes(payload);
        return new GrpcRequest().setName(name).setValue(payloadSize).setPayload(payload);
    }

    private static long allocatedBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getCurrentThreadAllocatedBytes();
    }

    /** Collects the responses of a call, completing when the call ends. */
    private static final class ResponseFuture extends CompletableFuture<List<GrpcResponse>>
            implements StreamObserver<GrpcResponse> {
        private final List<GrpcResponse> responses = new ArrayList<>();
        private final CompletableFuture<Status> status = new CompletableFuture<>();

        @Override
        public synchronized void onNext(GrpcResponse value) {
            responses.add(value);
        }

        @Override
        public void onError(Throwable t) {
            status.complete(Status.fromThrowable(t));
            completeExceptionally(t);
        }

        @Override
        public synchronized void onCompleted() {
            status.complete(Status.OK);
            complete(responses);
        }

        ClientCall.Listener<GrpcResponse> asListener() {
            return new ClientCall.Listener<GrpcResponse>() {
                @Override
                public void onMessage(GrpcResponse message) {
                    ResponseFuture.this.onNext(message);
                }

                @Override
                public void onClose(Status status, Metadata trailers) {
                    if (status.isOk()) {
                        ResponseFuture.this.onCompleted();
                    } else {
                        ResponseFuture.this.onError(status.asRuntimeException(trailers));
                    }
                }
            };
        }

        Status awaitStatus(long timeout, TimeUnit unit) throws Exception {
            return status.get(timeout, unit);
        }
    }

    private static final class GzipResponses implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            call.setCompression("gzip");
            return next.startCall(call, headers);
        }
    }

    private static final class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {
        @Override
        public void unary(GrpcRequest request, StreamObserver<GrpcResponse> responseObserver) {
            responseObserver.onNext(new GrpcResponse()
                    .setMessage("echo: " + request.getName())
                    .setResult(request.getValue() * 2)
                    .setPayload(request.getPayload()));
            responseObserver.onCompleted();
        }

        @Override
        public void serverStream(GrpcRequest request, StreamObserver<GrpcResponse> responseObserver) {
            for (int i = 0; i < STREAMED_MESSAGES; i++) {
                responseObserver.onNext(new GrpcResponse()
                        .setMessage(request.getName() + "-" + i)
                        .setResult(i)
                        .setPayload(request.getPayload()));
            }
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<GrpcRequest> clientStream(StreamObserver<GrpcResponse> responseObserver) {
            return new StreamObserver<GrpcRequest>() {
                private final CRC32 crc = new CRC32();
                private int count;

                @Override
                public void onNext(GrpcRequest value) {
                    count++;
                    crc.update(value.getPayload());
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                    responseObserver.onNext(new GrpcResponse()
                            .setMessage("received " + count + " messages")
                            .setResult((int) crc.getValue()));
                    responseObserver.onCompleted();
                }
            };
        }

        @Override
        public StreamObserver<GrpcRequest> bidiStream(StreamObserver<GrpcResponse> responseObserver) {
            return new StreamObserver<GrpcRequest>() {
                @Override
                public void onNext(GrpcRequest value) {
                    responseObserver.onNext(new GrpcResponse()
                            .setMessage("echo: " + value.getName())
                            .setPayload(value.getPayload()));
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }
}
