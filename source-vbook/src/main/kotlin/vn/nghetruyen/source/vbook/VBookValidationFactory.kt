package vn.nghetruyen.source.vbook

import com.nghetruyen.source.sandbox.JsSyntaxValidator

object VBookValidationFactory {
    fun production(): VBookCandidateValidator = VBookCandidateValidator(
        compileProbe = VBookCompileProbe { path, source ->
            val result = JsSyntaxValidator.validate(source, path)
            if (result.valid) null else buildString {
                append(result.message ?: "JavaScript syntax error")
                result.line?.let { append(" line=").append(it) }
                result.column?.let { append(" column=").append(it) }
            }
        },
    )
}
