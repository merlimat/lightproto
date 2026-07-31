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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.pulsar.common.api.proto.BaseCommand;
import org.apache.pulsar.common.api.proto.PulsarApi;
import org.junit.jupiter.api.Test;

/**
 * Messages larger than {@code SCRATCH_RETAIN_MAX} must serialize to non-array
 * buffers through the ByteBuf API — byte-identical to the scratch path, and
 * without allocating a transient full-size heap array per write (the 0.8.0
 * regression that OOMed Pulsar's proxy back-pressure test).
 */
public class LargeWriteThroughTest {

    /**
     * ~4.5 MB of repeated strings (8192 names, ~550 chars each), mirroring the
     * Pulsar CommandGetTopicsOfNamespaceResponse shape that exposed the
     * regression. Every 100th name carries non-ASCII characters so the UTF-8
     * write path is exercised at scale.
     */
    private static S largeStrings() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            sb.append("persistent://public/default/large-write-through-").append(i).append('/');
        }
        String base = sb.toString();
        S s = new S().setId("large");
        for (int i = 0; i < 8192; i++) {
            s.addName(base + (i % 100 == 0 ? "-λ∞≈-" : "-") + i);
        }
        return s;
    }

    private static byte[] drain(ByteBuf b) {
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        return out;
    }

    private static byte[] scratchOf(Object msg) throws Exception {
        Field f = msg.getClass().getDeclaredField("_scratch");
        f.setAccessible(true);
        return (byte[]) f.get(msg);
    }

    @Test
    public void testLargeRepeatedStringsAllTargets() throws Exception {
        S s = largeStrings();
        byte[] expected = s.toByteArray();
        assertTrue(expected.length > LightProtoCodec.SCRATCH_RETAIN_MAX);

        Strings.S.Builder pb = Strings.S.newBuilder().setId("large");
        for (int i = 0; i < s.getNamesCount(); i++) {
            pb.addNames(s.getNameAt(i));
        }
        assertArrayEquals(expected, pb.build().toByteArray());

        ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(expected.length);
        try {
            assertEquals(expected.length, s.writeTo(direct));
            assertArrayEquals(expected, drain(direct));

            // Re-serialization must reuse the cached sizes and stay identical
            direct.clear();
            s.writeTo(direct);
            assertArrayEquals(expected, drain(direct));
        } finally {
            direct.release();
        }

        CompositeByteBuf composite = Unpooled.compositeBuffer();
        try {
            s.writeTo(composite);
            assertArrayEquals(expected, drain(composite));
        } finally {
            composite.release();
        }

        // A parsed message writes through the lazy-string passthrough branch
        S parsed = new S();
        parsed.parseFrom(expected);
        ByteBuf direct2 = PooledByteBufAllocator.DEFAULT.directBuffer(expected.length);
        try {
            parsed.writeTo(direct2);
            assertArrayEquals(expected, drain(direct2));
        } finally {
            direct2.release();
        }
    }

    @Test
    public void testLargeBytesFieldWriteThrough() throws Exception {
        byte[] payload = new byte[2 * 1024 * 1024];
        new Random(42).nextBytes(payload);
        B b = new B().setPayload(payload);
        b.addExtraItem(new byte[]{1, 2, 3});
        b.addExtraItem(new byte[]{4, 5});

        byte[] expected = b.toByteArray();
        assertTrue(expected.length > LightProtoCodec.SCRATCH_RETAIN_MAX);

        ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(expected.length);
        try {
            b.writeTo(direct);
            assertArrayEquals(expected, drain(direct));

            // Parsed passthrough: bytes fields copy straight from the parse buffer
            B parsed = new B();
            parsed.parseFrom(expected);
            direct.clear();
            parsed.writeTo(direct);
            assertArrayEquals(expected, drain(direct));
        } finally {
            direct.release();
        }
    }

    @Test
    public void testLargeNestedMessageTreeWriteThrough() {
        M m = new M();
        m.setX().setA("a-value").setB("b-value");
        for (int i = 0; i < 9000; i++) {
            M.KV kv = m.addItem();
            kv.setK("key-" + "k".repeat(60) + "-" + i);
            kv.setV("val-" + "v".repeat(60) + "-" + i);
            if (i % 10 == 0) {
                kv.setXx().setN(i);
            }
        }

        byte[] expected = m.toByteArray();
        assertTrue(expected.length > LightProtoCodec.SCRATCH_RETAIN_MAX);

        ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(expected.length);
        try {
            m.writeTo(direct);
            assertArrayEquals(expected, drain(direct));
        } finally {
            direct.release();
        }
    }

    @Test
    public void testPulsarBaseCommandShapeWriteThrough() {
        // The exact production shape: a BaseCommand wrapping a multi-MB
        // CommandGetTopicsOfNamespaceResponse. BaseCommand also exercises the
        // bit-driven traversal variant of the write-through path.
        List<String> topics = new ArrayList<>();
        String base = "persistent://public/default/" + "t".repeat(520) + "-";
        for (int i = 0; i < 8192; i++) {
            topics.add(base + i);
        }

        BaseCommand cmd = new BaseCommand().setType(BaseCommand.Type.GET_TOPICS_OF_NAMESPACE_RESPONSE);
        cmd.setGetTopicsOfNamespaceResponse().setRequestId(42).addAllTopics(topics);

        PulsarApi.BaseCommand pb = PulsarApi.BaseCommand.newBuilder()
                .setType(PulsarApi.BaseCommand.Type.GET_TOPICS_OF_NAMESPACE_RESPONSE)
                .setGetTopicsOfNamespaceResponse(PulsarApi.CommandGetTopicsOfNamespaceResponse.newBuilder()
                        .setRequestId(42)
                        .addAllTopics(topics))
                .build();

        byte[] expected = pb.toByteArray();
        assertTrue(expected.length > LightProtoCodec.SCRATCH_RETAIN_MAX);
        assertEquals(expected.length, cmd.getSerializedSize());

        ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(expected.length);
        try {
            cmd.writeTo(direct);
            assertArrayEquals(expected, drain(direct));
        } finally {
            direct.release();
        }
    }

    @Test
    public void testWriteThroughAllocationBoundBytes() throws Exception {
        // Bytes fields transfer with bulk getBytes() in every JVM config, so
        // this asserts the core property unconditionally: no transient
        // full-size heap array per write.
        byte[] payload = new byte[5 * 1024 * 1024];
        new Random(7).nextBytes(payload);
        B b = new B().setPayload(payload);
        assertAllocationFreeWrites(b, b.getSerializedSize());
    }

    @Test
    public void testWriteThroughAllocationBoundStrings() throws Exception {
        // With -XX:-CompactStrings both write paths copy each string's bytes
        // through a temporary array (writeString/writeRawString cannot read the
        // String's internal byte[]), so the string-heavy variant of this
        // assertion only holds under the default compact-strings config.
        assumeTrue(!ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:-CompactStrings"));

        S s = largeStrings();
        assertAllocationFreeWrites(s, s.getSerializedSize());
    }

    private static void assertAllocationFreeWrites(LightProtoCodec.LightProtoMessage msg, int size)
            throws Exception {
        var mxBean = ManagementFactory.getThreadMXBean();
        assumeTrue(mxBean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean tb = (com.sun.management.ThreadMXBean) mxBean;
        assumeTrue(tb.isThreadAllocatedMemorySupported());
        if (!tb.isThreadAllocatedMemoryEnabled()) {
            tb.setThreadAllocatedMemoryEnabled(true);
        }

        assertTrue(size > LightProtoCodec.SCRATCH_RETAIN_MAX);

        ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(size + 64);
        try {
            for (int i = 0; i < 3; i++) {
                direct.clear();
                msg.writeTo(direct);
            }

            long tid = Thread.currentThread().getId();
            long before = tb.getThreadAllocatedBytes(tid);
            for (int i = 0; i < 5; i++) {
                direct.clear();
                msg.writeTo(direct);
            }
            long allocated = tb.getThreadAllocatedBytes(tid) - before;

            // Staging would allocate a fresh full-size array per write (5x the
            // message size over this loop); the write-through path allocates
            // none of that. The bound leaves generous slack for JIT/runtime
            // noise.
            assertTrue(allocated < size / 4,
                    "expected allocation-free serialization but " + allocated
                            + " bytes were allocated for 5 writes of a " + size + "-byte message");
            // The scratch path was never taken for this outlier message
            assertNull(scratchOf(msg));
        } finally {
            direct.release();
        }
    }

    @Test
    public void testScratchStillUsedBelowThreshold() throws Exception {
        // ~600 KB: stays on the scratch fast path and retains the array
        S s = new S().setId("medium");
        for (int i = 0; i < 1200; i++) {
            s.addName("x".repeat(500));
        }
        byte[] expected = s.toByteArray();
        assertTrue(expected.length <= LightProtoCodec.SCRATCH_RETAIN_MAX);

        ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(expected.length);
        try {
            s.writeTo(direct);
            assertArrayEquals(expected, drain(direct));
        } finally {
            direct.release();
        }

        byte[] scratch = scratchOf(s);
        assertNotNull(scratch);
        assertTrue(scratch.length <= LightProtoCodec.SCRATCH_RETAIN_MAX);
    }
}
