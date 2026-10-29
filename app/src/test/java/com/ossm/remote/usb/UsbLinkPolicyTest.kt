package com.ossm.remote.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbLinkPolicyTest {
    @Test
    fun `one slow write does not drop the link`() {
        assertFalse(UsbLinkPolicy.shouldDropLink(1))
    }

    @Test
    fun `link is dropped after the configured number of consecutive failures`() {
        assertFalse(UsbLinkPolicy.shouldDropLink(UsbLinkPolicy.DROP_AFTER_FAILURES - 1))
        assertTrue(UsbLinkPolicy.shouldDropLink(UsbLinkPolicy.DROP_AFTER_FAILURES))
    }
}
