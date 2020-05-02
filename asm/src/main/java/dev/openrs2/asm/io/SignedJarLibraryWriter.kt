package dev.openrs2.asm.io

import dev.openrs2.asm.classpath.ClassPath
import dev.openrs2.asm.classpath.Library
import dev.openrs2.crypto.Pkcs12KeyStore
import dev.openrs2.util.io.DeterministicJarOutputStream
import dev.openrs2.util.io.entries
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarInputStream
import java.util.jar.Manifest

class SignedJarLibraryWriter(
    private val manifest: Manifest,
    private val keyStore: Pkcs12KeyStore
) : LibraryWriter {
    override fun write(output: OutputStream, classPath: ClassPath, library: Library) {
        val unsignedJar = Files.createTempFile(TEMP_PREFIX, JAR_SUFFIX)
        try {
            Files.newOutputStream(unsignedJar).use { unsignedOutput ->
                ManifestJarLibraryWriter(manifest).write(unsignedOutput, classPath, library)
            }

            val signedJar = Files.createTempFile(TEMP_PREFIX, JAR_SUFFIX)
            try {
                keyStore.signJar(unsignedJar, signedJar)
                repack(signedJar, output)
            } finally {
                Files.deleteIfExists(signedJar)
            }
        } finally {
            Files.deleteIfExists(unsignedJar)
        }
    }

    private fun repack(signedJar: Path, output: OutputStream) {
        JarInputStream(Files.newInputStream(signedJar)).use { input ->
            DeterministicJarOutputStream(output, input.manifest).use { output ->
                for (entry in input.entries) {
                    output.putNextEntry(entry)
                    input.copyTo(output)
                }
            }
        }
    }

    private companion object {
        private const val TEMP_PREFIX = "tmp"
        private const val JAR_SUFFIX = ".jar"
    }
}