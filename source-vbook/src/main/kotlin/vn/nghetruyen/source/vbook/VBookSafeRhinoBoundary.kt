package vn.nghetruyen.source.vbook

import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.WrapFactory
import java.lang.reflect.Array as ReflectArray





internal object VBookSafeRhinoBoundary {
    fun installCurrentContext() {
        val cx = Context.getCurrentContext() ?: return
        cx.setWrapFactory(SafeHostWrapFactory)
    }

    private object SafeHostWrapFactory : WrapFactory() {
        override fun wrap(cx: Context, scope: Scriptable, obj: Any?, staticType: Class<*>?): Any? = when (obj) {
            null -> null
            is Scriptable -> obj
            is String -> obj
            is CharSequence -> obj.toString()
            is Number, is Boolean, is Char -> obj
            is Map<*, *> -> mapObject(cx, scope, obj)
            is Iterable<*> -> arrayObject(cx, scope, obj.toList())
            else -> when {
                obj.javaClass.isArray -> arrayObject(cx, scope, (0 until ReflectArray.getLength(obj)).map { ReflectArray.get(obj, it) })
                else -> super.wrap(cx, scope, obj, staticType)
            }
        }

        private fun mapObject(cx: Context, scope: Scriptable, values: Map<*, *>): Scriptable =
            cx.newObject(scope).also { output ->
                values.entries.forEach { (key, value) ->
                    if (key != null) ScriptableObject.putProperty(output, key.toString(), safeValue(cx, scope, value))
                }
            }

        private fun arrayObject(cx: Context, scope: Scriptable, values: List<*>): Scriptable =
            cx.newArray(scope, values.map { safeValue(cx, scope, it) }.toTypedArray())

        private fun safeValue(cx: Context, scope: Scriptable, value: Any?): Any? = when (value) {
            null -> null
            is Scriptable -> value
            is String -> value
            is CharSequence -> value.toString()
            is Number, is Boolean, is Char -> value
            is Map<*, *> -> mapObject(cx, scope, value)
            is Iterable<*> -> arrayObject(cx, scope, value.toList())
            else -> when {
                value.javaClass.isArray -> arrayObject(cx, scope, (0 until ReflectArray.getLength(value)).map { ReflectArray.get(value, it) })
                else -> super.wrap(cx, scope, value, value.javaClass)
            }
        }
    }
}
