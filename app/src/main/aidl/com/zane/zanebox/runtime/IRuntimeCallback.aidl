package com.zane.zanebox.runtime;
interface IRuntimeCallback { oneway void onSnapshot(String json); oneway void onEvent(String kind,String payload); }
