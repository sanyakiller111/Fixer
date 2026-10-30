package com.october.fixer

/** JNI binding to hev-socks5-tunnel (class name fixed at build time via PKGNAME/CLSNAME, see native/Application.mk). */
object TProxy {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    @JvmStatic external fun TProxyGetStats(): LongArray
}
