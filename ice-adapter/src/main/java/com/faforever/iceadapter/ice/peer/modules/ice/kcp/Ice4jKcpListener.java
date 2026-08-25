package com.faforever.iceadapter.ice.peer.modules.ice.kcp;

import io.netty.buffer.ByteBuf;
import kcp.KcpListener;
import kcp.Ukcp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class Ice4jKcpListener implements KcpListener {
    private final Consumer<byte[]> handleData;

    @Override
    public void onConnected(Ukcp ukcp) {
        log.info("Connected Ukcp {}", ukcp);
    }

    @Override
    public void handleReceive(ByteBuf buf, Ukcp ukcp) {
        if (buf.readableBytes() > 0) {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);
            if (handleData != null) {
                handleData.accept(data);
            }
        }
    }

    @Override
    public void handleException(Throwable ex, Ukcp ukcp) {
        log.error("Error on ukcp {}", ukcp, ex);
    }

    @Override
    public void handleClose(Ukcp ukcp) {
        log.info("Connected close {}", ukcp);
    }
}
