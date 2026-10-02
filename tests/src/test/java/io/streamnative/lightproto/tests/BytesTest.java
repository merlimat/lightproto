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

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class BytesTest {

    private byte[] b1 = new byte[4096];
    private ByteBuf bb1 = Unpooled.wrappedBuffer(b1);

    private byte[] b2 = new byte[4096];

    @BeforeEach
    public void setup() {
        bb1.clear();
        Arrays.fill(b1, (byte) 0);
        Arrays.fill(b2, (byte) 0);
    }

    @Test
    public void testBytes() throws Exception {
        B lpb = new B()
                .setPayload(new byte[]{1, 2, 3});

        assertTrue(lpb.hasPayload());
        assertEquals(3, lpb.getPayloadSize());
        assertArrayEquals(new byte[]{1, 2, 3}, lpb.getPayload());

        Bytes.B pbb = Bytes.B.newBuilder()
                .setPayload(ByteString.copyFrom(new byte[]{1, 2, 3}))
                .build();

        assertEquals(pbb.getSerializedSize(), lpb.getSerializedSize());
        lpb.writeTo(bb1);
        assertEquals(lpb.getSerializedSize(), bb1.readableBytes());

        pbb.writeTo(CodedOutputStream.newInstance(b2));

        assertArrayEquals(b1, b2);

        B parsed = new B();
        parsed.parseFrom(bb1, bb1.readableBytes());

        assertTrue(parsed.hasPayload());
        assertEquals(3, parsed.getPayloadSize());
        assertArrayEquals(new byte[]{1, 2, 3}, parsed.getPayload());
    }

    @Test
    public void testBytesBuf() throws Exception {
        B lpb = new B();
        ByteBuf b = Unpooled.directBuffer(3);
        b.writeBytes(new byte[]{1, 2, 3});
        lpb.setPayload(b);

        assertTrue(lpb.hasPayload());
        assertEquals(3, lpb.getPayloadSize());
        assertArrayEquals(new byte[]{1, 2, 3}, lpb.getPayload());
        assertEquals(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}), lpb.getPayloadSlice());

        Bytes.B pbb = Bytes.B.newBuilder()
                .setPayload(ByteString.copyFrom(new byte[]{1, 2, 3}))
                .build();

        assertEquals(pbb.getSerializedSize(), lpb.getSerializedSize());
        lpb.writeTo(bb1);
        assertEquals(lpb.getSerializedSize(), bb1.readableBytes());

        pbb.writeTo(CodedOutputStream.newInstance(b2));

        assertArrayEquals(b1, b2);

        B parsed = new B();
        parsed.parseFrom(bb1, bb1.readableBytes());

        assertTrue(parsed.hasPayload());
        assertEquals(3, parsed.getPayloadSize());
        assertEquals(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}), parsed.getPayloadSlice());
        assertArrayEquals(new byte[]{1, 2, 3}, parsed.getPayload());

        bb1.clear();
        parsed.writeTo(bb1);

        assertEquals(lpb.getSerializedSize(), bb1.readableBytes());
        assertArrayEquals(b1, b2);
    }

    @Test
    public void testAccessUnsetOptionalBytes() {
        B lpb = new B();
        assertFalse(lpb.hasPayload());

        // Accessing unset optional bytes should return empty defaults, not throw
        assertArrayEquals(new byte[0], lpb.getPayload());
        assertEquals(0, lpb.getPayloadSize());
        assertEquals(0, lpb.getPayloadSlice().readableBytes());
    }

    @Test
    public void testClearResetsOptionalBytesToDefault() {
        B lpb = new B();
        lpb.setPayload(new byte[]{1, 2, 3});

        assertTrue(lpb.hasPayload());
        assertEquals(3, lpb.getPayloadSize());

        lpb.clear();

        assertFalse(lpb.hasPayload());
        assertArrayEquals(new byte[0], lpb.getPayload());
        assertEquals(0, lpb.getPayloadSize());
        assertEquals(0, lpb.getPayloadSlice().readableBytes());
        assertEquals(0, lpb.getSerializedSize());
    }

    @Test
    public void testByteBufNotConsumedBySerialization() throws Exception {
        // Setting a ByteBuf-typed bytes field must not advance the source
        // buffer's readerIndex during serialization, otherwise:
        //   1. the same message can't be serialized more than once (e.g.
        //      across gRPC retries), and
        //   2. two fields backed by the same ByteBuf would clobber each other
        //      because the second field would read 0 bytes.
        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{10, 20, 30, 40});
        int readerIdxBefore = payload.readerIndex();
        int readableBytesBefore = payload.readableBytes();

        B lpb = new B().setPayload(payload);

        bb1.clear();
        lpb.writeTo(bb1);

        assertEquals(readerIdxBefore, payload.readerIndex(),
                "setX(ByteBuf) + writeTo must not consume the source buffer's readerIndex");
        assertEquals(readableBytesBefore, payload.readableBytes());

        // Serialize a second time — must produce the same wire bytes.
        ByteBuf bb2nd = Unpooled.buffer(4096);
        lpb.writeTo(bb2nd);
        assertEquals(bb1.readableBytes(), bb2nd.readableBytes());
        assertEquals(bb1, bb2nd);
    }

    @Test
    public void testByteBufWithNonZeroReaderIndex() throws Exception {
        // Setting a ByteBuf whose readerIndex > 0 should still serialize
        // the readable region correctly.
        ByteBuf raw = Unpooled.wrappedBuffer(new byte[]{99, 99, 99, 1, 2, 3});
        raw.readerIndex(3); // skip three padding bytes
        assertEquals(3, raw.readableBytes());

        B lpb = new B().setPayload(raw);
        assertArrayEquals(new byte[]{1, 2, 3}, lpb.getPayload());

        bb1.clear();
        lpb.writeTo(bb1);

        B parsed = new B();
        parsed.parseFrom(bb1, bb1.readableBytes());
        assertArrayEquals(new byte[]{1, 2, 3}, parsed.getPayload());
    }

    @Test
    public void testTwoByteBufFieldsCanShareUnderlyingBuffer() throws Exception {
        // Two bytes fields in the same message both backed by the same
        // ByteBuf reference must each serialize the full content. This
        // mirrors the bookkeeper stream-storage routing-header pattern
        // where the same key is stored as both the request key and the
        // routing header rKey.
        ByteBuf shared = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5});

        B lpb = new B().setPayload(shared);
        lpb.addExtraItem(shared);

        bb1.clear();
        lpb.writeTo(bb1);

        B parsed = new B();
        parsed.parseFrom(bb1, bb1.readableBytes());

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, parsed.getPayload());
        assertEquals(1, parsed.getExtraItemsCount());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, parsed.getExtraItemAt(0));
    }

    @Test
    public void testRepeatedBytes() throws Exception {
        B lpb = new B();
        lpb.addExtraItem(new byte[]{1, 2, 3});
        lpb.addExtraItem(new byte[]{4, 5, 6, 7});

        assertEquals(2, lpb.getExtraItemsCount());
        assertEquals(3, lpb.getExtraItemSizeAt(0));
        assertEquals(4, lpb.getExtraItemSizeAt(1));
        assertArrayEquals(new byte[]{1, 2, 3}, lpb.getExtraItemAt(0));
        assertArrayEquals(new byte[]{4, 5, 6, 7}, lpb.getExtraItemAt(1));

        Bytes.B pbb = Bytes.B.newBuilder()
                .addExtraItems(ByteString.copyFrom(new byte[]{1, 2, 3}))
                .addExtraItems(ByteString.copyFrom(new byte[]{4, 5, 6, 7}))
                .build();

        assertEquals(pbb.getSerializedSize(), lpb.getSerializedSize());
        lpb.writeTo(bb1);
        assertEquals(lpb.getSerializedSize(), bb1.readableBytes());

        pbb.writeTo(CodedOutputStream.newInstance(b2));

        assertArrayEquals(b1, b2);

        B parsed = new B();
        parsed.parseFrom(bb1, bb1.readableBytes());

        assertEquals(2, parsed.getExtraItemsCount());
        assertEquals(3, parsed.getExtraItemSizeAt(0));
        assertEquals(4, parsed.getExtraItemSizeAt(1));
        assertArrayEquals(new byte[]{1, 2, 3}, parsed.getExtraItemAt(0));
        assertArrayEquals(new byte[]{4, 5, 6, 7}, parsed.getExtraItemAt(1));
    }

    @Test
    public void testGettersShareMaterializedArrays() throws Exception {
        B lpb = new B().setPayload(new byte[]{1, 2, 3});
        lpb.addExtraItem(new byte[]{4, 5});
        lpb.writeTo(bb1);

        B parsed = new B();
        parsed.parseFrom(bb1, bb1.readableBytes());
        parsed.materialize();

        // materialize() copied each value into an exact-size array: the getters return that
        // array as is, and it is the one backing the field
        byte[] payload = parsed.getPayload();
        assertArrayEquals(new byte[]{1, 2, 3}, payload);
        assertSame(payload, parsed.getPayload());
        assertSame(payload, parsed.getPayloadSlice().array());

        byte[] item = parsed.getExtraItemAt(0);
        assertArrayEquals(new byte[]{4, 5}, item);
        assertSame(item, parsed.getExtraItemAt(0));
        assertSame(item, parsed.getExtraItemSliceAt(0).array());

        // The arrays do not depend on the buffer the message was parsed from
        Arrays.fill(b1, (byte) 0);
        assertArrayEquals(new byte[]{1, 2, 3}, payload);
        assertArrayEquals(new byte[]{4, 5}, item);
    }

    @Test
    public void testGettersReturnArraysPassedToSetters() {
        byte[] payload = {1, 2, 3};
        byte[] item = {4, 5};
        B lpb = new B().setPayload(payload);
        lpb.addExtraItem(item);

        assertSame(payload, lpb.getPayload());
        assertSame(item, lpb.getExtraItemAt(0));

        // Same for a ByteBuf that wraps exactly the caller's array
        byte[] wrapped = {6, 7};
        lpb.setPayload(Unpooled.wrappedBuffer(wrapped));
        assertSame(wrapped, lpb.getPayload());
    }

    @Test
    public void testGettersCopySlicesOfParsedBuffer() throws Exception {
        B lpb = new B().setPayload(new byte[]{1, 2, 3});
        lpb.addExtraItem(new byte[]{4, 5});
        lpb.writeTo(bb1);

        // Without materialize(), the values are slices of the larger parsed buffer
        B parsed = new B();
        parsed.parseFrom(bb1, bb1.readableBytes());

        byte[] payload = parsed.getPayload();
        assertArrayEquals(new byte[]{1, 2, 3}, payload);
        assertNotSame(payload, parsed.getPayload());
        payload[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, parsed.getPayload());

        byte[] item = parsed.getExtraItemAt(0);
        assertArrayEquals(new byte[]{4, 5}, item);
        assertNotSame(item, parsed.getExtraItemAt(0));
        item[0] = 9;
        assertArrayEquals(new byte[]{4, 5}, parsed.getExtraItemAt(0));
    }

    @Test
    public void testGettersCopyBuffersNotWrappingExactlyTheValue() {
        assertPayloadCopied(Unpooled.wrappedBuffer(new byte[]{0, 1, 2, 3, 0}, 1, 3));
        assertPayloadCopied(Unpooled.wrappedBuffer(new byte[]{0, 1, 2, 3}).skipBytes(1));
        assertPayloadCopied(Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 0}).writerIndex(3));

        ByteBuf pooled = PooledByteBufAllocator.DEFAULT.heapBuffer(3).writeBytes(new byte[]{1, 2, 3});
        try {
            assertPayloadCopied(pooled);
        } finally {
            pooled.release();
        }

        ByteBuf direct = Unpooled.directBuffer(3).writeBytes(new byte[]{1, 2, 3});
        try {
            assertPayloadCopied(direct);
        } finally {
            direct.release();
        }
    }

    private static void assertPayloadCopied(ByteBuf value) {
        B lpb = new B().setPayload(value);
        byte[] payload = lpb.getPayload();
        assertArrayEquals(new byte[]{1, 2, 3}, payload);
        assertNotSame(payload, lpb.getPayload());
    }

    @Test
    public void testRepeatedGetterAfterClear() {
        B lpb = new B();
        lpb.addExtraItem(new byte[]{4, 5});
        lpb.clear();

        // clear() keeps the element holders for reuse: their arrays must not be returned
        assertThrows(IndexOutOfBoundsException.class, () -> lpb.getExtraItemAt(0));
    }

    @Test
    public void testMapGettersShareWholeArrays() throws Exception {
        byte[] a = {1, 2, 3};
        MapMessage lp = new MapMessage();
        lp.putStringToBytes("a", a);
        lp.putStringToBytes("b", new byte[]{4, 5});
        assertSame(a, lp.getStringToBytes("a"));

        lp.writeTo(bb1);
        MapMessage parsed = new MapMessage();
        parsed.parseFrom(bb1, bb1.readableBytes());

        // Values that are still slices of the parsed buffer are copied
        byte[] copied = parsed.getStringToBytes("a");
        assertArrayEquals(a, copied);
        assertNotSame(copied, parsed.getStringToBytes("a"));
        Map<String, byte[]> copies = new HashMap<>();
        parsed.forEachStringToBytes(copies::put);
        assertNotSame(copies.get("a"), parsed.getStringToBytes("a"));

        parsed.materialize();
        byte[] shared = parsed.getStringToBytes("a");
        assertArrayEquals(a, shared);
        assertSame(shared, parsed.getStringToBytes("a"));

        Map<String, byte[]> values = new HashMap<>();
        parsed.forEachStringToBytes(values::put);
        assertEquals(2, values.size());
        assertSame(shared, values.get("a"));
        assertArrayEquals(new byte[]{4, 5}, values.get("b"));
        assertSame(values.get("b"), parsed.getStringToBytes("b"));
    }
}
