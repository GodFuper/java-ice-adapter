package com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework;

import io.netty.buffer.ByteBuf;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public interface IceKcpOutput {

    void out(ByteBuf data, IceKcp kcp);

}
