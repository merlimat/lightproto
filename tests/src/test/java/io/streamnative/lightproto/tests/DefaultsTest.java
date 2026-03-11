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

import com.google.protobuf.CodedOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultsTest {

    private byte[] b1 = new byte[4096];
    private ByteBuf bb1 = Unpooled.wrappedBuffer(b1);

    private byte[] b2 = new byte[4096];

    @BeforeEach
    public void setup() {
        bb1.clear();
        Arrays.fill(b1, (byte) 0);
        Arrays.fill(b2, (byte) 0);
    }

    // ---- Proto2 optional, no custom default: unset returns type default ----

    @Test
    public void testProto2OptionalNoDefault_unsetReturnsTypeDefault() {
        DefaultsOptionalNoDefault msg = new DefaultsOptionalNoDefault();

        // All has*() return false
        assertFalse(msg.hasFInt32());
        assertFalse(msg.hasFInt64());
        assertFalse(msg.hasFUint32());
        assertFalse(msg.hasFUint64());
        assertFalse(msg.hasFSint32());
        assertFalse(msg.hasFSint64());
        assertFalse(msg.hasFFixed32());
        assertFalse(msg.hasFFixed64());
        assertFalse(msg.hasFSfixed32());
        assertFalse(msg.hasFSfixed64());
        assertFalse(msg.hasFFloat());
        assertFalse(msg.hasFDouble());
        assertFalse(msg.hasFBool());
        assertFalse(msg.hasFString());
        assertFalse(msg.hasFBytes());
        assertFalse(msg.hasFEnum());
        assertFalse(msg.hasFMsg());

        // Getters return type defaults (no exception)
        assertEquals(0, msg.getFInt32());
        assertEquals(0L, msg.getFInt64());
        assertEquals(0, msg.getFUint32());
        assertEquals(0L, msg.getFUint64());
        assertEquals(0, msg.getFSint32());
        assertEquals(0L, msg.getFSint64());
        assertEquals(0, msg.getFFixed32());
        assertEquals(0L, msg.getFFixed64());
        assertEquals(0, msg.getFSfixed32());
        assertEquals(0L, msg.getFSfixed64());
        assertEquals(0.0f, msg.getFFloat());
        assertEquals(0.0, msg.getFDouble());
        assertFalse(msg.isFBool());
        assertEquals("", msg.getFString());
        assertArrayEquals(new byte[0], msg.getFBytes());
        assertEquals(io.netty.buffer.Unpooled.EMPTY_BUFFER, msg.getFBytesSlice());
        assertEquals(0, msg.getFBytesSize());
        assertEquals(DefaultEnum.DE_ZERO, msg.getFEnum());
        assertNull(msg.getFMsg());
    }

    // ---- Proto2 optional, with custom default: unset returns custom default ----

    @Test
    public void testProto2OptionalWithDefault_unsetReturnsCustomDefault() {
        DefaultsOptionalWithDefault msg = new DefaultsOptionalWithDefault();

        assertFalse(msg.hasFInt32());
        assertFalse(msg.hasFInt64());
        assertFalse(msg.hasFUint32());
        assertFalse(msg.hasFUint64());
        assertFalse(msg.hasFSint32());
        assertFalse(msg.hasFSint64());
        assertFalse(msg.hasFFixed32());
        assertFalse(msg.hasFFixed64());
        assertFalse(msg.hasFSfixed32());
        assertFalse(msg.hasFSfixed64());
        assertFalse(msg.hasFFloat());
        assertFalse(msg.hasFDouble());
        assertFalse(msg.hasFBool());
        assertFalse(msg.hasFString());

        assertEquals(42, msg.getFInt32());
        assertEquals(100L, msg.getFInt64());
        assertEquals(7, msg.getFUint32());
        assertEquals(200L, msg.getFUint64());
        assertEquals(-5, msg.getFSint32());
        assertEquals(-50L, msg.getFSint64());
        assertEquals(99, msg.getFFixed32());
        assertEquals(999L, msg.getFFixed64());
        assertEquals(-99, msg.getFSfixed32());
        assertEquals(-999L, msg.getFSfixed64());
        assertEquals(3.14f, msg.getFFloat(), 0.001f);
        assertEquals(2.718, msg.getFDouble(), 0.001);
        assertTrue(msg.isFBool());
        assertEquals("hello", msg.getFString());
    }

    // ---- Proto2 required: unset throws ----

    @Test
    public void testProto2Required_unsetThrows() {
        DefaultsRequired msg = new DefaultsRequired();

        assertThrows(IllegalStateException.class, msg::getFInt32);
        assertThrows(IllegalStateException.class, msg::getFString);
        assertThrows(IllegalStateException.class, msg::isFBool);
    }

    @Test
    public void testProto2Required_serializeUnsetThrows() {
        DefaultsRequired msg = new DefaultsRequired();
        assertThrows(IllegalStateException.class, () -> msg.writeTo(bb1));
    }

    @Test
    public void testProto2Required_setThenGetWorks() {
        DefaultsRequired msg = new DefaultsRequired();
        msg.setFInt32(10);
        msg.setFString("test");
        msg.setFBool(true);

        assertEquals(10, msg.getFInt32());
        assertEquals("test", msg.getFString());
        assertTrue(msg.isFBool());
    }

    // ---- Proto2 optional: has() lifecycle (set, clear, set) ----

    @Test
    public void testProto2Optional_hasLifecycle() {
        DefaultsOptionalNoDefault msg = new DefaultsOptionalNoDefault();

        // Initially unset
        assertFalse(msg.hasFInt32());
        assertEquals(0, msg.getFInt32());

        // After set
        msg.setFInt32(42);
        assertTrue(msg.hasFInt32());
        assertEquals(42, msg.getFInt32());

        // After clear
        msg.clearFInt32();
        assertFalse(msg.hasFInt32());
        assertEquals(0, msg.getFInt32());

        // Re-set
        msg.setFInt32(99);
        assertTrue(msg.hasFInt32());
        assertEquals(99, msg.getFInt32());
    }

    @Test
    public void testProto2Optional_hasLifecycleString() {
        DefaultsOptionalNoDefault msg = new DefaultsOptionalNoDefault();

        assertFalse(msg.hasFString());
        assertEquals("", msg.getFString());

        msg.setFString("hello");
        assertTrue(msg.hasFString());
        assertEquals("hello", msg.getFString());

        msg.clearFString();
        assertFalse(msg.hasFString());
        assertEquals("", msg.getFString());
    }

    @Test
    public void testProto2Optional_hasLifecycleEnum() {
        DefaultsOptionalNoDefault msg = new DefaultsOptionalNoDefault();

        assertFalse(msg.hasFEnum());
        assertEquals(DefaultEnum.DE_ZERO, msg.getFEnum());

        msg.setFEnum(DefaultEnum.DE_ONE);
        assertTrue(msg.hasFEnum());
        assertEquals(DefaultEnum.DE_ONE, msg.getFEnum());

        msg.clearFEnum();
        assertFalse(msg.hasFEnum());
        assertEquals(DefaultEnum.DE_ZERO, msg.getFEnum());
    }

    @Test
    public void testProto2Optional_hasLifecycleMessage() {
        DefaultsOptionalNoDefault msg = new DefaultsOptionalNoDefault();

        assertFalse(msg.hasFMsg());
        assertNull(msg.getFMsg());

        msg.setFMsg().setValue(5);
        assertTrue(msg.hasFMsg());
        assertNotNull(msg.getFMsg());
        assertEquals(5, msg.getFMsg().getValue());
    }

    @Test
    public void testProto2Optional_hasLifecycleBytes() {
        DefaultsOptionalNoDefault msg = new DefaultsOptionalNoDefault();

        assertFalse(msg.hasFBytes());
        assertArrayEquals(new byte[0], msg.getFBytes());
        assertEquals(0, msg.getFBytesSize());

        msg.setFBytes(new byte[]{1, 2, 3});
        assertTrue(msg.hasFBytes());
        assertArrayEquals(new byte[]{1, 2, 3}, msg.getFBytes());
        assertEquals(3, msg.getFBytesSize());

        msg.clearFBytes();
        assertFalse(msg.hasFBytes());
        assertArrayEquals(new byte[0], msg.getFBytes());
        assertEquals(0, msg.getFBytesSize());
    }

    @Test
    public void testProto2Optional_hasLifecycleWithDefault() {
        DefaultsOptionalWithDefault msg = new DefaultsOptionalWithDefault();

        assertFalse(msg.hasFInt32());
        assertEquals(42, msg.getFInt32());

        msg.setFInt32(0);
        assertTrue(msg.hasFInt32());
        assertEquals(0, msg.getFInt32());

        msg.clearFInt32();
        assertFalse(msg.hasFInt32());
        assertEquals(42, msg.getFInt32());
    }

    // ---- Proto2 optional: clear() resets all types to implicit default ----

    @Test
    public void testProto2Optional_clearResetsAllToImplicitDefault() {
        DefaultsOptionalNoDefault msg = new DefaultsOptionalNoDefault();

        // Set all fields
        msg.setFInt32(42);
        msg.setFInt64(100L);
        msg.setFUint32(7);
        msg.setFUint64(200L);
        msg.setFSint32(-5);
        msg.setFSint64(-50L);
        msg.setFFixed32(99);
        msg.setFFixed64(999L);
        msg.setFSfixed32(-99);
        msg.setFSfixed64(-999L);
        msg.setFFloat(3.14f);
        msg.setFDouble(2.718);
        msg.setFBool(true);
        msg.setFString("hello");
        msg.setFBytes(new byte[]{1, 2, 3});
        msg.setFEnum(DefaultEnum.DE_TWO);
        msg.setFMsg().setValue(42);

        // Verify all are set
        assertTrue(msg.hasFInt32());
        assertTrue(msg.hasFString());
        assertTrue(msg.hasFBool());
        assertTrue(msg.hasFEnum());
        assertTrue(msg.hasFMsg());

        // Clear all
        msg.clear();

        // Verify all reset to implicit defaults
        assertFalse(msg.hasFInt32());
        assertFalse(msg.hasFInt64());
        assertFalse(msg.hasFUint32());
        assertFalse(msg.hasFUint64());
        assertFalse(msg.hasFSint32());
        assertFalse(msg.hasFSint64());
        assertFalse(msg.hasFFixed32());
        assertFalse(msg.hasFFixed64());
        assertFalse(msg.hasFSfixed32());
        assertFalse(msg.hasFSfixed64());
        assertFalse(msg.hasFFloat());
        assertFalse(msg.hasFDouble());
        assertFalse(msg.hasFBool());
        assertFalse(msg.hasFString());
        assertFalse(msg.hasFBytes());
        assertFalse(msg.hasFEnum());
        assertFalse(msg.hasFMsg());

        assertEquals(0, msg.getFInt32());
        assertEquals(0L, msg.getFInt64());
        assertEquals(0, msg.getFUint32());
        assertEquals(0L, msg.getFUint64());
        assertEquals(0, msg.getFSint32());
        assertEquals(0L, msg.getFSint64());
        assertEquals(0, msg.getFFixed32());
        assertEquals(0L, msg.getFFixed64());
        assertEquals(0, msg.getFSfixed32());
        assertEquals(0L, msg.getFSfixed64());
        assertEquals(0.0f, msg.getFFloat());
        assertEquals(0.0, msg.getFDouble());
        assertFalse(msg.isFBool());
        assertEquals("", msg.getFString());
        assertArrayEquals(new byte[0], msg.getFBytes());
        assertEquals(0, msg.getFBytesSize());
        assertEquals(DefaultEnum.DE_ZERO, msg.getFEnum());
        assertNull(msg.getFMsg());
    }

    // ---- Proto2 optional: clear() resets all types to custom default ----

    @Test
    public void testProto2Optional_clearResetsAllToCustomDefault() {
        DefaultsOptionalWithDefault msg = new DefaultsOptionalWithDefault();

        // Set all fields to non-default values
        msg.setFInt32(0);
        msg.setFInt64(0L);
        msg.setFUint32(0);
        msg.setFUint64(0L);
        msg.setFSint32(0);
        msg.setFSint64(0L);
        msg.setFFixed32(0);
        msg.setFFixed64(0L);
        msg.setFSfixed32(0);
        msg.setFSfixed64(0L);
        msg.setFFloat(0.0f);
        msg.setFDouble(0.0);
        msg.setFBool(false);
        msg.setFString("world");

        // Verify all are set
        assertTrue(msg.hasFInt32());
        assertTrue(msg.hasFString());
        assertTrue(msg.hasFBool());

        // Clear all
        msg.clear();

        // Verify all reset to custom defaults
        assertFalse(msg.hasFInt32());
        assertFalse(msg.hasFInt64());
        assertFalse(msg.hasFUint32());
        assertFalse(msg.hasFUint64());
        assertFalse(msg.hasFSint32());
        assertFalse(msg.hasFSint64());
        assertFalse(msg.hasFFixed32());
        assertFalse(msg.hasFFixed64());
        assertFalse(msg.hasFSfixed32());
        assertFalse(msg.hasFSfixed64());
        assertFalse(msg.hasFFloat());
        assertFalse(msg.hasFDouble());
        assertFalse(msg.hasFBool());
        assertFalse(msg.hasFString());

        assertEquals(42, msg.getFInt32());
        assertEquals(100L, msg.getFInt64());
        assertEquals(7, msg.getFUint32());
        assertEquals(200L, msg.getFUint64());
        assertEquals(-5, msg.getFSint32());
        assertEquals(-50L, msg.getFSint64());
        assertEquals(99, msg.getFFixed32());
        assertEquals(999L, msg.getFFixed64());
        assertEquals(-99, msg.getFSfixed32());
        assertEquals(-999L, msg.getFSfixed64());
        assertEquals(3.14f, msg.getFFloat(), 0.001f);
        assertEquals(2.718, msg.getFDouble(), 0.001);
        assertTrue(msg.isFBool());
        assertEquals("hello", msg.getFString());
    }

    // ---- Proto3 optional: clear() resets to type default ----

    @Test
    public void testProto3Optional_clearResetsToTypeDefault() {
        Proto3Defaults msg = new Proto3Defaults();

        // Set all fields
        msg.setOptInt32(42);
        msg.setOptInt64(100L);
        msg.setOptFloat(3.14f);
        msg.setOptDouble(2.718);
        msg.setOptBool(true);
        msg.setOptString("hello");
        msg.setOptBytes(new byte[]{1, 2, 3});
        msg.setOptEnum(Proto3Enum.VALUE_A);
        msg.setOptMsg().setLabel("test").setValue(5);

        assertTrue(msg.hasOptInt32());
        assertTrue(msg.hasOptString());
        assertTrue(msg.hasOptMsg());

        // Clear all
        msg.clear();

        assertFalse(msg.hasOptInt32());
        assertFalse(msg.hasOptInt64());
        assertFalse(msg.hasOptFloat());
        assertFalse(msg.hasOptDouble());
        assertFalse(msg.hasOptBool());
        assertFalse(msg.hasOptString());
        assertFalse(msg.hasOptBytes());
        assertFalse(msg.hasOptEnum());
        assertFalse(msg.hasOptMsg());

        assertEquals(0, msg.getOptInt32());
        assertEquals(0L, msg.getOptInt64());
        assertEquals(0.0f, msg.getOptFloat());
        assertEquals(0.0, msg.getOptDouble());
        assertFalse(msg.isOptBool());
        assertEquals("", msg.getOptString());
        assertArrayEquals(new byte[0], msg.getOptBytes());
        assertEquals(0, msg.getOptBytesSize());
        assertEquals(Proto3Enum.DEFAULT, msg.getOptEnum());
        assertNull(msg.getOptMsg());
    }

    // ---- Proto2 optional set to type default: round-trip preserves presence ----

    @Test
    public void testProto2Optional_setToDefaultRoundtrip() throws Exception {
        DefaultsOptionalNoDefault msg = new DefaultsOptionalNoDefault();
        msg.setFInt32(0);
        msg.setFString("");
        msg.setFBool(false);
        msg.setFEnum(DefaultEnum.DE_ZERO);

        assertTrue(msg.hasFInt32());
        assertTrue(msg.hasFString());
        assertTrue(msg.hasFBool());
        assertTrue(msg.hasFEnum());

        // Serialize
        msg.writeTo(bb1);
        assertTrue(bb1.readableBytes() > 0);

        // Deserialize
        DefaultsOptionalNoDefault parsed = new DefaultsOptionalNoDefault();
        parsed.parseFrom(bb1, bb1.readableBytes());

        assertTrue(parsed.hasFInt32());
        assertTrue(parsed.hasFString());
        assertTrue(parsed.hasFBool());
        assertTrue(parsed.hasFEnum());
        assertEquals(0, parsed.getFInt32());
        assertEquals("", parsed.getFString());
        assertFalse(parsed.isFBool());
        assertEquals(DefaultEnum.DE_ZERO, parsed.getFEnum());
    }

    // ---- Proto3 optional: unset returns type default ----

    @Test
    public void testProto3Optional_unsetReturnsTypeDefault() {
        Proto3Defaults msg = new Proto3Defaults();

        assertFalse(msg.hasOptInt32());
        assertFalse(msg.hasOptInt64());
        assertFalse(msg.hasOptFloat());
        assertFalse(msg.hasOptDouble());
        assertFalse(msg.hasOptBool());
        assertFalse(msg.hasOptString());
        assertFalse(msg.hasOptBytes());
        assertFalse(msg.hasOptEnum());
        assertFalse(msg.hasOptMsg());

        assertEquals(0, msg.getOptInt32());
        assertEquals(0L, msg.getOptInt64());
        assertEquals(0.0f, msg.getOptFloat());
        assertEquals(0.0, msg.getOptDouble());
        assertFalse(msg.isOptBool());
        assertEquals("", msg.getOptString());
        assertArrayEquals(new byte[0], msg.getOptBytes());
        assertEquals(0, msg.getOptBytesSize());
        assertEquals(Proto3Enum.DEFAULT, msg.getOptEnum());
        assertNull(msg.getOptMsg());
    }

    // ---- Proto3 optional: has() works after set ----

    @Test
    public void testProto3Optional_setToDefaultPreservesPresence() {
        Proto3Defaults msg = new Proto3Defaults();

        msg.setOptInt32(0);
        assertTrue(msg.hasOptInt32());
        assertEquals(0, msg.getOptInt32());

        msg.setOptString("");
        assertTrue(msg.hasOptString());
        assertEquals("", msg.getOptString());

        msg.setOptBool(false);
        assertTrue(msg.hasOptBool());
        assertFalse(msg.isOptBool());

        msg.setOptEnum(Proto3Enum.DEFAULT);
        assertTrue(msg.hasOptEnum());
        assertEquals(Proto3Enum.DEFAULT, msg.getOptEnum());
    }

    // ---- Cross-format: LightProto ↔ protobuf-java round-trip ----

    @Test
    public void testProto2CrossFormat_emptyMessage() throws Exception {
        // LightProto empty → protobuf-java
        DefaultsOptionalNoDefault lpEmpty = new DefaultsOptionalNoDefault();
        lpEmpty.writeTo(bb1);
        byte[] lpBytes = new byte[bb1.readableBytes()];
        System.arraycopy(b1, 0, lpBytes, 0, lpBytes.length);

        Defaults.DefaultsOptionalNoDefault gpFromLp =
                Defaults.DefaultsOptionalNoDefault.parseFrom(lpBytes);
        assertFalse(gpFromLp.hasFInt32());
        assertFalse(gpFromLp.hasFString());
        assertFalse(gpFromLp.hasFBool());
        assertFalse(gpFromLp.hasFEnum());

        // protobuf-java empty → LightProto
        Defaults.DefaultsOptionalNoDefault gpEmpty =
                Defaults.DefaultsOptionalNoDefault.getDefaultInstance();
        byte[] gpBytes = gpEmpty.toByteArray();

        DefaultsOptionalNoDefault lpFromGp = new DefaultsOptionalNoDefault();
        lpFromGp.parseFrom(gpBytes);
        assertFalse(lpFromGp.hasFInt32());
        assertFalse(lpFromGp.hasFString());
        assertFalse(lpFromGp.hasFBool());
        assertFalse(lpFromGp.hasFEnum());
        assertEquals(0, lpFromGp.getFInt32());
        assertEquals("", lpFromGp.getFString());
        assertFalse(lpFromGp.isFBool());
        assertEquals(DefaultEnum.DE_ZERO, lpFromGp.getFEnum());
    }

    @Test
    public void testProto2CrossFormat_withValues() throws Exception {
        // LightProto with values → protobuf-java
        DefaultsOptionalNoDefault lp = new DefaultsOptionalNoDefault();
        lp.setFInt32(42);
        lp.setFString("test");
        lp.setFBool(true);
        lp.setFEnum(DefaultEnum.DE_ONE);
        lp.setFBytes(new byte[]{1, 2});

        lp.writeTo(bb1);
        byte[] lpBytes = new byte[bb1.readableBytes()];
        System.arraycopy(b1, 0, lpBytes, 0, lpBytes.length);

        Defaults.DefaultsOptionalNoDefault gp =
                Defaults.DefaultsOptionalNoDefault.parseFrom(lpBytes);
        assertTrue(gp.hasFInt32());
        assertEquals(42, gp.getFInt32());
        assertTrue(gp.hasFString());
        assertEquals("test", gp.getFString());
        assertTrue(gp.hasFBool());
        assertTrue(gp.getFBool());
        assertTrue(gp.hasFEnum());
        assertEquals(Defaults.DefaultEnum.DE_ONE, gp.getFEnum());

        // protobuf-java → LightProto
        bb1.clear();
        byte[] gpBytes = gp.toByteArray();
        DefaultsOptionalNoDefault lpFromGp = new DefaultsOptionalNoDefault();
        lpFromGp.parseFrom(gpBytes);
        assertTrue(lpFromGp.hasFInt32());
        assertEquals(42, lpFromGp.getFInt32());
        assertTrue(lpFromGp.hasFString());
        assertEquals("test", lpFromGp.getFString());
        assertTrue(lpFromGp.hasFBool());
        assertTrue(lpFromGp.isFBool());
        assertTrue(lpFromGp.hasFEnum());
        assertEquals(DefaultEnum.DE_ONE, lpFromGp.getFEnum());
    }

    @Test
    public void testProto2CrossFormat_customDefaults() throws Exception {
        // protobuf-java empty with custom defaults → LightProto
        Defaults.DefaultsOptionalWithDefault gpEmpty =
                Defaults.DefaultsOptionalWithDefault.getDefaultInstance();
        byte[] gpBytes = gpEmpty.toByteArray();

        DefaultsOptionalWithDefault lpFromGp = new DefaultsOptionalWithDefault();
        lpFromGp.parseFrom(gpBytes);
        assertFalse(lpFromGp.hasFInt32());
        assertEquals(42, lpFromGp.getFInt32());
        assertFalse(lpFromGp.hasFString());
        assertEquals("hello", lpFromGp.getFString());
        assertFalse(lpFromGp.hasFBool());
        assertTrue(lpFromGp.isFBool());
    }

    @Test
    public void testProto3CrossFormat_optionalDefaults() throws Exception {
        // LightProto empty → protobuf-java
        Proto3Defaults lpEmpty = new Proto3Defaults();
        assertEquals(0, lpEmpty.getSerializedSize());

        // protobuf-java empty → LightProto
        Proto3Protos.Proto3Defaults gpEmpty =
                Proto3Protos.Proto3Defaults.getDefaultInstance();
        byte[] gpBytes = gpEmpty.toByteArray();

        Proto3Defaults lpFromGp = new Proto3Defaults();
        lpFromGp.parseFrom(gpBytes);
        assertFalse(lpFromGp.hasOptInt32());
        assertEquals(0, lpFromGp.getOptInt32());
        assertFalse(lpFromGp.hasOptString());
        assertEquals("", lpFromGp.getOptString());
    }

    @Test
    public void testProto2CrossFormat_byteExact() throws Exception {
        DefaultsOptionalNoDefault lp = new DefaultsOptionalNoDefault();
        lp.setFInt32(42);
        lp.setFString("test");
        lp.setFBool(true);

        Defaults.DefaultsOptionalNoDefault gp =
                Defaults.DefaultsOptionalNoDefault.newBuilder()
                        .setFInt32(42)
                        .setFString("test")
                        .setFBool(true)
                        .build();

        assertEquals(gp.getSerializedSize(), lp.getSerializedSize());

        lp.writeTo(bb1);
        gp.writeTo(CodedOutputStream.newInstance(b2));

        byte[] lpResult = new byte[lp.getSerializedSize()];
        byte[] gpResult = new byte[gp.getSerializedSize()];
        System.arraycopy(b1, 0, lpResult, 0, lpResult.length);
        System.arraycopy(b2, 0, gpResult, 0, gpResult.length);
        assertArrayEquals(gpResult, lpResult);
    }
}
