package vn.nghetruyen.source.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonCodecTest {
    @Test fun roundTripPreservesStructuredData() {
        val value = JsonCodec.parse("""{"a":[1,true,null,"gió"],"b":{"x":"y"}}""")
        assertEquals("""{"a":[1,true,null,"gió"],"b":{"x":"y"}}""", JsonCodec.stringify(value))
    }

    @Test fun rejectsDuplicateKeys() {
        assertThrows(IllegalArgumentException::class.java) { JsonCodec.parse("""{"a":1,"a":2}""") }
    }
}
