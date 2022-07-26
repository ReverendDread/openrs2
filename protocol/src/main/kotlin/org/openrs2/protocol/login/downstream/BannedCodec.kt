package org.openrs2.protocol.login.downstream

import org.openrs2.protocol.EmptyPacketCodec
import javax.inject.Singleton

@Singleton
public class BannedCodec : EmptyPacketCodec<LoginResponse.Banned>(
    packet = LoginResponse.Banned,
    opcode = 4
)