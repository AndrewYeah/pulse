package com.andrew.proxyapp.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkInterfaceAddressTest {
    @Test
    fun formatsIpv4AddressAsCidr() {
        assertEquals(
            "192.168.1.20/24",
            formatLibboxAddressPrefix("192.168.1.20", 24, 32)
        )
    }

    @Test
    fun removesIpv6ZoneBeforePassingAddressToLibbox() {
        assertEquals(
            "fe80::cceb:c4ff:feb5:b237/64",
            formatLibboxAddressPrefix("fe80::cceb:c4ff:feb5:b237%dummy0", 64, 128)
        )
    }

    @Test
    fun rejectsInvalidPrefixLengths() {
        assertNull(formatLibboxAddressPrefix("192.168.1.20", 33, 32))
        assertNull(formatLibboxAddressPrefix("fe80::1", -1, 128))
    }
}
