package com.faforever.iceadapter.ice;

public interface ConnectivityModule extends ModuleBase {

    char COMMAND_ECHO = 'e';

    void onEchoReceived(byte[] data, int length);

    long getEchosReceived();

    long getInvalidEchosReceived();

}
