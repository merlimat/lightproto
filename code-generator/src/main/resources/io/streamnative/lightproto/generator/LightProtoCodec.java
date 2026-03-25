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
package io.streamnative.lightproto.generator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

class LightProtoCodec {

    private static final boolean HAS_UNSAFE;
    private static final long STRING_VALUE_OFFSET;
    static final long BYTE_ARRAY_BASE_OFFSET;
    static final boolean LITTLE_ENDIAN = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN;
    // True when JDK compact strings are enabled (default since JDK 9).
    // When disabled via -XX:-CompactStrings, String's internal byte[] uses UTF-16
    // and we must not use the Unsafe string fast paths.
    private static final boolean COMPACT_STRINGS;

    // MethodHandles for Unsafe operations, resolved via reflection to avoid
    // referencing sun.misc.Unsafe as a type (which triggers javac warnings).
    // HotSpot inlines invokeExact on static final MethodHandles.
    private static final MethodHandle MH_PUT_BYTE;
    private static final MethodHandle MH_PUT_INT;
    private static final MethodHandle MH_PUT_LONG;
    private static final MethodHandle MH_GET_OBJECT;
    private static final MethodHandle MH_PUT_OBJECT;
    private static final MethodHandle MH_COPY_MEMORY;
    private static final MethodHandle MH_GET_LONG;
    private static final MethodHandle MH_ALLOCATE_INSTANCE;

    static {
        boolean hasUnsafe = false;
        long offset = -1;
        long arrayBase = -1;
        boolean compactStrings = false;
        MethodHandle mhPutByte = null;
        MethodHandle mhPutInt = null;
        MethodHandle mhPutLong = null;
        MethodHandle mhGetObject = null;
        MethodHandle mhPutObject = null;
        MethodHandle mhCopyMemory = null;
        MethodHandle mhGetLong = null;
        MethodHandle mhAllocateInstance = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);

            // Use reflection for init-only operations
            Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            Method arrayBaseOffsetMethod = unsafeClass.getMethod("arrayBaseOffset", Class.class);
            Method getObjectMethod = unsafeClass.getMethod("getObject", Object.class, long.class);

            offset = (long) objectFieldOffset.invoke(unsafe, String.class.getDeclaredField("value"));
            arrayBase = (int) arrayBaseOffsetMethod.invoke(unsafe, byte[].class);
            // Detect compact strings: an ASCII string's internal byte[] length
            // equals the string length when compact strings are enabled (LATIN1 coder),
            // but is 2x the string length when disabled (UTF-16 coder).
            byte[] testValue = (byte[]) getObjectMethod.invoke(unsafe, "a", offset);
            compactStrings = (testValue.length == 1);

