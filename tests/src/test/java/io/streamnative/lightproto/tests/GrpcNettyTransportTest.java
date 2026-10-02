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
import io.grpc.HasByteBuffer;
import io.grpc.KnownLength;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the generated stubs over a real Netty transport on localhost. Unlike the in-process
 * transport, which passes the stream returned by stream() straight to parse(), it hands parse()
 * a stream over the transport buffers the message was received in.
 */
public class GrpcNettyTransportTest {

    private static final String ONE_BUFFER = "one buffer";
    private static final String SEVERAL_BUFFERS = "several buffers";

    private final List<GrpcPayload> serverReceived = new CopyOnWriteArrayList<>();
    private final InspectingMarshaller responseMarshaller =
            new InspectingMarshaller(EchoServiceGrpc.getEchoMethod().getResponseMarshaller());
    private final MethodDescriptor<GrpcPayload, GrpcPayload> inspectedEchoMethod = EchoServiceGrpc.getEchoMethod()
            .toBuilder(EchoServiceGrpc.getEchoMethod().getRequestMarshaller(), responseMarshaller)
            .build();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        server = NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .addService(new EchoServiceImpl())
                .build()
                .start();
        channel = NettyChannelBuilder.forAddress(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()))
                .usePlaintext()
                .build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    void testSmallMessages() {
        List<GrpcPayload> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            GrpcPayload request = GrpcPayloads.create(i, 100);
            sent.add(request);
            assertEquals(request, echo(request));
        }
        assertEquals(sent, serverReceived);
        // Each response was received whole in a single buffer, which parse() reads in place
        assertEquals(Collections.nCopies(sent.size(), ONE_BUFFER), responseMarshaller.shapes);
    }

    @Test
    void testMessagesLargerThanAFrame() {
        List<GrpcPayload> sent = new ArrayList<>();
        for (int dataSize : new int[] {16 * 1024, 64 * 1024, 1024 * 1024}) {
            GrpcPayload request = GrpcPayloads.create(dataSize, dataSize);
            sent.add(request);
            assertEquals(request, echo(request));
        }
        assertEquals(sent, serverReceived);
        // HTTP/2 DATA frames carry at most 16 KiB by default, so each response spans several buffers
        assertEquals(Collections.nCopies(sent.size(), SEVERAL_BUFFERS), responseMarshaller.shapes);
    }

    @Test
    void testMessagesOutliveTheirTransportBuffers() throws Exception {
        // The pooled buffers that early messages were parsed from get reused for later ones,
        // which would corrupt any field that still referred to them
        int[] dataSizes = {10, 100, 1000, 20 * 1024};
        List<GrpcPayload> sent = new ArrayList<>();
        List<GrpcPayload> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        StreamObserver<GrpcPayload> requests = EchoServiceGrpc.newStub(channel)
                .echoStream(new StreamObserver<GrpcPayload>() {
                    @Override
                    public void onNext(GrpcPayload value) {
                        received.add(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        done.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                        done.complete(null);
                    }
                });
        for (int i = 0; i < 200; i++) {
            GrpcPayload request = GrpcPayloads.create(i, dataSizes[i % dataSizes.length]);
            sent.add(request);
            requests.onNext(request);
        }
        requests.onCompleted();
        done.get(30, TimeUnit.SECONDS);

        assertEquals(sent.size(), serverReceived.size());
        assertEquals(sent.size(), received.size());
        for (int i = 0; i < sent.size(); i++) {
            assertEquals(sent.get(i), serverReceived.get(i), "Request " + i);
            assertEquals(sent.get(i), received.get(i), "Response " + i);
        }
    }

    private GrpcPayload echo(GrpcPayload request) {
        return ClientCalls.blockingUnaryCall(channel, inspectedEchoMethod,
                CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS), request);
    }

    /** Records how the transport hands each message to parse(), then parses it with the generated marshaller. */
    private static final class InspectingMarshaller implements MethodDescriptor.Marshaller<GrpcPayload> {
        private final MethodDescriptor.Marshaller<GrpcPayload> delegate;
        final List<String> shapes = new CopyOnWriteArrayList<>();

        InspectingMarshaller(MethodDescriptor.Marshaller<GrpcPayload> delegate) {
            this.delegate = delegate;
        }

        @Override
        public InputStream stream(GrpcPayload value) {
            return delegate.stream(value);
        }

        @Override
        public GrpcPayload parse(InputStream stream) {
            shapes.add(shapeOf(stream));
            return delegate.parse(stream);
        }

        private static String shapeOf(InputStream stream) {
            if (!(stream instanceof KnownLength && stream instanceof HasByteBuffer
                    && ((HasByteBuffer) stream).byteBufferSupported())) {
                return stream.getClass().getName();
            }
            try {
                ByteBuffer first = ((HasByteBuffer) stream).getByteBuffer();
                return first != null && first.remaining() == stream.available() ? ONE_BUFFER : SEVERAL_BUFFERS;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private final class EchoServiceImpl extends EchoServiceGrpc.EchoServiceImplBase {
        @Override
        public void echo(GrpcPayload request, StreamObserver<GrpcPayload> responseObserver) {
            serverReceived.add(request);
            responseObserver.onNext(request);
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<GrpcPayload> echoStream(StreamObserver<GrpcPayload> responseObserver) {
            return new StreamObserver<GrpcPayload>() {
                @Override
                public void onNext(GrpcPayload value) {
                    serverReceived.add(value);
                    responseObserver.onNext(value);
                }

                @Override
                public void onError(Throwable t) {
                    responseObserver.onError(t);
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }
}
