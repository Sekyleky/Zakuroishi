package io.github.zakuro.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;

/** A reference identifies an installed title, never paths, settings or executable code. */
final class FrontendReference {
    static final String MAGIC = "ZAKURO-LAUNCH-1\n";
    static String validId(String id) throws IOException {
        if (id == null || !id.matches("[0-9A-Fa-f]{16}")) throw new IOException("Invalid frontend game ID");
        return id.toUpperCase(java.util.Locale.ROOT);
    }
    static byte[] encode(String id) throws IOException {
        return (MAGIC + validId(id) + "\n").getBytes(StandardCharsets.US_ASCII);
    }
    static String read(SeekableByteChannel source) throws IOException {
        ByteBuffer prefix=ByteBuffer.allocate(MAGIC.length());
        source.position(0);
        while(prefix.hasRemaining()) {
            if(source.read(prefix) <= 0) throw new IOException("Truncated frontend reference");
        }
        if (!new String(prefix.array(),StandardCharsets.US_ASCII).equals(MAGIC)) return ImportPlan.title(source);
        if(source.size() != MAGIC.length()+17) throw new IOException("Invalid frontend reference length");
        ByteBuffer value=ByteBuffer.allocate(17);
        while(value.hasRemaining()) if(source.read(value)<=0) throw new IOException("Truncated frontend reference");
        byte[] bytes=value.array();
        if(bytes[16]!='\n') throw new IOException("Invalid frontend reference terminator");
        return validId(new String(bytes,0,16,StandardCharsets.US_ASCII));
    }
}
