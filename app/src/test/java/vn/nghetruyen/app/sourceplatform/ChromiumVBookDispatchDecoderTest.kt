package vn.nghetruyen.app.sourceplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import vn.nghetruyen.source.api.JsonCodec
import vn.nghetruyen.source.api.JsonValue

class ChromiumVBookDispatchDecoderTest {
    @Test
    fun unwrapsDispatcherEnvelopeToRawResultObject() {
        val rawResult = "{\"code\":0,\"data\":[{\"name\":\"x\"}],\"data2\":null}"
        val outer = JsonCodec.stringify(JsonValue.Obj(linkedMapOf(
            "code" to JsonValue.Num(0.0, "0"),
            "data" to JsonValue.Obj(linkedMapOf(
                "__ngheVBookRawResult" to JsonValue.Str(rawResult),
            )),
            "data2" to JsonValue.Null,
        )))

        val decoded = ChromiumVBookDispatchDecoder.decode(outer)

        assertEquals(rawResult, decoded.string("__ngheVBookRawResult"))
    }

    @Test
    fun toleratesAdditionalWebViewStringSerializationLayers() {
        val outer = "{\"code\":0,\"data\":{\"__ngheVBookRawResult\":\"payload\"},\"data2\":null}"
        val quotedOnce = JsonCodec.stringify(JsonValue.Str(outer))
        val quotedTwice = JsonCodec.stringify(JsonValue.Str(quotedOnce))

        val decoded = ChromiumVBookDispatchDecoder.decode(quotedTwice)

        assertEquals("payload", decoded.string("__ngheVBookRawResult"))
    }

    @Test
    fun reportsGuardedChromiumEvaluationError() {
        val failure = "{\"__ngheChromiumEvalError\":\"TypeError: readonly global\"}"

        val error = assertThrows(IllegalStateException::class.java) {
            ChromiumVBookDispatchDecoder.decode(failure)
        }

        assertEquals("CHROMIUM_EVAL_ERROR:TypeError: readonly global", error.message)
    }

    @Test
    fun rejectsNonSuccessEnvelope() {
        val failure = "{\"code\":1,\"data\":\"boom\"}"

        val error = assertThrows(IllegalStateException::class.java) {
            ChromiumVBookDispatchDecoder.decode(failure)
        }

        assertEquals("VBOOK_RESPONSE_ERROR:boom", error.message)
    }
}