            // Create MethodHandles for hot-path operations, bound to the Unsafe instance
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            mhPutByte = lookup.unreflect(
                    unsafeClass.getMethod("putByte", Object.class, long.class, byte.class)).bindTo(unsafe);
            mhPutInt = lookup.unreflect(
                    unsafeClass.getMethod("putInt", Object.class, long.class, int.class)).bindTo(unsafe);
            mhPutLong = lookup.unreflect(
                    unsafeClass.getMethod("putLong", Object.class, long.class, long.class)).bindTo(unsafe);
            mhGetObject = lookup.unreflect(getObjectMethod).bindTo(unsafe);
            mhPutObject = lookup.unreflect(
                    unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)).bindTo(unsafe);
            mhCopyMemory = lookup.unreflect(
                    unsafeClass.getMethod("copyMemory", Object.class, long.class, Object.class, long.class, long.class))
                    .bindTo(unsafe);
            mhGetLong = lookup.unreflect(
                    unsafeClass.getMethod("getLong", Object.class, long.class)).bindTo(unsafe);
            mhAllocateInstance = lookup.unreflect(
                    unsafeClass.getMethod("allocateInstance", Class.class)).bindTo(unsafe);
            hasUnsafe = true;
        } catch (Throwable ignore) {
            // Fallback to non-Unsafe path
        }
        HAS_UNSAFE = hasUnsafe;
        STRING_VALUE_OFFSET = offset;
        BYTE_ARRAY_BASE_OFFSET = arrayBase;
        COMPACT_STRINGS = compactStrings;
        MH_PUT_BYTE = mhPutByte;
        MH_PUT_INT = mhPutInt;
        MH_PUT_LONG = mhPutLong;
        MH_GET_OBJECT = mhGetObject;
        MH_PUT_OBJECT = mhPutObject;
        MH_COPY_MEMORY = mhCopyMemory;
        MH_GET_LONG = mhGetLong;
        MH_ALLOCATE_INSTANCE = mhAllocateInstance;
    }

    static final int TAG_TYPE_MASK = 7;
    static final int TAG_TYPE_BITS = 3;
    static final int WIRETYPE_VARINT = 0;
    static final int WIRETYPE_FIXED64 = 1;
    static final int WIRETYPE_LENGTH_DELIMITED = 2;
    static final int WIRETYPE_START_GROUP = 3;
    static final int WIRETYPE_END_GROUP = 4;
    static final int WIRETYPE_FIXED32 = 5;
    private LightProtoCodec() {
    }

    private static int getTagType(int tag) {
        return tag & TAG_TYPE_MASK;
    }

    static int getFieldId(int tag) {
        return tag >>> TAG_TYPE_BITS;
    }

    static void writeVarInt(ByteBuf b, int n) {
        if (n >= 0) {
            _writeVarInt(b, n);
        } else {
            writeVarInt64(b, n);
        }
    }

    static void writeSignedVarInt(ByteBuf b, int n) {
        writeVarInt(b, encodeZigZag32(n));
    }

    static int readSignedVarInt(ByteBuf b) {
        return decodeZigZag32(readVarInt(b));
    }

    static long readSignedVarInt64(ByteBuf b) {
        return decodeZigZag64(readVarInt64(b));
    }

    static void writeFloat(ByteBuf b, float n) {
        writeFixedInt32(b, Float.floatToRawIntBits(n));
    }

    static void writeDouble(ByteBuf b, double n) {
        writeFixedInt64(b, Double.doubleToRawLongBits(n));
    }

    static float readFloat(ByteBuf b) {
        return Float.intBitsToFloat(readFixedInt32(b));
    }

    static double readDouble(ByteBuf b) {
        return Double.longBitsToDouble(readFixedInt64(b));
    }

    private static void _writeVarInt(ByteBuf b, int n) {
        while (true) {
            if ((n & ~0x7F) == 0) {
                b.writeByte(n);
                return;
            } else {
                b.writeByte((n & 0x7F) | 0x80);
                n >>>= 7;
            }
        }
    }

    static void writeVarInt64(ByteBuf b, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                b.writeByte((int) value);
                return;
            } else {
                b.writeByte(((int) value & 0x7F) | 0x80);
                value >>>= 7;
            }
        }
    }

    static void writeFixedInt32(ByteBuf b, int n) {
        b.writeIntLE(n);
    }

    static void writeFixedInt64(ByteBuf b, long n) {
        b.writeLongLE(n);
    }

    static int readFixedInt32(ByteBuf b) {
        return b.readIntLE();
    }

    static long readFixedInt64(ByteBuf b) {
        return b.readLongLE();
    }


    static void writeSignedVarInt64(ByteBuf b, long n) {
        writeVarInt64(b, encodeZigZag64(n));
    }

    private static int encodeZigZag32(final int n) {
        return (n << 1) ^ (n >> 31);
    }

    private static long encodeZigZag64(final long n) {
        return (n << 1) ^ (n >> 63);
    }

    private static int decodeZigZag32(int n) {
        return n >>> 1 ^ -(n & 1);
    }

    private static long decodeZigZag64(long n) {
        return n >>> 1 ^ -(n & 1L);
    }

    static int readVarInt(ByteBuf buf) {
        byte tmp = buf.readByte();
        if (tmp >= 0) {
            return tmp;
        }
        int result = tmp & 0x7f;
        if ((tmp = buf.readByte()) >= 0) {
            result |= tmp << 7;
        } else {
            result |= (tmp & 0x7f) << 7;
            if ((tmp = buf.readByte()) >= 0) {
                result |= tmp << 14;
            } else {
                result |= (tmp & 0x7f) << 14;
                if ((tmp = buf.readByte()) >= 0) {
                    result |= tmp << 21;
                } else {
                    result |= (tmp & 0x7f) << 21;
                    result |= (tmp = buf.readByte()) << 28;
                    if (tmp < 0) {
                        // Discard upper 32 bits.
                        for (int i = 0; i < 5; i++) {
                            if (buf.readByte() >= 0) {
                                return result;
                            }
                        }
                        throw new IllegalArgumentException("Encountered a malformed varint.");
                    }
                }
            }
        }
        return result;
    }

    static long readVarInt64(ByteBuf buf) {
        long result;
        byte tmp = buf.readByte();
        if (tmp >= 0) {
            return tmp;
        }
        result = tmp & 0x7fL;
        if ((tmp = buf.readByte()) >= 0) {
            result |= (long) tmp << 7;
        } else {
            result |= (tmp & 0x7fL) << 7;
            if ((tmp = buf.readByte()) >= 0) {
                result |= (long) tmp << 14;
            } else {
                result |= (tmp & 0x7fL) << 14;
                if ((tmp = buf.readByte()) >= 0) {
                    result |= (long) tmp << 21;
                } else {
                    result |= (tmp & 0x7fL) << 21;
                    if ((tmp = buf.readByte()) >= 0) {
                        result |= (long) tmp << 28;
                    } else {
                        result |= (tmp & 0x7fL) << 28;
                        if ((tmp = buf.readByte()) >= 0) {
                            result |= (long) tmp << 35;
                        } else {
                            result |= (tmp & 0x7fL) << 35;
                            if ((tmp = buf.readByte()) >= 0) {
                                result |= (long) tmp << 42;
                            } else {
                                result |= (tmp & 0x7fL) << 42;
                                if ((tmp = buf.readByte()) >= 0) {
                                    result |= (long) tmp << 49;
                                } else {
                                    result |= (tmp & 0x7fL) << 49;
                                    if ((tmp = buf.readByte()) >= 0) {
                                        result |= (long) tmp << 56;
                                    } else {
                                        result |= (tmp & 0x7fL) << 56;
                                        result |= ((long) buf.readByte()) << 63;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    static int computeSignedVarIntSize(final int value) {
        return computeVarUIntSize(encodeZigZag32(value));
    }

    static int computeSignedVarInt64Size(final long value) {
        return computeVarInt64Size(encodeZigZag64(value));
    }

    static int computeVarIntSize(final int value) {
        if (value < 0) {
            return 10;
        } else {
            return computeVarUIntSize(value);
        }
    }

    static int computeVarUIntSize(final int value) {
        if ((value & (0xffffffff << 7)) == 0) {
            return 1;
        } else if ((value & (0xffffffff << 14)) == 0) {
            return 2;
        } else if ((value & (0xffffffff << 21)) == 0) {
            return 3;
        } else if ((value & (0xffffffff << 28)) == 0) {
            return 4;
        } else {
            return 5;
        }
    }

    static int computeVarInt64Size(final long value) {
        if ((value & (0xffffffffffffffffL << 7)) == 0) {
            return 1;
        } else if ((value & (0xffffffffffffffffL << 14)) == 0) {
            return 2;
        } else if ((value & (0xffffffffffffffffL << 21)) == 0) {
            return 3;
        } else if ((value & (0xffffffffffffffffL << 28)) == 0) {
            return 4;
        } else if ((value & (0xffffffffffffffffL << 35)) == 0) {
            return 5;
        } else if ((value & (0xffffffffffffffffL << 42)) == 0) {
            return 6;
        } else if ((value & (0xffffffffffffffffL << 49)) == 0) {
            return 7;
        } else if ((value & (0xffffffffffffffffL << 56)) == 0) {
            return 8;
        } else if ((value & (0xffffffffffffffffL << 63)) == 0) {
            return 9;
        } else {
            return 10;
        }
    }

    static int computeStringUTF8Size(String s) {
        return ByteBufUtil.utf8Bytes(s);
    }

    static void writeString(ByteBuf b, String s, int bytesCount) {
        if (s.length() == bytesCount) {
            // ASCII fast path: read String's internal byte[] directly via Unsafe,
            // then writeBytes in a single copy with zero intermediate allocation.
            // On JDK 9+ compact strings, ASCII strings use LATIN1 coder and the
            // internal value byte[] contains exactly the bytes we need.
            if (HAS_UNSAFE && COMPACT_STRINGS) {
                try {
                    Object _v = (Object) MH_GET_OBJECT.invokeExact((Object) s, STRING_VALUE_OFFSET);
                    b.writeBytes((byte[]) _v, 0, bytesCount);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            } else {
                b.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
            }
        } else {
            ByteBufUtil.reserveAndWriteUtf8(b, s, bytesCount);
        }
    }

    // --- Unsafe raw write methods for zero-overhead serialization ---
    // These bypass all Netty ByteBuf boundary checks by writing directly to memory.
    // Used by generated writeTo() methods after a single ensureWritable() call.

    static long writeRawByte(Object base, long addr, int value) {
        try {
            MH_PUT_BYTE.invokeExact(base, addr, (byte) value);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return addr + 1;
    }

    static long writeRawVarInt(Object base, long addr, int n) {
        try {
            if (n >= 0) {
                while (true) {
                    if ((n & ~0x7F) == 0) {
                        MH_PUT_BYTE.invokeExact(base, addr++, (byte) n);
                        return addr;
                    }
                    MH_PUT_BYTE.invokeExact(base, addr++, (byte) ((n & 0x7F) | 0x80));
                    n >>>= 7;
                }
            } else {
                return writeRawVarInt64(base, addr, n);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    static long writeRawVarInt64(Object base, long addr, long value) {
        try {
            while (true) {
                if ((value & ~0x7FL) == 0) {
                    MH_PUT_BYTE.invokeExact(base, addr++, (byte) value);
                    return addr;
                }
                MH_PUT_BYTE.invokeExact(base, addr++, (byte) (((int) value & 0x7F) | 0x80));
                value >>>= 7;
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    static long writeRawSignedVarInt(Object base, long addr, int n) {
        return writeRawVarInt(base, addr, encodeZigZag32(n));
    }

    static long writeRawSignedVarInt64(Object base, long addr, long n) {
        return writeRawVarInt64(base, addr, encodeZigZag64(n));
    }

    static long writeRawLittleEndian32(Object base, long addr, int value) {
        try {
            MH_PUT_INT.invokeExact(base, addr, LITTLE_ENDIAN ? value : Integer.reverseBytes(value));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return addr + 4;
    }

    static long writeRawLittleEndian64(Object base, long addr, long value) {
        try {
            MH_PUT_LONG.invokeExact(base, addr, LITTLE_ENDIAN ? value : Long.reverseBytes(value));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return addr + 8;
    }

    static long writeRawFloat(Object base, long addr, float n) {
        return writeRawLittleEndian32(base, addr, Float.floatToRawIntBits(n));
    }

    static long writeRawDouble(Object base, long addr, double n) {
        return writeRawLittleEndian64(base, addr, Double.doubleToRawLongBits(n));
    }

    /**
     * Write an ASCII string directly via Unsafe. Returns new addr on success,
     * or -1 if the string is non-ASCII and needs UTF-8 encoding via ByteBuf.
     */
    static long writeRawString(Object base, long addr, String s, int bytesCount) {
        if (COMPACT_STRINGS && s.length() == bytesCount) {
            try {
                Object _v = (Object) MH_GET_OBJECT.invokeExact((Object) s, STRING_VALUE_OFFSET);
                MH_COPY_MEMORY.invokeExact((Object) _v, BYTE_ARRAY_BASE_OFFSET, base, addr, (long) bytesCount);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
            return addr + bytesCount;
        }
        return -1;
    }

    static String readString(ByteBuf b, int index, int len) {
        if (HAS_UNSAFE && STRING_VALUE_OFFSET >= 0) {
            try {
                // Allocate target byte[] and copy directly from ByteBuf memory,
                // bypassing Netty's getBytes chain (checkIndex, checkRangeBounds, etc.)
                byte[] value = new byte[len];
                if (b.hasMemoryAddress()) {
                    MH_COPY_MEMORY.invokeExact((Object) null, b.memoryAddress() + index,
                            (Object) value, BYTE_ARRAY_BASE_OFFSET, (long) len);
                } else if (b.hasArray()) {
                    MH_COPY_MEMORY.invokeExact((Object) b.array(),
                            BYTE_ARRAY_BASE_OFFSET + b.arrayOffset() + index,
                            (Object) value, BYTE_ARRAY_BASE_OFFSET, (long) len);
                } else {
                    b.getBytes(index, value, 0, len);
                }

                // For ASCII strings (all bytes < 128), create a String directly via Unsafe,
                // injecting the byte[] as the internal value with LATIN1 coder (0).
                // This eliminates the second copy that new String() would do.
                // Only possible when compact strings are enabled (-XX:+CompactStrings, the default).
                if (COMPACT_STRINGS && _isAscii(value, len)) {
                    Object _s = (Object) MH_ALLOCATE_INSTANCE.invokeExact(String.class);
                    MH_PUT_OBJECT.invokeExact(_s, STRING_VALUE_OFFSET, (Object) value);
                    // coder=0 (LATIN1) is already set by zero-initialization from allocateInstance
                    return (String) _s;
                }

                // Non-ASCII or compact strings disabled: decode properly
                return new String(value, 0, len, StandardCharsets.UTF_8);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
        return b.toString(index, len, StandardCharsets.UTF_8);
    }

    private static boolean _isAscii(byte[] bytes, int len) {
        try {
            // Check 8 bytes at a time using long reads — data is in L1 cache from the copy
            int i = 0;
            for (; i + 7 < len; i += 8) {
                if (((long) MH_GET_LONG.invokeExact((Object) bytes, BYTE_ARRAY_BASE_OFFSET + i)
                        & 0x8080808080808080L) != 0) {
                    return false;
                }
            }
            // Check remaining bytes
            for (; i < len; i++) {
                if (bytes[i] < 0) return false;
            }
            return true;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    static void skipUnknownField(int tag, ByteBuf buffer) {
        int tagType = getTagType(tag);
        switch (tagType) {
            case WIRETYPE_VARINT:
                readVarInt(buffer);
                break;
            case WIRETYPE_FIXED64:
                buffer.skipBytes(8);
                break;
            case WIRETYPE_LENGTH_DELIMITED:
                int len = readVarInt(buffer);
                buffer.skipBytes(len);
                break;
            case WIRETYPE_FIXED32:
                buffer.skipBytes(4);
                break;
            default:
                throw new IllegalArgumentException("Invalid unknonwn tag type: " + tagType);
        }
    }

    interface LightProtoMessage {
        int getSerializedSize();
        int writeTo(ByteBuf b);
        int writeJsonTo(ByteBuf b);
        void parseFrom(ByteBuf buffer, int size);
        void parseFrom(byte[] a);
        void parseFromJson(byte[] a);
        void parseFromJson(ByteBuf b);
        void materialize();
    }

    static void parseFromJson(LightProtoMessage msg, String json) {
        msg.parseFromJson(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String toJson(LightProtoMessage msg) {
        ByteBuf buf = io.netty.buffer.Unpooled.buffer(256);
        try {
            msg.writeJsonTo(buf);
            return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            buf.release();
        }
    }

    static final class StringHolder {
        String s;
        int idx;
        int len;
    }

    static final class BytesHolder {
        ByteBuf b;
        int idx;
        int len;
    }

    // ==================== JSON serialization helpers ====================

    private static final byte[] HEX_CHARS = "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    static void writeJsonFieldName(ByteBuf b, String name) {
        b.writeByte('"');
        b.writeCharSequence(name, java.nio.charset.StandardCharsets.US_ASCII);
        b.writeByte('"');
        b.writeByte(':');
    }

    static void writeJsonString(ByteBuf b, String s) {
        b.writeByte('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.writeByte('\\');
                    b.writeByte('"');
                    break;
                case '\\':
                    b.writeByte('\\');
                    b.writeByte('\\');
                    break;
                case '\b':
                    b.writeByte('\\');
                    b.writeByte('b');
                    break;
                case '\f':
                    b.writeByte('\\');
                    b.writeByte('f');
                    break;
                case '\n':
                    b.writeByte('\\');
                    b.writeByte('n');
                    break;
                case '\r':
                    b.writeByte('\\');
                    b.writeByte('r');
                    break;
                case '\t':
                    b.writeByte('\\');
                    b.writeByte('t');
                    break;
                default:
                    if (c < 0x20) {
                        b.writeByte('\\');
                        b.writeByte('u');
                        b.writeByte(HEX_CHARS[(c >> 12) & 0xF]);
                        b.writeByte(HEX_CHARS[(c >> 8) & 0xF]);
                        b.writeByte(HEX_CHARS[(c >> 4) & 0xF]);
                        b.writeByte(HEX_CHARS[c & 0xF]);
                    } else {
                        b.writeCharSequence(String.valueOf(c), java.nio.charset.StandardCharsets.UTF_8);
                    }
            }
        }
        b.writeByte('"');
    }

    static void writeJsonBase64(ByteBuf b, ByteBuf data, int offset, int len) {
        byte[] raw = new byte[len];
        data.getBytes(offset, raw);
        b.writeByte('"');
        b.writeCharSequence(java.util.Base64.getEncoder().encodeToString(raw),
                java.nio.charset.StandardCharsets.US_ASCII);
        b.writeByte('"');
    }

    static void writeJsonAscii(ByteBuf b, String s) {
        b.writeCharSequence(s, java.nio.charset.StandardCharsets.US_ASCII);
    }

    // ==================== JSON parsing utilities ====================

    /**
     * Lightweight recursive-descent JSON reader operating on a byte array.
     * Supports the subset of JSON used by protobuf's JsonFormat.
     */
    static final class JsonReader {
        private final byte[] data;
        private int pos;

        JsonReader(byte[] data) {
            this.data = data;
            this.pos = 0;
        }

        JsonReader(ByteBuf b) {
            this.data = new byte[b.readableBytes()];
            b.getBytes(b.readerIndex(), this.data);
            this.pos = 0;
        }

        void skipWhitespace() {
            while (pos < data.length) {
                byte c = data[pos];
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        byte peek() {
            skipWhitespace();
            if (pos >= data.length) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            return data[pos];
        }

        void expect(byte expected) {
            skipWhitespace();
            if (pos >= data.length || data[pos] != expected) {
                throw new IllegalArgumentException("Expected '" + (char) expected
                        + "' at position " + pos + " but found "
                        + (pos < data.length ? "'" + (char) data[pos] + "'" : "end of input"));
            }
            pos++;
        }

        boolean tryConsume(byte expected) {
            skipWhitespace();
            if (pos < data.length && data[pos] == expected) {
                pos++;
                return true;
            }
            return false;
        }

        /**
         * Read a JSON string value (the opening '"' must be next).
         * Handles escape sequences including unicode escapes.
         */
        String readString() {
            expect((byte) '"');
            StringBuilder sb = new StringBuilder();
            while (pos < data.length) {
                byte c = data[pos++];
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= data.length) {
                        throw new IllegalArgumentException("Unexpected end of JSON in string escape");
                    }
                    byte esc = data[pos++];
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > data.length) {
                                throw new IllegalArgumentException("Incomplete \\u escape");
                            }
                            int cp = Integer.parseInt(new String(data, pos, 4,
                                    java.nio.charset.StandardCharsets.US_ASCII), 16);
                            sb.append((char) cp);
                            pos += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid escape: \\" + (char) esc);
                    }
                } else {
                    // Handle multi-byte UTF-8
                    if ((c & 0x80) == 0) {
                        sb.append((char) c);
                    } else if ((c & 0xE0) == 0xC0) {
                        int c2 = data[pos++] & 0xFF;
                        sb.append((char) (((c & 0x1F) << 6) | (c2 & 0x3F)));
                    } else if ((c & 0xF0) == 0xE0) {
                        int c2 = data[pos++] & 0xFF;
                        int c3 = data[pos++] & 0xFF;
                        sb.append((char) (((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F)));
                    } else if ((c & 0xF8) == 0xF0) {
                        int c2 = data[pos++] & 0xFF;
                        int c3 = data[pos++] & 0xFF;
                        int c4 = data[pos++] & 0xFF;
                        int codePoint = ((c & 0x07) << 18) | ((c2 & 0x3F) << 12)
                                | ((c3 & 0x3F) << 6) | (c4 & 0x3F);
                        sb.appendCodePoint(codePoint);
                    }
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        /**
         * Read a JSON number token as a raw string (for parsing into int/long/float/double).
         * Also handles quoted numbers (protobuf JSON quotes int64 types).
         */
        String readNumberToken() {
            skipWhitespace();
            boolean quoted = false;
            if (pos < data.length && data[pos] == '"') {
                quoted = true;
                pos++;
            }
            int start = pos;
            while (pos < data.length) {
                byte c = data[pos];
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'
                        || (c >= '0' && c <= '9')) {
                    pos++;
                } else {
                    break;
                }
            }
            String token = new String(data, start, pos - start, java.nio.charset.StandardCharsets.US_ASCII);
            if (quoted) {
                expect((byte) '"');
            }
            return token;
        }

        int readInt() {
            return Integer.parseInt(readNumberToken());
        }

        long readLong() {
            return Long.parseLong(readNumberToken());
        }

        float readFloat() {
            skipWhitespace();
            if (pos < data.length && data[pos] == '"') {
                // Handle special float values: "NaN", "Infinity", "-Infinity"
                String s = readString();
                return Float.parseFloat(s);
            }
            return Float.parseFloat(readNumberToken());
        }

        double readDouble() {
            skipWhitespace();
            if (pos < data.length && data[pos] == '"') {
                String s = readString();
                return Double.parseDouble(s);
            }
            return Double.parseDouble(readNumberToken());
        }

        boolean readBool() {
            skipWhitespace();
            if (pos + 4 <= data.length && data[pos] == 't' && data[pos + 1] == 'r'
                    && data[pos + 2] == 'u' && data[pos + 3] == 'e') {
                pos += 4;
                return true;
            }
            if (pos + 5 <= data.length && data[pos] == 'f' && data[pos + 1] == 'a'
                    && data[pos + 2] == 'l' && data[pos + 3] == 's' && data[pos + 4] == 'e') {
                pos += 5;
                return false;
            }
            throw new IllegalArgumentException("Expected 'true' or 'false' at position " + pos);
        }

        /**
         * Read a base64-encoded bytes field value.
         */
        byte[] readBase64Bytes() {
            String encoded = readString();
            return java.util.Base64.getDecoder().decode(encoded);
        }

        /**
         * Skip an unknown JSON value (object, array, string, number, boolean, null).
         */
        void skipValue() {
            skipWhitespace();
            if (pos >= data.length) return;
            byte c = data[pos];
            if (c == '"') {
                readString();
            } else if (c == '{') {
                pos++;
                if (!tryConsume((byte) '}')) {
                    do {
                        readString(); // key
                        expect((byte) ':');
                        skipValue();
                    } while (tryConsume((byte) ','));
                    expect((byte) '}');
                }
            } else if (c == '[') {
                pos++;
                if (!tryConsume((byte) ']')) {
                    do {
                        skipValue();
                    } while (tryConsume((byte) ','));
                    expect((byte) ']');
                }
            } else if (c == 't' || c == 'f') {
                readBool();
            } else if (c == 'n') {
                // null
                if (pos + 4 <= data.length && data[pos + 1] == 'u'
                        && data[pos + 2] == 'l' && data[pos + 3] == 'l') {
                    pos += 4;
                } else {
                    throw new IllegalArgumentException("Invalid token at position " + pos);
                }
            } else {
                // number
                readNumberToken();
            }
        }

        boolean isEof() {
            skipWhitespace();
            return pos >= data.length;
        }
    }
}
