package com.nexus.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class MobileVideoIceQueueTest {
    @Test fun candidatesAreAppliedAfterOfferExactlyOnce() {
        val queue = MobileVideoIceQueue<Int>()
        assertEquals(emptyList<Int>(), queue.receive(1))
        assertEquals(listOf(1), queue.activate())
        assertEquals(listOf(2), queue.receive(2))
        assertEquals(emptyList<Int>(), queue.activate())
    }
    @Test fun activationAndReceiptDoNotLoseConcurrentCandidates() {
        repeat(100) {
            val queue = MobileVideoIceQueue<Int>()
            val applied = Collections.synchronizedList(mutableListOf<Int>())
            val start = CountDownLatch(1)
            val callback = thread { start.await(); applied.addAll(queue.activate()) }
            val receiver = thread { start.await(); repeat(40) { applied.addAll(queue.receive(it)) } }
            start.countDown(); callback.join(2_000); receiver.join(2_000)
            assertEquals((0 until 40).toList(), applied.sorted())
        }
    }
    @Test(expected = IllegalStateException::class) fun preOfferQueueIsBounded() {
        val queue = MobileVideoIceQueue<Int>()
        repeat(65) { queue.receive(it) }
    }
}
