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

import java.nio.ByteBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * _writeTo() writes the length prefix of each nested message from _sizeForWrite(), which reads
 * the size that getSerializedSize() cached for the whole tree before the write. These tests call
 * _writeTo() on messages whose sizes were never computed, so every nested prefix takes the
 * getSerializedSize() fallback, and compare the bytes with protobuf-java's, written to a byte[]
 * and to a direct ByteBuffer.
 */
public class SizeForWriteTest {

    interface ArrayWriter {
        int write(byte[] a, int i);
    }

    interface NioWriter {
        int write(ByteBuffer nb, int i);
    }

    private static void assertWritesAs(byte[] expected, ArrayWriter array, NioWriter nio, boolean direct) {
        byte[] actual = new byte[expected.length];
        if (direct) {
            ByteBuffer nb = ByteBuffer.allocateDirect(expected.length);
            assertEquals(expected.length, nio.write(nb, 0));
            nb.get(0, actual);
        } else {
            assertEquals(expected.length, array.write(actual, 0));
        }
        assertArrayEquals(expected, actual);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testSingularAndRepeatedMessages(boolean direct) {
        // x is singular; items is repeated, and its second element nests xx one level further
        M lp = new M();
        lp.setX().setA("a").setB("b");
        lp.addItem().setK("k1").setV("v1");
        lp.addItem().setK("k2").setV("v2").setXx().setN(5);

        Messages.M pb = Messages.M.newBuilder()
                .setX(Messages.X.newBuilder().setA("a").setB("b"))
                .addItems(Messages.M.KV.newBuilder().setK("k1").setV("v1"))
                .addItems(Messages.M.KV.newBuilder().setK("k2").setV("v2")
                        .setXx(Messages.M.KV.XX.newBuilder().setN(5)))
                .build();
        assertWritesAs(pb.toByteArray(), lp::_writeTo, lp::_writeTo, direct);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testMapMessageValues(boolean direct) {
        // inner holds a map with message values; nested_maps has message values that hold one
        MapMessageHolder lp = new MapMessageHolder();
        lp.setInner().putStringToMsg("a").setId(1).setName("x");
        lp.putNestedMaps("n").putStringToMsg("b").setId(2);

        MapsProtos.MapMessageHolder pb = MapsProtos.MapMessageHolder.newBuilder()
                .setInner(MapsProtos.MapMessage.newBuilder()
                        .putStringToMsg("a", MapsProtos.MapNestedValue.newBuilder().setId(1).setName("x").build()))
                .putNestedMaps("n", MapsProtos.MapMessage.newBuilder()
                        .putStringToMsg("b", MapsProtos.MapNestedValue.newBuilder().setId(2).build())
                        .build())
                .build();
        assertWritesAs(pb.toByteArray(), lp::_writeTo, lp::_writeTo, direct);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testOneofMessage(boolean direct) {
        OneofMsg lp = new OneofMsg().setName("n").setAfterField(7);
        lp.setOneofMsg().setValue(42).setLabel("test");

        OneofProtos.OneofMsg pb = OneofProtos.OneofMsg.newBuilder()
                .setName("n")
                .setOneofMsg(OneofProtos.SubMessage.newBuilder().setValue(42).setLabel("test"))
                .setAfterField(7)
                .build();
        assertWritesAs(pb.toByteArray(), lp::_writeTo, lp::_writeTo, direct);
    }
}
