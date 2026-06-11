package com.meshchat.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DeduplicationCacheTest {

    @Test
    fun testNoConcurrentDuplication() = runTest {
        val cache = DeduplicationCache(replayWindowMs = 60000L)
        val msgId = "concurrent_msg_1"

        // Launch 50 concurrent requests
        val results = coroutineScope {
            val deferreds = (1..50).map {
                async {
                    cache.isDuplicateOrReplay(msgId)
                }
            }
            deferreds.awaitAll()
        }

        // isDuplicateOrReplay returns false if it's NOT a duplicate (i.e., it's the first time)
        // It returns true if it IS a duplicate
        val firstTimeCount = results.count { !it }
        val duplicateCount = results.count { it }

        assertEquals(1, firstTimeCount, "Exactly one coroutine should see the message as new")
        assertEquals(49, duplicateCount, "All other coroutines should see the message as duplicate")
    }
}
