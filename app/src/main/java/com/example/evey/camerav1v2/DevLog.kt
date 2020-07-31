package com.example.evey.camerav1v2

import android.util.Log

object DevLog {

    private var tag = "v1v2tag"

    fun v(msg: String) {
        Log.v(tag, msg)
    }

    fun e(msg: String) {
        Log.e(tag, msg)
    }

    fun i(msg: String) {
        val callersStackTraceElement =
            Thread.currentThread().stackTrace[3]
        val className = callersStackTraceElement.className
        val canonicalName: String = className.substring(className.lastIndexOf(".") + 1)
        val methodName = callersStackTraceElement.methodName + "()"
        Log.i(tag, "[ $canonicalName $methodName ] $msg << Thread:${Thread.currentThread().name}")
    }

    fun d(msg: String) {
        Log.d(tag, msg + " << Thread:${Thread.currentThread().name}")
    }

    fun w(msg: String) {
        Log.w(tag, msg)
    }

    fun printStackTrace(e: Throwable) {
        e.printStackTrace()
    }
}
