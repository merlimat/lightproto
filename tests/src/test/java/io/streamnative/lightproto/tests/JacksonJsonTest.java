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

import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the generated {@code writeJsonTo()} method produces JSON compatible
 * with protobuf's {@link JsonFormat}.
 */
public class JacksonJsonTest {

    @Test
    public void testNumbersJsonCrossCompatibility() throws Exception {
        Numbers lpNumbers = new Numbers()
                .setXInt32(42)
                .setXInt64(123456789L)
                .setXUint32(100)
                .setXUint64(200L)
                .setXSint32(-50)
                .setXSint64(-100L)
                .setXFixed32(1000)
                .setXFixed64(2000L)
                .setXSfixed32(-1000)
                .setXSfixed64(-2000L)
                .setXFloat(3.14f)
                .setXDouble(2.71828)
                .setXBool(true)
                .setEnum1(Enum1.X1_1)
                .setEnum2(Numbers.Enum2.X2_2);

        String json = lpNumbers.toJson();

        // Parse into protobuf object using JsonFormat
        NumbersOuterClass.Numbers.Builder builder = NumbersOuterClass.Numbers.newBuilder();
        JsonFormat.parser().merge(json, builder);
        NumbersOuterClass.Numbers pbNumbers = builder.build();

        assertEquals(42, pbNumbers.getXInt32());
        assertEquals(123456789L, pbNumbers.getXInt64());
        assertEquals(100, pbNumbers.getXUint32());
        assertEquals(200L, pbNumbers.getXUint64());
        assertEquals(-50, pbNumbers.getXSint32());
        assertEquals(-100L, pbNumbers.getXSint64());
        assertEquals(1000, pbNumbers.getXFixed32());
        assertEquals(2000L, pbNumbers.getXFixed64());
        assertEquals(-1000, pbNumbers.getXSfixed32());
        assertEquals(-2000L, pbNumbers.getXSfixed64());
        assertEquals(3.14f, pbNumbers.getXFloat());
        assertEquals(2.71828, pbNumbers.getXDouble());
        assertTrue(pbNumbers.getXBool());
        assertEquals(NumbersOuterClass.Enum1.X1_1, pbNumbers.getEnum1());
        assertEquals(NumbersOuterClass.Numbers.Enum2.X2_2, pbNumbers.getEnum2());
    }

    @Test
    public void testStringJsonCrossCompatibility() throws Exception {
        S lpS = new S();
        lpS.setId("hello-world");
        lpS.addName("alice");
        lpS.addName("bob");

        String json = lpS.toJson();

        Strings.S.Builder builder = Strings.S.newBuilder();
        JsonFormat.parser().merge(json, builder);
        Strings.S pbS = builder.build();

        assertEquals("hello-world", pbS.getId());
        assertEquals(2, pbS.getNamesCount());
        assertEquals("alice", pbS.getNames(0));
        assertEquals("bob", pbS.getNames(1));
    }

    @Test
    public void testNestedMessageJsonCrossCompatibility() throws Exception {
        M lpM = new M();
        lpM.setX().setA("value-a").setB("value-b");
        lpM.addItem().setK("key1").setV("val1");
        lpM.addItem().setK("key2").setV("val2").setXx().setN(42);

        String json = lpM.toJson();

        Messages.M.Builder builder = Messages.M.newBuilder();
        JsonFormat.parser().merge(json, builder);
        Messages.M pbM = builder.build();

        assertEquals("value-a", pbM.getX().getA());
        assertEquals("value-b", pbM.getX().getB());
        assertEquals(2, pbM.getItemsCount());
        assertEquals("key1", pbM.getItems(0).getK());
        assertEquals("val1", pbM.getItems(0).getV());
        assertEquals("key2", pbM.getItems(1).getK());
        assertEquals("val2", pbM.getItems(1).getV());
        assertEquals(42, pbM.getItems(1).getXx().getN());
    }

    @Test
    public void testEnumJsonCrossCompatibility() throws Exception {
        EnumTest1Optional lpEt = new EnumTest1Optional();
        lpEt.setE(E1.C1);

        String json = lpEt.toJson();

        Enums.EnumTest1Optional.Builder builder = Enums.EnumTest1Optional.newBuilder();
        JsonFormat.parser().merge(json, builder);

        assertEquals(Enums.E1.C1, builder.build().getE());
    }

    @Test
    public void testEmptyMessageJson() throws Exception {
        Numbers n = new Numbers();
        String json = n.toJson();
        assertEquals("{}", json);
    }

    @Test
    public void testStringEscaping() throws Exception {
        S lpS = new S();
        lpS.setId("hello \"world\"\nnew\tline\\slash");

        String json = lpS.toJson();
        assertTrue(json.contains("\\\"world\\\""));
        assertTrue(json.contains("\\n"));
        assertTrue(json.contains("\\t"));
        assertTrue(json.contains("\\\\"));

        Strings.S.Builder builder = Strings.S.newBuilder();
        JsonFormat.parser().merge(json, builder);
        assertEquals("hello \"world\"\nnew\tline\\slash", builder.build().getId());
    }

