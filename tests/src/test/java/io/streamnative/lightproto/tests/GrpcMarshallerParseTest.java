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

import io.grpc.MethodDescriptor;
import io.grpc.internal.CompositeReadableBuffer;
import io.grpc.internal.ForwardingReadableBuffer;
import io.grpc.internal.ReadableBuffer;
import io.grpc.internal.ReadableBuffers;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Parses with the generated marshaller from the stream gRPC transports pass to parse():
 * a {@code ReadableBuffers.BufferInputStream} over the buffers holding the message, assembled
 * with the same gRPC classes the deframer uses, from buffers that record how they are read.
 */
public class GrpcMarshallerParseTest {

    private static final MethodDescriptor.Marshaller<GrpcPayload> MARSHALLER =
            EchoServiceGrpc.getEchoMethod().getRequestMarshaller();

    @ParameterizedTest
    @ValueSource(ints = {100, 64 * 1024})
    void testMessageInOneBufferIsParsedInPlace(int dataSize) {
        GrpcPayload expected = GrpcPayloads.create(dataSize, dataSize);
        List<TrackingBuffer> buffers = track(split(expected.toByteArray(), 1), true);

        GrpcPayload parsed = MARSHALLER.parse(openStream(buffers));

        TrackingBuffer buffer = buffers.get(0);
        assertEquals(0, buffer.bytesRead, "The message should not be copied out of the buffer");
        assertEquals(1, buffer.closeCount);
        // close() overwrote the buffer, so the message must no longer refer to it
        assertEquals(expected, parsed);
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 64 * 1024})
    void testMessageAcrossBuffersIsCopiedInOnePass(int dataSize) {
        GrpcPayload expected = GrpcPayloads.create(dataSize, dataSize);
        List<TrackingBuffer> buffers = track(split(expected.toByteArray(), 3), true);

        GrpcPayload parsed = MARSHALLER.parse(openStream(buffers));

        for (TrackingBuffer buffer : buffers) {
            assertEquals(1, buffer.readCalls, "Each buffer should be read by a single call");
            assertEquals(buffer.size, buffer.bytesRead);
            assertEquals(1, buffer.closeCount);
        }
        assertEquals(expected, parsed);
    }

    @Test
    void testBufferWithoutByteBufferSupportIsCopiedInOnePass() {
        GrpcPayload expected = GrpcPayloads.create(1, 100);
        List<TrackingBuffer> buffers = track(split(expected.toByteArray(), 1), false);

        GrpcPayload parsed = MARSHALLER.parse(openStream(buffers));

        TrackingBuffer buffer = buffers.get(0);
        assertEquals(1, buffer.readCalls);
        assertEquals(buffer.size, buffer.bytesRead);
        assertEquals(expected, parsed);
    }

    @Test
    void testEmptyMessage() {
        assertEquals(new GrpcPayload(), MARSHALLER.parse(openStream(List.of())));
    }

    @Test
    void testOtherStreamTypes() {
        GrpcPayload expected = GrpcPayloads.create(2, 100);
        byte[] serialized = expected.toByteArray();

        // The marshaller's own stream, which the in-process transport passes along
        assertEquals(expected, MARSHALLER.parse(MARSHALLER.stream(expected)));
        // A stream of unknown length
        assertEquals(expected, MARSHALLER.parse(new BufferedInputStream(new ByteArrayInputStream(serialized))));
        // A Netty ByteBufInputStream
        assertEquals(expected,
                MARSHALLER.parse(new ByteBufInputStream(Unpooled.wrappedBuffer(serialized), true)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void testSmallMessageAllocation(int pieces) {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());

        MethodDescriptor.Marshaller<GrpcRequest> marshaller =
                TestServiceGrpc.getUnaryMethod().getRequestMarshaller();
        List<ByteBuffer> memory = split(new GrpcRequest().setName("hello").setValue(42).toByteArray(), pieces);

        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            marshaller.parse(openStream(wrap(memory)));
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            assertEquals(42, marshaller.parse(openStream(wrap(memory))).getValue());
        }
        long perMessage = (threads.getCurrentThreadAllocatedBytes() - before) / iterations;

        // readAllBytes() allocated an 8 or 16 KiB chunk per message, depending on the JDK
        assertTrue(perMessage < 4096, "Allocated " + perMessage + " bytes per message");
    }

    /** Splits a message into pieces of direct memory, like the pooled buffers of Netty. */
    private static List<ByteBuffer> split(byte[] message, int pieces) {
        List<ByteBuffer> memory = new ArrayList<>();
        int pieceSize = (message.length + pieces - 1) / pieces;
        for (int offset = 0; offset < message.length; offset += pieceSize) {
            int length = Math.min(pieceSize, message.length - offset);
            ByteBuffer piece = ByteBuffer.allocateDirect(length);
            piece.put(message, offset, length).flip();
            memory.add(piece);
        }
        return memory;
    }

    private static List<TrackingBuffer> track(List<ByteBuffer> memory, boolean byteBufferSupported) {
        List<TrackingBuffer> buffers = new ArrayList<>();
        for (ByteBuffer piece : memory) {
            buffers.add(new TrackingBuffer(piece, byteBufferSupported));
        }
        return buffers;
    }

    private static List<ReadableBuffer> wrap(List<ByteBuffer> memory) {
        List<ReadableBuffer> buffers = new ArrayList<>();
        for (ByteBuffer piece : memory) {
            buffers.add(ReadableBuffers.wrap(piece.duplicate()));
        }
        return buffers;
    }

    private static InputStream openStream(List<? extends ReadableBuffer> buffers) {
        // The deframer collects the buffers of each message in a CompositeReadableBuffer
        CompositeReadableBuffer composite = new CompositeReadableBuffer();
        buffers.forEach(composite::addBuffer);
        return ReadableBuffers.openStream(composite, true);
    }

    /**
     * A transport buffer that records the bytes copied out of it, and that overwrites its memory
     * on close(), as a pooled transport buffer does once it is reused.
     */
    private static final class TrackingBuffer extends ForwardingReadableBuffer {
        final ByteBuffer memory;
        final int size;
        final boolean byteBufferSupported;
        int readCalls;
        int bytesRead;
        int closeCount;

        TrackingBuffer(ByteBuffer memory, boolean byteBufferSupported) {
            super(ReadableBuffers.wrap(memory.duplicate()));
            this.memory = memory;
            this.size = memory.remaining();
            this.byteBufferSupported = byteBufferSupported;
        }

        @Override
        public int readUnsignedByte() {
            bytesRead++;
            return super.readUnsignedByte();
        }

        @Override
        public void readBytes(byte[] dest, int destOffset, int length) {
            readCalls++;
            bytesRead += length;
            super.readBytes(dest, destOffset, length);
        }

        @Override
        public boolean byteBufferSupported() {
            return byteBufferSupported;
        }

        @Override
        public void close() {
            closeCount++;
            for (int i = 0; i < memory.capacity(); i++) {
                memory.put(i, (byte) 0xff);
            }
            super.close();
        }
    }
}
