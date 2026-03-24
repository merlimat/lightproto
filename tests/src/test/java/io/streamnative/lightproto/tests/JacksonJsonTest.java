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
}