    @Test
    public void testMapJsonCrossCompatibility() throws Exception {
        MapMessage lpMap = new MapMessage();
        lpMap.putStringToInt("a", 1);
        lpMap.putStringToInt("b", 2);
        lpMap.putIntToString(10, "ten");
        lpMap.putIntToString(20, "twenty");
        lpMap.putStringToDouble("pi", 3.14);
        lpMap.setName("test-map");

        String json = lpMap.toJson();

        MapsProtos.MapMessage.Builder builder = MapsProtos.MapMessage.newBuilder();
        JsonFormat.parser().merge(json, builder);
        MapsProtos.MapMessage pbMap = builder.build();

        assertEquals(1, pbMap.getStringToIntOrThrow("a"));
        assertEquals(2, pbMap.getStringToIntOrThrow("b"));
        assertEquals("ten", pbMap.getIntToStringOrThrow(10));
        assertEquals("twenty", pbMap.getIntToStringOrThrow(20));
        assertEquals(3.14, pbMap.getStringToDoubleOrThrow("pi"), 0.001);
        assertEquals("test-map", pbMap.getName());
    }

    @Test
    public void testBytesJsonCrossCompatibility() throws Exception {
        B lpB = new B();
        lpB.setPayload(new byte[]{1, 2, 3, 4, 5});

        String json = lpB.toJson();

        Bytes.B.Builder builder = Bytes.B.newBuilder();
        JsonFormat.parser().merge(json, builder);

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, builder.build().getPayload().toByteArray());
    }

    @Test
    public void testJsonMatchesProtobufJsonFormat() throws Exception {
        // Verify the JSON output from LightProto matches protobuf's JsonFormat
        // for a simple message with single-word field names
        X lpX = new X();
        lpX.setA("hello");
        lpX.setB("world");

        Messages.X pbX = Messages.X.newBuilder()
                .setA("hello")
                .setB("world")
                .build();

        String lpJson = lpX.toJson();
        String pbJson = JsonFormat.printer().omittingInsignificantWhitespace().print(pbX);

        assertEquals(pbJson, lpJson);
    }

    // ==================== Round-trip tests (toJson -> parseFromJson) ====================

    @Test
    public void testNumbersRoundTrip() throws Exception {
        Numbers original = new Numbers()
                .setXInt32(42)
                .setXInt64(123456789L)
                .setXUint32(100)
                .setXUint64(200L)
                .setXSint32(-50)
                .setXSint64(-100L)
                .setXFixed32(1000)
                .setXFixed64(2000L)
                .setXSfixed32(-1000)
                .setXSfixed64(-2000L)
                .setXFloat(3.14f)
                .setXDouble(2.71828)
                .setXBool(true)
                .setEnum1(Enum1.X1_1)
                .setEnum2(Numbers.Enum2.X2_2);

        String json = original.toJson();
        Numbers parsed = new Numbers();
        parsed.parseFromJson(json);

        assertArrayEquals(original.toByteArray(), parsed.toByteArray());
    }

    @Test
    public void testStringRoundTrip() throws Exception {
        S original = new S();
        original.setId("hello-world");
        original.addName("alice");
        original.addName("bob");

        String json = original.toJson();
        S parsed = new S();
        parsed.parseFromJson(json);

        assertArrayEquals(original.toByteArray(), parsed.toByteArray());
    }

    @Test
    public void testNestedMessageRoundTrip() throws Exception {
        M original = new M();
        original.setX().setA("value-a").setB("value-b");
        original.addItem().setK("key1").setV("val1");
        original.addItem().setK("key2").setV("val2").setXx().setN(42);

        String json = original.toJson();
        M parsed = new M();
        parsed.parseFromJson(json);

        assertArrayEquals(original.toByteArray(), parsed.toByteArray());
    }

    @Test
    public void testBytesRoundTrip() throws Exception {
        B original = new B();
        original.setPayload(new byte[]{1, 2, 3, 4, 5});

        String json = original.toJson();
        B parsed = new B();
        parsed.parseFromJson(json);

        assertArrayEquals(original.toByteArray(), parsed.toByteArray());
    }

    @Test
    public void testMapRoundTrip() throws Exception {
        MapMessage original = new MapMessage();
        original.putStringToInt("a", 1);
        original.putStringToInt("b", 2);
        original.putIntToString(10, "ten");
        original.putIntToString(20, "twenty");
        original.putStringToDouble("pi", 3.14);
        original.setName("test-map");

        String json = original.toJson();
        MapMessage parsed = new MapMessage();
        parsed.parseFromJson(json);

        assertArrayEquals(original.toByteArray(), parsed.toByteArray());
    }

    @Test
    public void testEmptyMessageRoundTrip() throws Exception {
        Numbers original = new Numbers();
        String json = original.toJson();
        assertEquals("{}", json);

        Numbers parsed = new Numbers();
        parsed.parseFromJson(json);
        assertArrayEquals(original.toByteArray(), parsed.toByteArray());
    }

    @Test
    public void testStringEscapingRoundTrip() throws Exception {
        S original = new S();
        original.setId("hello \"world\"\nnew\tline\\slash");

        String json = original.toJson();
        S parsed = new S();
        parsed.parseFromJson(json);

        assertEquals(original.getId(), parsed.getId());
    }

    // ==================== Cross-compat: protobuf JSON -> LightProto parseFromJson ====================

    @Test
    public void testParseProtobufJson() throws Exception {
        NumbersOuterClass.Numbers pbNumbers = NumbersOuterClass.Numbers.newBuilder()
                .setXInt32(42)
                .setXInt64(123456789L)
                .setXUint32(100)
                .setXUint64(200L)
                .setXSint32(-50)
                .setXSint64(-100L)
                .setXFixed32(1000)
                .setXFixed64(2000L)
                .setXSfixed32(-1000)
                .setXSfixed64(-2000L)
                .setXFloat(3.14f)
                .setXDouble(2.71828)
                .setXBool(true)
                .setEnum1(NumbersOuterClass.Enum1.X1_1)
                .setEnum2(NumbersOuterClass.Numbers.Enum2.X2_2)
                .build();

        String pbJson = JsonFormat.printer().omittingInsignificantWhitespace().print(pbNumbers);

        Numbers lpNumbers = new Numbers();
        lpNumbers.parseFromJson(pbJson);

        assertEquals(42, lpNumbers.getXInt32());
        assertEquals(123456789L, lpNumbers.getXInt64());
        assertEquals(100, lpNumbers.getXUint32());
        assertEquals(200L, lpNumbers.getXUint64());
        assertEquals(-50, lpNumbers.getXSint32());
        assertEquals(-100L, lpNumbers.getXSint64());
        assertEquals(1000, lpNumbers.getXFixed32());
        assertEquals(2000L, lpNumbers.getXFixed64());
        assertEquals(-1000, lpNumbers.getXSfixed32());
        assertEquals(-2000L, lpNumbers.getXSfixed64());
        assertEquals(3.14f, lpNumbers.getXFloat(), 0.001f);
        assertEquals(2.71828, lpNumbers.getXDouble(), 0.00001);
        assertTrue(lpNumbers.isXBool());
        assertEquals(Enum1.X1_1, lpNumbers.getEnum1());
        assertEquals(Numbers.Enum2.X2_2, lpNumbers.getEnum2());
    }

    @Test
    public void testParseProtobufJsonNestedMessage() throws Exception {
        Messages.M pbM = Messages.M.newBuilder()
                .setX(Messages.X.newBuilder().setA("value-a").setB("value-b"))
                .addItems(Messages.M.KV.newBuilder().setK("key1").setV("val1"))
                .addItems(Messages.M.KV.newBuilder().setK("key2").setV("val2")
                        .setXx(Messages.M.KV.XX.newBuilder().setN(42)))
                .build();

        String pbJson = JsonFormat.printer().omittingInsignificantWhitespace().print(pbM);

        M lpM = new M();
        lpM.parseFromJson(pbJson);

        assertEquals("value-a", lpM.getX().getA());
        assertEquals("value-b", lpM.getX().getB());
        assertEquals(2, lpM.getItemsCount());
        assertEquals("key1", lpM.getItemAt(0).getK());
        assertEquals("val1", lpM.getItemAt(0).getV());
        assertEquals("key2", lpM.getItemAt(1).getK());
        assertEquals("val2", lpM.getItemAt(1).getV());
        assertEquals(42, lpM.getItemAt(1).getXx().getN());
    }

    @Test
    public void testParseFromJsonByteBuf() throws Exception {
        Numbers original = new Numbers()
                .setXInt32(99)
                .setXBool(true);

        String json = original.toJson();
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Numbers parsed = new Numbers();
        parsed.parseFromJson(buf);

        assertEquals(99, parsed.getXInt32());
        assertTrue(parsed.isXBool());
        buf.release();
    }

    @Test
    public void testUnknownFieldsIgnored() throws Exception {
        // JSON with extra unknown fields should be silently ignored
        String json = "{\"xInt32\":42,\"unknownField\":\"ignored\",\"xBool\":true}";
        Numbers parsed = new Numbers();
        parsed.parseFromJson(json);

        assertEquals(42, parsed.getXInt32());
        assertTrue(parsed.isXBool());
    }
}
