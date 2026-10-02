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

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A bool on the wire is a 64-bit varint that protobuf reads as true when it is not zero.
 * Values other than 0 and 1 re-serialize as 1, so the serialized size is not the wire size.
 * protobuf-java is the oracle for the values, the serialized size and the bytes.
 */
public class BoolParseTest {

    // 0 and 1 are canonical; 2 takes one byte, 255 two, 2^32 five (with the low 32 bits
    // all zero) and -1 ten
    private static final long[] VALUES = {0, 1, 2, 255, 1L << 32, -1L};

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 255, 1L << 32, -1L})
    public void testOptionalBool(long value) throws Exception {
        byte[] wire = encode(out -> {
            out.writeTag(13, WireFormat.WIRETYPE_VARINT); // x_bool
            out.writeUInt64NoTag(value);
        });
        NumbersOuterClass.Numbers expected = NumbersOuterClass.Numbers.parseFrom(wire);

        Numbers parsed = new Numbers();
        parsed.parseFrom(wire);
        assertEquals(expected.getXBool(), parsed.isXBool());
        assertEquals(expected.getSerializedSize(), parsed.getSerializedSize());
        assertArrayEquals(expected.toByteArray(), parsed.toByteArray());
    }

    // 0 is left out: proto3 doesn't serialize a default found on the wire, for any field type
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 255, 1L << 32, -1L})
    public void testProto3Bool(long value) throws Exception {
        byte[] wire = encode(out -> {
            out.writeTag(5, WireFormat.WIRETYPE_VARINT); // bool_field
            out.writeUInt64NoTag(value);
        });
        Proto3Protos.Proto3Message expected = Proto3Protos.Proto3Message.parseFrom(wire);

        Proto3Message parsed = new Proto3Message();
        parsed.parseFrom(wire);
        assertEquals(expected.getBoolField(), parsed.isBoolField());
        assertEquals(expected.getSerializedSize(), parsed.getSerializedSize());
        assertArrayEquals(expected.toByteArray(), parsed.toByteArray());
    }

    @Test
    public void testRepeatedBool() throws Exception {
        byte[] wire = encode(out -> {
            for (long value : VALUES) {
                out.writeTag(13, WireFormat.WIRETYPE_VARINT); // x_bool
                out.writeUInt64NoTag(value);
            }
        });
        RepeatedNumbers.Repeated expected = RepeatedNumbers.Repeated.parseFrom(wire);

        Repeated parsed = new Repeated();
        parsed.parseFrom(wire);
        assertEquals(expected.getXBoolCount(), parsed.getXBoolsCount());
        for (int i = 0; i < VALUES.length; i++) {
            assertEquals(expected.getXBool(i), parsed.getXBoolAt(i));
        }
        assertEquals(expected.getSerializedSize(), parsed.getSerializedSize());
        assertArrayEquals(expected.toByteArray(), parsed.toByteArray());
    }

    @Test
    public void testPackedBool() throws Exception {
        byte[] wire = encode(out -> {
            int size = 0;
            for (long value : VALUES) {
                size += CodedOutputStream.computeUInt64SizeNoTag(value);
            }
            out.writeTag(13, WireFormat.WIRETYPE_LENGTH_DELIMITED); // x_bool
            out.writeUInt32NoTag(size);
            for (long value : VALUES) {
                out.writeUInt64NoTag(value);
            }
        });
        RepeatedNumbers.RepeatedPacked expected = RepeatedNumbers.RepeatedPacked.parseFrom(wire);

        RepeatedPacked parsed = new RepeatedPacked();
        parsed.parseFrom(wire);
        assertEquals(expected.getXBoolCount(), parsed.getXBoolsCount());
        for (int i = 0; i < VALUES.length; i++) {
            assertEquals(expected.getXBool(i), parsed.getXBoolAt(i));
        }
        assertEquals(expected.getSerializedSize(), parsed.getSerializedSize());
        assertArrayEquals(expected.toByteArray(), parsed.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 255, 1L << 32, -1L})
    public void testMapBoolKey(long value) throws Exception {
        byte[] entry = encode(out -> {
            out.writeTag(1, WireFormat.WIRETYPE_VARINT); // key
            out.writeUInt64NoTag(value);
            out.writeString(2, "v");
        });
        byte[] wire = encode(out -> out.writeByteArray(5, entry)); // bool_to_string
        MapsProtos.MapMessage expected = MapsProtos.MapMessage.parseFrom(wire);

        MapMessage parsed = new MapMessage();
        parsed.parseFrom(wire);
        assertEquals(1, parsed.getBoolToStringCount());
        assertEquals(expected.getBoolToStringOrThrow(value != 0), parsed.getBoolToString(value != 0));
        assertEquals(expected.getSerializedSize(), parsed.getSerializedSize());
        assertArrayEquals(expected.toByteArray(), parsed.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 255, 1L << 32, -1L})
    public void testMapBoolValue(long value) throws Exception {
        byte[] entry = encode(out -> {
            out.writeString(1, "k");
            out.writeTag(2, WireFormat.WIRETYPE_VARINT); // value
            out.writeUInt64NoTag(value);
        });
        byte[] wire = encode(out -> out.writeByteArray(8, entry)); // string_to_bool
        MapsProtos.MapMessage expected = MapsProtos.MapMessage.parseFrom(wire);

        MapMessage parsed = new MapMessage();
        parsed.parseFrom(wire);
        assertEquals(expected.getStringToBoolOrThrow("k"), parsed.getStringToBool("k"));
        assertEquals(expected.getSerializedSize(), parsed.getSerializedSize());
        assertArrayEquals(expected.toByteArray(), parsed.toByteArray());
    }

    private interface Encoder {
        void encode(CodedOutputStream out) throws IOException;
    }

    private static byte[] encode(Encoder encoder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        encoder.encode(out);
        out.flush();
        return bytes.toByteArray();
    }
}
