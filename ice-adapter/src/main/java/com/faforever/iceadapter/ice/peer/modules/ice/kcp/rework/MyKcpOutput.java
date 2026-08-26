package com.faforever.iceadapter.ice.peer.modules.ice.kcp.rework;

import io.netty.buffer.ByteBuf;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public interface MyKcpOutput {

    void out(ByteBuf data, MyKcp kcp);

}
