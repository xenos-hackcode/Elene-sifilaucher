package de.blinkt.openvpn.api;
import android.content.Intent;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;
// Interoperable subset of OpenVPN for Android's published external API.
// Explicit zero-based method IDs preserve its wire protocol (never renumber).
// Contract: https://github.com/schwabe/ics-openvpn/tree/master/main/src/main/aidl/de/blinkt/openvpn/api
interface IOpenVPNAPIService {
    void startVPN(in String inlineconfig) = 3;
    Intent prepare(in String packagename) = 4;
    Intent prepareVPNService() = 5;
    void disconnect() = 6;
    void registerStatusCallback(in IOpenVPNStatusCallback callback) = 9;
    void unregisterStatusCallback(in IOpenVPNStatusCallback callback) = 10;
}
