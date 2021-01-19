package org.openrs2.deob.bytecode

import com.github.ajalt.clikt.core.CliktCommand
import com.google.inject.Guice
import java.nio.file.Path

public fun main(args: Array<String>): Unit = DeobfuscateBytecodeCommand().main(args)

public class DeobfuscateBytecodeCommand : CliktCommand(name = "bytecode") {
    override fun run() {
        val injector = Guice.createInjector(BytecodeDeobfuscatorModule)
        val deobfuscator = injector.getInstance(BytecodeDeobfuscator::class.java)
        deobfuscator.run(
            input = Path.of("nonfree/lib"),
            output = Path.of("nonfree/var/cache/deob")
        )
    }
}
