package com.nghetruyen.source.sandbox

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.RhinoException

data class JsSyntaxValidation(
    val valid: Boolean,
    val message: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

 
object JsSyntaxValidator {
    fun validate(
        source: String,
        sourceName: String = "extension.js",
        languageVersion: Int = Context.VERSION_ES6,
    ): JsSyntaxValidation {
        require(sourceName.isNotBlank())
        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().also { cx ->
                cx.languageVersion = languageVersion
                cx.optimizationLevel = -1
                cx.setClassShutter(ClassShutter { false })
            }
        }
        return runCatching {
            val cx = factory.enterContext()
            try {
                
                cx.compileString(source, sourceName, 1, null)
                JsSyntaxValidation(true)
            } finally {
                Context.exit()
            }
        }.getOrElse { error ->
            if (error is RhinoException) {
                JsSyntaxValidation(
                    valid = false,
                    message = error.details(),
                    line = error.lineNumber().takeIf { it > 0 },
                    column = error.columnNumber().takeIf { it > 0 },
                )
            } else {
                JsSyntaxValidation(false, error.message ?: error.javaClass.simpleName)
            }
        }
    }
}
