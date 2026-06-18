package com.hermes.agent

import android.app.Application
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class HermesApp : Application() {

    companion object {
        private const val TAG = "HermesApp"
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化 Chaquopy Python 运行时
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // 测试 Python 桥接
        try {
            val py = Python.getInstance()
            val bridge = py.getModule("hermes_bridge")
            val version = bridge.callAttr("get_version").toString()
            Log.i(TAG, "Hermes version: $version")

            val info = bridge.callAttr("get_python_info").asMap()
            Log.i(TAG, "Python info: $info")

            // Test core imports
            val imports = bridge.callAttr("test_imports").asMap()
            for ((mod, result) in imports) {
                val status = if (result.toString() == "OK") "✅" else "❌"
                Log.i(TAG, "  $status $mod: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Python bridge init failed", e)
        }
    }
}