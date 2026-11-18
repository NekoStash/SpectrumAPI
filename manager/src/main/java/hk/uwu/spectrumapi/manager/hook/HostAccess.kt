package hk.uwu.spectrumapi.manager.hook

import android.os.Bundle

/** Reflection is restricted to the fields/methods verified in the target SystemUI/plugin. */
object HostAccess {
    @JvmStatic
    fun field(owner: Any?, name: String?): Any? {
        if (owner == null) return null
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField(name)
                field.isAccessible = true
                return field.get(owner)
            } catch (_: NoSuchFieldException) {
                // The base holder owns some view fields.
            } catch (_: ReflectiveOperationException) {
                return null
            } catch (_: RuntimeException) {
                return null
            }
            type = type.superclass
        }
        return null
    }

    @JvmStatic
    fun call(owner: Any?, name: String?): Any? {
        if (owner == null) return null
        return try {
            val method = owner.javaClass.getMethod(name)
            method.isAccessible = true
            method.invoke(owner)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    @JvmStatic
    fun string(value: Any?): String? = value as? String

    @JvmStatic
    fun integer(value: Any?, fallback: Int): Int = if (value is Number) value.toInt() else fallback

    @JvmStatic
    fun extras(data: Any?): Bundle? = call(data, "getExtras") as? Bundle
}
