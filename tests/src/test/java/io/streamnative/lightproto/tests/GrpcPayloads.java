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

import java.util.Random;

/**
 * Builds {@link GrpcPayload} messages with every field set, from content derived from a seed,
 * so that each message is distinct and can be rebuilt for comparison.
 */
final class GrpcPayloads {

    private GrpcPayloads() {
    }

    static GrpcPayload create(int seed, int dataSize) {
        GrpcPayload p = new GrpcPayload();
        p.setName("payload-" + seed);
        p.setData(bytes(seed, dataSize));
        p.setNested().setKey("nested-" + seed).setValue(bytes(seed + 1, 16));
        p.addTag("tag-" + seed);
        p.addTag("non-ascii-é→-" + seed);
        p.addChunk(bytes(seed + 2, 8));
        p.addChunk(bytes(seed + 3, 24));
        p.addItem().setKey("first-" + seed).setValue(bytes(seed + 4, 4));
        p.addItem().setKey("second-" + seed).setValue(bytes(seed + 5, 12));
        p.putLabels("label-" + seed, "value-" + seed);
        p.putBlobs("blob-" + seed, bytes(seed + 6, 10));
        p.putIndex("index-" + seed).setKey("indexed-" + seed).setValue(bytes(seed + 7, 6));
        return p;
    }

    private static byte[] bytes(int seed, int size) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }
}
