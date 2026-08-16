package vn.nghetruyen.app.sourceplatform

 
fun interface SourceWebViewCookieReader {
    fun readWebViewCookieHeader(sourceId: String, requestUrl: String): String?
}
