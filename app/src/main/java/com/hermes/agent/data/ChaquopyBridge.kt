package com.hermes.agent.data

import com.chaquo.python.PyObject

/**
 * ChaquopyBridge — 统一处理 Chaquopy Python ↔ Kotlin 类型转换。
 *
 * 核心问题：PyObject.asMap() 返回 Map<PyObject, PyObject>，
 * 不能直接当作 Map<String, Any?> 使用。此工具类封装所有转换。
 */
object ChaquopyBridge {

    /** 将 PyObject dict 转为 Map<String, Any?>，值递归转换为 Java 类型 */
    @Suppress("UNCHECKED_CAST")
    fun PyObject.toKMap(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((k, v) in this.asMap()) {
            val key = k.toString()
            result[key] = convertPy(v)
        }
        return result
    }

    /** 将 PyObject list 转为 List<Any?>，元素递归转换 */
    fun PyObject.toKList(): List<Any?> {
        return this.asList().map { convertPy(it) }
    }

    /** 将任意 PyObject 转为最合适的 Kotlin/Java 类型 */
    private fun convertPy(obj: PyObject?): Any? {
        if (obj == null) return null
        return try {
            obj.toJava()  // Chaquopy 自动转：str→String, int→Int, dict→Map, list→List, None→null
        } catch (_: Exception) {
            try {
                obj.toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    /** 安全调用 Python 方法并返回 Map<String, Any?> */
    fun callMap(py: PyObject, method: String, vararg args: Any?): Map<String, Any?> {
        return try {
            val result = py.callAttr(method, *args)
            if (result is PyObject) result.toKMap() else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 安全调用 Python 方法并返回 List<Any?> */
    fun callList(py: PyObject, method: String, vararg args: Any?): List<Any?> {
        return try {
            val result = py.callAttr(method, *args)
            if (result is PyObject) result.toKList() else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 安全调用 Python 方法并返回 String */
    fun callStr(py: PyObject, method: String, vararg args: Any?): String {
        return try {
            py.callAttr(method, *args)?.toString() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** 安全调用 Python 方法并返回 Int */
    fun callInt(py: PyObject, method: String, vararg args: Any?): Int {
        return try {
            py.callAttr(method, *args)?.toInt() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /** 安全调用 Python 方法并返回 Boolean */
    fun callBool(py: PyObject, method: String, vararg args: Any?): Boolean {
        return try {
            py.callAttr(method, *args)?.toBoolean() ?: false
        } catch (_: Exception) {
            false
        }
    }
}