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
package io.streamnative.lightproto.benchmark;

import io.grpc.MethodDescriptor;
import io.grpc.internal.CompositeReadableBuffer;
import io.grpc.internal.ReadableBuffers;
import io.streamnative.lightproto.tests.EchoServiceGrpc;
import io.streamnative.lightproto.tests.GrpcPayload;
import io.streamnative.lightproto.tests.GrpcPayloadItem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Parsing a message from the stream gRPC transports pass to the marshaller: a
 * {@code ReadableBuffers.BufferInputStream} over the buffers the message was received in, here
 * direct memory like the pooled buffers of Netty. Compares the generated marshaller with the
 * copies that parse() made before: readAllBytes(), then an exact-size array for messages spread
 * over several buffers. Run with {@code -prof gc} and compare gc.alloc.rate.norm for the bytes
 * allocated per message.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 3)
public class GrpcParseBenchmark {

    private static final MethodDescriptor.Marshaller<GrpcPayload> MARSHALLER =
            EchoServiceGrpc.getEchoMethod().getRequestMarshaller();

    /** Size of the bytes field, or of all the records. */
    @Param({"100", "4096", "65536"})
    public int size;

    /** Number of transport buffers the message is spread over. */
    @Param({"1", "4"})
    public int buffers;

    /** One bytes field, or records of 64 bytes with small fields, as in a range scan response. */
    @Param({"value", "records"})
    public String shape;

    /**
     * How the bytes fields are read: as slices, or as arrays, which the getters return without a
     * copy for a materialized message.
     */
    @Param({"slice", "array"})
    public String access;

    private ByteBuffer[] memory;
    private boolean arrays;

    @Setup
    public void setup() {
        arrays = access.equals("array");
        Random random = new Random(42);
        GrpcPayload payload = new GrpcPayload().setName("/benchmark/key");
        if (shape.equals("value")) {
            byte[] data = new byte[size];
            random.nextBytes(data);
            payload.setData(data);
        } else {
            for (int i = 0; i < Math.max(1, size / 64); i++) {
                byte[] value = new byte[32];
                random.nextBytes(value);
                payload.addItem().setKey(String.format("/key/%010d", i)).setValue(value).setVersion(random.nextLong());
            }
        }
        byte[] serialized = payload.toByteArray();
        memory = new ByteBuffer[buffers];
        int pieceSize = (serialized.length + buffers - 1) / buffers;
        for (int i = 0; i < buffers; i++) {
            int offset = i * pieceSize;
            int length = Math.min(pieceSize, serialized.length - offset);
            memory[i] = ByteBuffer.allocateDirect(length);
            memory[i].put(serialized, offset, length).flip();
        }
    }

    private InputStream openStream() {
        CompositeReadableBuffer composite = new CompositeReadableBuffer();
        for (ByteBuffer piece : memory) {
            composite.addBuffer(ReadableBuffers.wrap(piece.duplicate()));
        }
        return ReadableBuffers.openStream(composite, true);
    }

    /** The parse() generated before: the whole stream copied through readAllBytes(). */
    @Benchmark
    public void readAllBytes(Blackhole bh) throws IOException {
        try (InputStream stream = openStream()) {
            GrpcPayload msg = new GrpcPayload();
            msg.parseFrom(stream.readAllBytes());
            consume(bh, msg);
        }
    }

    /** The exact-size copy that parse() made of a message spread over several buffers. */
    @Benchmark
    public void readNBytes(Blackhole bh) throws IOException {
        try (InputStream stream = openStream()) {
            int length = stream.available();
            byte[] bytes = new byte[length];
            stream.readNBytes(bytes, 0, length);
            GrpcPayload msg = new GrpcPayload();
            msg.parseFrom(bytes);
            consume(bh, msg);
        }
    }

    @Benchmark
    public void marshaller(Blackhole bh) {
        consume(bh, MARSHALLER.parse(openStream()));
    }

    private void consume(Blackhole bh, GrpcPayload msg) {
        // Reading the fields also decodes those that the copying paths leave lazy
        bh.consume(msg.getName());
        bh.consume(arrays ? msg.getData() : msg.getDataSlice());
        for (int i = 0; i < msg.getItemsCount(); i++) {
            GrpcPayloadItem item = msg.getItemAt(i);
            bh.consume(item.getKey());
            bh.consume(arrays ? item.getValue() : item.getValueSlice());
            bh.consume(item.getVersion());
        }
    }
}
