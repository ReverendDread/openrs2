package dev.openrs2.bundler

import com.google.inject.AbstractModule
import dev.openrs2.crypto.CryptoModule

class BundlerModule : AbstractModule() {
    override fun configure() {
        install(CryptoModule())
    }
}
