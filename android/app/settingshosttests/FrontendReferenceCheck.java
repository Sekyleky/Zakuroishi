package io.github.zakuro.android;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class FrontendReferenceCheck {
    private static final String ID="00040000001B5100";
    private static void require(boolean ok) { if(!ok) throw new AssertionError(); }
    private static String read(Path file,byte[] value) throws Exception {
        Files.write(file,value);
        try(FileChannel channel=FileChannel.open(file)) { return FrontendReference.read(channel); }
    }
    public static void main(String[] args) throws Exception {
        Path folder=Paths.get(args.length>0?args[0]:System.getProperty("java.io.tmpdir"));
        Path file=Files.createTempFile(folder,"zakuro-reference-", ".zkr");
        try {
            byte[] good=FrontendReference.encode(ID);
            require(read(file,good).equals(ID));
            byte[] rom=NativeFixture.rom(0xA00,Long.parseUnsignedLong(ID,16));
            require(read(file,rom).equals(ID));
            byte[] cartridge=new byte[0xC00];
            java.nio.ByteBuffer header=java.nio.ByteBuffer.wrap(cartridge).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            header.putInt(0x100,0x4453434e); header.putInt(0x120,1); header.putInt(0x124,5);
            System.arraycopy(rom,0,cartridge,512,rom.length);
            require(read(file,cartridge).equals(ID));
            require(read(file,(FrontendReference.MAGIC+ID.toLowerCase()+"\n").getBytes(StandardCharsets.US_ASCII)).equals(ID));
            for(byte[] bad:new byte[][] {new byte[0],Arrays.copyOf(good,good.length-1),Arrays.copyOf(good,good.length+1),
                    (FrontendReference.MAGIC+"../../title.so\n").getBytes(StandardCharsets.US_ASCII),
                    (FrontendReference.MAGIC+"00040000001B510Z\n").getBytes(StandardCharsets.US_ASCII)}) {
                try { read(file,bad); throw new AssertionError("Accepted unsafe/truncated reference"); }
                catch(java.io.IOException expected) { }
            }
            require(FrontendExport.filename("../Bad:/\\Name\n",ID).endsWith("["+ID+"].zkr"));
            require(!FrontendExport.filename("../Bad:/\\Name\n",ID).contains("/"));
            require(!FrontendExport.filename("../Bad:/\\Name\n",ID).contains("\\"));
            try { FrontendReference.encode("../"); throw new AssertionError(); } catch(java.io.IOException expected) { }
        } finally { Files.deleteIfExists(file); }
        System.out.println("Frontend reference checks passed: round trip, malformed IDs, bounds and safe filenames.");
    }
}
