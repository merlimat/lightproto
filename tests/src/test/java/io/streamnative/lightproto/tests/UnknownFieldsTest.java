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

import com.google.protobuf.DiscardUnknownFieldsParser;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.google.protobuf.UnknownFieldSet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.streamnative.lightproto.tests.imported.ImportedProtos;
import io.streamnative.lightproto.tests.importer.Container;
import io.streamnative.lightproto.tests.importer.ImporterProtos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parsing drops unknown fields, so a parsed message must re-serialize to what
 * protobuf-java writes once it discards them too, wherever they sit: in the
 * message itself, in a nested message at any depth, or inside a map entry.
 * parseFrom() may reuse the wire size as the serialized size only when nothing
 * below it was dropped; otherwise writeTo() pads its output with trailing bytes.
 */
public class UnknownFieldsTest {

    /** A field that none of the messages below declare, as added by a newer producer. */
    private static final UnknownFieldSet UNKNOWN_FIELD = UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(9).build())
            .build();

    @Test
    public void testUnknownFieldInMessage() throws Exception {
        byte[] wire = Messages.X.newBuilder()
                .setA("a")
                .setUnknownFields(UNKNOWN_FIELD)
                .build().toByteArray();
        byte[] expected = withoutUnknownFields(Messages.X.parser(), wire);

        X lp = new X();
        lp.parseFrom(wire);
        assertSerializedAs(expected, lp::getSerializedSize, lp::writeTo);
    }

    @Test
    public void testUnknownFieldInNestedMessage() throws Exception {
        byte[] wire = Messages.M.newBuilder()
                .setX(Messages.X.newBuilder().setA("a").setUnknownFields(UNKNOWN_FIELD))
                .build().toByteArray();
        byte[] expected = withoutUnknownFields(Messages.M.parser(), wire);

        M lp = new M();
        lp.parseFrom(wire);
        assertEquals("a", lp.getX().getA());
        assertSerializedAs(expected, lp::getSerializedSize, lp::writeTo);
    }

    @Test
    public void testUnknownFieldInRepeatedMessage() throws Exception {
        byte[] wire = Messages.M.newBuilder()
                .addItems(Messages.M.KV.newBuilder().setK("k1").setV("v1"))
                .addItems(Messages.M.KV.newBuilder().setK("k2").setV("v2").setUnknownFields(UNKNOWN_FIELD))
                .build().toByteArray();
        byte[] expected = withoutUnknownFields(Messages.M.parser(), wire);

        M lp = new M();
        lp.parseFrom(wire);
        assertEquals(2, lp.getItemsCount());
        assertSerializedAs(expected, lp::getSerializedSize, lp::writeTo);
    }

    @Test
    public void testUnknownFieldTwoLevelsDown() throws Exception {
        // M.items[0].xx skips the field: XX must keep KV from caching its size, and KV must keep M.
        byte[] wire = Messages.M.newBuilder()
                .addItems(Messages.M.KV.newBuilder().setK("k").setV("v")
                        .setXx(Messages.M.KV.XX.newBuilder().setN(5).setUnknownFields(UNKNOWN_FIELD)))
                .build().toByteArray();
        byte[] expected = withoutUnknownFields(Messages.M.parser(), wire);

        M lp = new M();
        lp.parseFrom(wire);
        assertEquals(5, lp.getItemAt(0).getXx().getN());
        assertSerializedAs(expected, lp::getSerializedSize, lp::writeTo);
    }

    @Test
    public void testUnknownFieldInOneofMessage() throws Exception {
        byte[] wire = OneofProtos.OneofMsg.newBuilder()
                .setOneofMsg(OneofProtos.SubMessage.newBuilder().setValue(1).setUnknownFields(UNKNOWN_FIELD))
                .build().toByteArray();
        byte[] expected = withoutUnknownFields(OneofProtos.OneofMsg.parser(), wire);

        OneofMsg lp = new OneofMsg();
        lp.parseFrom(wire);
        assertEquals(1, lp.getOneofMsg().getValue());
        assertSerializedAs(expected, lp::getSerializedSize, lp::writeTo);
    }

    @Test
    public void testUnknownFieldInMessageFromOtherPackage() throws Exception {
        ImportedProtos.SharedItem item = ImportedProtos.SharedItem.newBuilder()
                .setName("n")
                .setUnknownFields(UNKNOWN_FIELD)
                .build();
        byte[] wire = ImporterProtos.Container.newBuilder()
                .setItem(item)
                .addItems(item)
                .build().toByteArray();
        byte[] expected = withoutUnknownFields(ImporterProtos.Container.parser(), wire);

        Container lp = new Container();
        lp.parseFrom(wire);
        assertSerializedAs(expected, lp::getSerializedSize, lp::writeTo);
    }

    @Test
    public void testUnknownFieldInMapEntry() throws Exception {
        // string_to_int { key: "k" value: 1 } plus field 3 inside the entry, which
        // declares only key (1) and value (2). protobuf-java can't build this.
        byte[] wire = {0x0A, 0x07, 0x0A, 0x01, 'k', 0x10, 0x01, 0x18, 0x09};
        byte[] expected = withoutUnknownFields(MapsProtos.MapMessage.parser(), wire);

        MapMessage lp = new MapMessage();
        lp.parseFrom(wire);
        assertEquals(1, lp.getStringToInt("k"));
        assertSerializedAs(expected, lp::getSerializedSize, lp::writeTo);
    }

    @Test
    public void testUnknownFieldInMapMessageValue() throws Exception {
        byte[] wire = MapsProtos.MapMessage.newBuilder()
                .putStringToMsg("k", MapsProtos.MapNestedValue.newBuilder()
                        .setId(1)
                        .setUnknownFields(UNKNOWN_FIELD)
                        .build())
                .build().toByteArray();
        byte[] expected = withoutUnknownFields(MapsProtos.MapMessage.parser(), wire);

        MapMessage lp = new MapMessage();
        lp.parseFrom(wire);
        assertEquals(1, lp.getStringToMsg("k").getId());
        assertSerializedAs(expected, lp::getSerializedSize, lp::writeTo);
    }

    /**
     * Clean input must still cache the wire size at every level, including when
     * the pooled nested instances of a reused message parsed unknown fields before.
     */
    @Test
    public void testCleanInputCachesWireSize() throws Exception {
        Messages.M.KV.XX xx = Messages.M.KV.XX.newBuilder().setN(5).build();
        byte[] clean = Messages.M.newBuilder()
                .setX(Messages.X.newBuilder().setA("a"))
                .addItems(Messages.M.KV.newBuilder().setK("k").setV("v").setXx(xx))
                .build().toByteArray();
        byte[] withUnknown = Messages.M.newBuilder()
                .setX(Messages.X.newBuilder().setA("a"))
                .addItems(Messages.M.KV.newBuilder().setK("k").setV("v")
                        .setXx(xx.toBuilder().setUnknownFields(UNKNOWN_FIELD)))
                .build().toByteArray();

        M lp = new M();
        lp.parseFrom(clean);
        assertEquals(clean.length, cachedSize(lp));

        lp.parseFrom(withUnknown);
        assertEquals(-1, cachedSize(lp));
        assertEquals(-1, cachedSize(lp.getItemAt(0)));
        // A sibling of the message that skipped the field caches as usual.
        assertTrue(cachedSize(lp.getX()) > 0);
        assertSerializedAs(clean, lp::getSerializedSize, lp::writeTo);

        lp.parseFrom(clean);
        assertEquals(clean.length, cachedSize(lp));
        assertSerializedAs(clean, lp::getSerializedSize, lp::writeTo);
    }

    /** What protobuf-java writes for {@code wire} once it discards unknown fields, as LightProto does. */
    private static byte[] withoutUnknownFields(Parser<? extends Message> parser, byte[] wire) throws Exception {
        byte[] expected = DiscardUnknownFieldsParser.wrap(parser).parseFrom(wire).toByteArray();
        assertTrue(expected.length < wire.length, "the input must carry unknown fields");
        return expected;
    }

    private static void assertSerializedAs(byte[] expected, IntSupplier serializedSize,
                                           ToIntFunction<ByteBuf> writeTo) {
        assertEquals(expected.length, serializedSize.getAsInt());
        ByteBuf out = Unpooled.buffer();
        assertEquals(expected.length, writeTo.applyAsInt(out));
        assertArrayEquals(expected, ByteBufUtil.getBytes(out));
    }

    private static int cachedSize(Object message) throws ReflectiveOperationException {
        Field f = message.getClass().getDeclaredField("_cachedSize");
        f.setAccessible(true);
        return f.getInt(message);
    }
}
