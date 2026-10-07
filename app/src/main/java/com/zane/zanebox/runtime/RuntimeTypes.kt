package com.zane.zanebox.runtime

/** Numeric codes/command wires are retained for the existing Binder and snapshot format. */
enum class RuntimeState(val code:Int) {
    STOPPED(0),STARTING(1),CONNECTED(2),STOPPING(3),FAILED(4);
    companion object {fun fromCode(code:Int)=entries.firstOrNull {it.code==code} ?: FAILED}
}
internal enum class RuntimeCommand(val wire:String) {
    START("start"),
    STOP("stop"),
    RELOAD("reload"),
    AUTO_RELOAD("autoReload"),
    SUBSCRIPTION_UPDATED("subscriptionUpdated"),
    LIVE_SETTINGS("liveSettings"),
    SELECT("select"),
    SELECT_AUTO("selectAuto"),
    RESTORE("restore"),
    VALIDATE("validate"),
    TEST("test"),
    CANCEL_TESTS("cancelTests"),
    LOGS("logs"),
    CLEAR_LOGS("clearLogs"),
    SYSTEM_LOGS("systemLogs"),
    WAKE_RESET("wakeReset"),
    ASSET("asset"),
    IP("ip"),
    TRAFFIC("traffic"),
    RESET_TRAFFIC("resetTraffic"),
    STATS("stats"),
    CONNECTIONS("connections"),
    PANEL("panel"),
    CLOSE_CONNECTION("closeConnection"),
    CLOSE_ALL_CONNECTIONS("closeAllConnections"),
    SPEED("speed"),
    CANCEL_SPEED("cancelSpeed"),
    STUN("stun"),
    CANCEL_STUN("cancelStun");
    companion object {fun fromWire(wire:String)=entries.firstOrNull {it.wire==wire}}
}
