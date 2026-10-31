package io.github.zakuro.android;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class DriverPackageCheck {
    static void require(boolean value) { if(!value) throw new AssertionError(); }
    static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)) {
            for(String name:names) { zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[]{1,2,3}); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    static void rejects(byte[] zip) throws IOException {
        Path folder=Files.createTempDirectory("driver-reject-");
        try {
            try { Launcher.extractDriver(new ByteArrayInputStream(zip),folder.toFile()); throw new AssertionError("Unsafe ZIP accepted"); }
            catch(IOException expected) {}
        } finally { remove(folder); }
    }
    static void remove(Path folder) throws IOException {
        try(java.util.stream.Stream<Path> paths=Files.walk(folder)) {
            for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) { path.toFile().setWritable(true); Files.delete(path); }
        }
    }
    static void rejectMetadata(String name,int api,int schema) throws Exception {
        try { GameSettings.validateDriverMetadata(name,api,schema,35); throw new AssertionError("Invalid driver metadata accepted"); }
        catch(IOException expected) {}
    }
    public static void main(String[] args) throws Exception {
        GameSettings.validateDriverMetadata("vulkan_driver-1.so",30,1,35);
        rejectMetadata("driver+extra.so",30,1); rejectMetadata("../driver.so",30,1);
        rejectMetadata("driver.so",36,1); rejectMetadata("driver.so",-1,1); rejectMetadata("driver.so",30,2);
        rejects(zip("../outside.so")); rejects(zip("/outside.so")); rejects(zip("C:/outside.so"));
        rejects(zip("nested\\outside.so")); rejects(zip());
        String[] many=new String[513]; for(int i=0;i<many.length;i++) many[i]="entry"+i;
        rejects(zip(many));
        Path folder=Files.createTempDirectory("driver-valid-");
        try { Launcher.extractDriver(new ByteArrayInputStream(zip("meta.json","libvulkan.so","licenses/LICENSE")),folder.toFile());
            require(Arrays.equals(Files.readAllBytes(folder.resolve("libvulkan.so")),new byte[]{1,2,3}));
            require(Files.isRegularFile(folder.resolve("licenses/LICENSE")));
        } finally { remove(folder); }
        byte[] header=new byte[64]; header[0]=127; header[1]='E'; header[2]='L'; header[3]='F'; header[4]=2; header[5]=1; header[16]=3; header[18]=(byte)183;
        require(Launcher.arm64Driver(header)); header[18]=62; require(!Launcher.arm64Driver(header)); require(!Launcher.arm64Driver(new byte[10]));
        System.out.println("Driver package checks passed: ZIP traversal, entry limit, read-only synced extraction, ARM64 header, basename/minApi/schema validation.");
    }
}
