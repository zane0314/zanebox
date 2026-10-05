package com.zane.zanebox.runtime;
import com.zane.zanebox.runtime.IRuntimeCallback;
interface IRuntime { String getSnapshot(); void registerCallback(IRuntimeCallback callback); void unregisterCallback(IRuntimeCallback callback); oneway void command(String action,String payload); }
