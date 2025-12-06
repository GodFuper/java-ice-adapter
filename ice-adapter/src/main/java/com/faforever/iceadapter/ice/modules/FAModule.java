package com.faforever.iceadapter.ice.modules;

import com.faforever.iceadapter.ice.ModuleBase;

import java.net.DatagramSocket;

public interface FAModule extends ModuleBase {

    DatagramSocket getSocket();

}
