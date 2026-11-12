package com.tyranor.runtime.systemnnn;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Paths;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public final class SystemNnnLockHolder {
    public static void main(String[] args) throws IOException {
        try (FileChannel channel = FileChannel.open(Paths.get(args[0]), CREATE, WRITE);
             FileLock lock = channel.lock()) {
            System.out.println("locked");
            System.out.flush();
            System.in.read();
        }
    }
}
