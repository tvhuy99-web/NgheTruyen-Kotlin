package vn.nghetruyen.source.api

import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCapabilityBrokersHostKernelTest {
    @Test
    fun hostKernelSurvivesCompatibilityBrokerCopies() {
        val host = SourceHostKernelDispatcher().register("ui", "refresh") { _, _, _ ->
            SourcePlatformResult.Success(JsonValue.Null)
        }
        val events = SourceHostEventSink { _, _, _ -> }
        val original = SourceCapabilityBrokers(hostKernel = host, hostEvents = events)
        val copy = original.copy(network = SourceNetworkBroker.DENY_ALL)
        assertTrue(host === copy.hostKernel)
        assertTrue(events === copy.hostEvents)
    }

    @Test
    fun defaultsFailClosedWithoutRemovingContract() {
        val brokers = SourceCapabilityBrokers()
        val result = brokers.hostKernel.execute(
            "vn.nghetruyen.sources.test",
            SourceHostKernelContract.command("reader", "refresh"),
            "trace-default",
        )
        assertTrue(result is SourcePlatformResult.Failure)
    }
}
