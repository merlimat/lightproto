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
import io.streamnative.lightproto.tests.importer.Container;
import io.streamnative.lightproto.tests.importer.ImporterProtos;
import io.streamnative.lightproto.tests.imported.ImportedProtos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests JSON serialization/deserialization for messages that import types
 * from a different protobuf package (and therefore a different Java package).
 */
public class CrossPackageJsonTest {

    @Test
    public void testCrossPackageJsonRoundTrip() throws Exception {
        Container original = new Container();
        original.setLabel("test-container");
        original.setItem().setName("single-item").setValue(42);
        original.addItem().setName("item-1").setValue(1);
        original.addItem().setName("item-2").setValue(2);

        String json = original.toJson();
        Container parsed = new Container();
        parsed.parseFromJson(json);

        assertEquals("test-container", parsed.getLabel());
        assertEquals("single-item", parsed.getItem().getName());
        assertEquals(42, parsed.getItem().getValue());
        assertEquals(2, parsed.getItemsCount());
        assertEquals("item-1", parsed.getItemAt(0).getName());
        assertEquals(1, parsed.getItemAt(0).getValue());
        assertEquals("item-2", parsed.getItemAt(1).getName());
        assertEquals(2, parsed.getItemAt(1).getValue());
    }

    @Test
    public void testCrossPackageJsonCrossCompatibility() throws Exception {
        Container lpContainer = new Container();
        lpContainer.setLabel("my-container");
        lpContainer.setItem().setName("shared").setValue(99);
        lpContainer.addItem().setName("a").setValue(1);
        lpContainer.addItem().setName("b").setValue(2);

        String json = lpContainer.toJson();

        ImporterProtos.Container.Builder builder = ImporterProtos.Container.newBuilder();
        JsonFormat.parser().merge(json, builder);
        ImporterProtos.Container pbContainer = builder.build();

        assertEquals("my-container", pbContainer.getLabel());
        assertEquals("shared", pbContainer.getItem().getName());
        assertEquals(99, pbContainer.getItem().getValue());
        assertEquals(2, pbContainer.getItemsCount());
        assertEquals("a", pbContainer.getItems(0).getName());
        assertEquals(1, pbContainer.getItems(0).getValue());
        assertEquals("b", pbContainer.getItems(1).getName());
        assertEquals(2, pbContainer.getItems(1).getValue());
    }

    @Test
    public void testCrossPackageParseProtobufJson() throws Exception {
        ImporterProtos.Container pbContainer = ImporterProtos.Container.newBuilder()
                .setLabel("from-protobuf")
                .setItem(ImportedProtos.SharedItem.newBuilder().setName("pb-item").setValue(77))
                .addItems(ImportedProtos.SharedItem.newBuilder().setName("x").setValue(10))
                .build();

        String pbJson = JsonFormat.printer().omittingInsignificantWhitespace().print(pbContainer);

        Container lpContainer = new Container();
        lpContainer.parseFromJson(pbJson);

        assertEquals("from-protobuf", lpContainer.getLabel());
        assertEquals("pb-item", lpContainer.getItem().getName());
        assertEquals(77, lpContainer.getItem().getValue());
        assertEquals(1, lpContainer.getItemsCount());
        assertEquals("x", lpContainer.getItemAt(0).getName());
        assertEquals(10, lpContainer.getItemAt(0).getValue());
    }

    @Test
    public void testCrossPackageBinaryRoundTrip() throws Exception {
        Container original = new Container();
        original.setLabel("binary-test");
        original.setItem().setName("bin-item").setValue(123);
        original.addItem().setName("r1").setValue(10);

        byte[] bytes = original.toByteArray();
        Container parsed = new Container();
        parsed.parseFrom(io.netty.buffer.Unpooled.wrappedBuffer(bytes), bytes.length);

        assertEquals("binary-test", parsed.getLabel());
        assertEquals("bin-item", parsed.getItem().getName());
        assertEquals(123, parsed.getItem().getValue());
        assertEquals(1, parsed.getItemsCount());
        assertEquals("r1", parsed.getItemAt(0).getName());
        assertEquals(10, parsed.getItemAt(0).getValue());
    }
}
