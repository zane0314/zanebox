package com.zane.zanebox

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** A separate launcher task keeps icon aliases out of the running MainActivity task. */
class LauncherActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(intent).setClass(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
