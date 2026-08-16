package vn.nghetruyen.source.vbook

import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

 
internal object VBookRhinoValues {
    fun array(context: Context, scope: Scriptable, values: Iterable<Any?>): Scriptable {
        val safe = values.map { value ->
            when (value) {
                null, Undefined.instance -> value
                is String, is Number, is Boolean, is Char, is Scriptable -> value
                else -> error("VBOOK_HOST_VALUE_UNSAFE:${value.javaClass.name}")
            }
        }.toTypedArray()
        return context.newArray(scope, safe)
    }

    fun strings(context: Context, scope: Scriptable, values: Iterable<String>): Scriptable =
        array(context, scope, values)

    fun stringMap(context: Context, scope: Scriptable, values: Map<String, String>): Scriptable =
        context.newObject(scope).also { output ->
            values.forEach { (key, value) -> ScriptableObject.putProperty(output, key, value) }
        }
}
