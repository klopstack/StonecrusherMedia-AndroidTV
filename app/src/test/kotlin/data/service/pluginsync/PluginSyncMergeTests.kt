package org.jellyfin.androidtv.data.service.pluginsync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PluginSyncMergeTests : FunSpec({
	val key = "confirmExit"

	test("first sync with no snapshot uses server values over local") {
		val local = mapOf(key to true)
		val server = mapOf(key to false)

		PluginSyncMerge.mergeThreeWay(local, server, emptyMap()) shouldBe mapOf(key to false)
	}

	test("local changed and server same keeps local value") {
		val snapshot = mapOf(key to false)
		val local = mapOf(key to true)
		val server = mapOf(key to false)

		PluginSyncMerge.mergeThreeWay(local, server, snapshot) shouldBe mapOf(key to true)
	}

	test("server changed and local same uses server value") {
		val snapshot = mapOf(key to false)
		val local = mapOf(key to false)
		val server = mapOf(key to true)

		PluginSyncMerge.mergeThreeWay(local, server, snapshot) shouldBe mapOf(key to true)
	}

	test("both changed resolves conflict with local winning") {
		val contentKey = "shuffleContentType"
		val snapshot = mapOf(contentKey to "all")
		val local = mapOf(contentKey to "movies")
		val server = mapOf(contentKey to "tv")

		PluginSyncMerge.mergeThreeWay(
			local,
			server,
			snapshot,
		) shouldBe mapOf(contentKey to "movies")
	}

	test("neither changed keeps local value") {
		val snapshot = mapOf(key to true)
		val local = mapOf(key to true)
		val server = mapOf(key to true)

		PluginSyncMerge.mergeThreeWay(local, server, snapshot) shouldBe mapOf(key to true)
	}

	test("type-normalized values are treated as unchanged") {
		val snapshot = mapOf("userPinLength" to 4)
		val local = mapOf("userPinLength" to "4")
		val server = mapOf("userPinLength" to 6)

		PluginSyncMerge.mergeThreeWay(local, server, snapshot) shouldBe mapOf("userPinLength" to 6)
	}

	test("ignores keys outside syncable server key set") {
		val snapshot = mapOf("notASyncKey" to "old")
		val local = mapOf("notASyncKey" to "local")
		val server = mapOf("notASyncKey" to "server")

		val merged = PluginSyncMerge.mergeThreeWay(local, server, snapshot)

		(merged.containsKey("notASyncKey")) shouldBe false
	}

	test("first sync ignores keys outside syncable server key set") {
		val local = mapOf("notASyncKey" to "local", key to true)
		val server = mapOf("notASyncKey" to "server", key to false)

		val merged = PluginSyncMerge.mergeThreeWay(local, server, emptyMap())

		merged shouldBe mapOf(key to false)
	}
})
