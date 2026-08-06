package vn.nghetruyen.app.ai

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** Revalidates every address used by OkHttp so a custom AI endpoint cannot DNS-rebind into a private network. */
object AiPublicDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
            throw UnknownHostException("Endpoint AI phân giải tới địa chỉ cục bộ hoặc riêng tư.")
        }
        return addresses
    }

    internal fun isBlockedAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
}
