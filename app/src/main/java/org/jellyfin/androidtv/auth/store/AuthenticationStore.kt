package org.jellyfin.androidtv.auth.store

import android.content.Context
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.jellyfin.androidtv.auth.model.AuthenticationStoreServer
import org.jellyfin.androidtv.auth.model.AuthenticationStoreUser
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import timber.log.Timber
import java.util.UUID

/**
 * Storage for authentication related entities. Stores servers with users inside, including
 * access tokens.
 *
 * The data is stored in a JSON file located in the applications data directory.
 */
class AuthenticationStore(
	private val context: Context,
) {
	private val storePath
		get() = context.filesDir.resolve("authentication_store.json")

	private val tempStorePath
		get() = context.filesDir.resolve("authentication_store.json.tmp")

	private val lock = Any()

	private val json = Json {
		encodeDefaults = true
		serializersModule = SerializersModule {
			contextual(UUIDSerializer())
		}
		ignoreUnknownKeys = true
	}

	@Volatile
	private var storeCache: MutableMap<UUID, AuthenticationStoreServer>? = null

	private fun getStore(): MutableMap<UUID, AuthenticationStoreServer> = synchronized(lock) {
		storeCache ?: load().toMutableMap().also { storeCache = it }
	}

	private fun load(): Map<UUID, AuthenticationStoreServer> {
		// No store found
		if (!storePath.exists()) return emptyMap()

		// Parse JSON document
		val root = try {
			json.parseToJsonElement(storePath.readText()).jsonObject
		} catch (e: SerializationException) {
			Timber.e(e, "Unable to read JSON")
			JsonObject(emptyMap())
		}

		// Check for version
		return when (root["version"]?.jsonPrimitive?.intOrNull) {
			// Migration was removed, clear stored servers
			1 -> {
				Timber.e("Migrating from version 1 is no longer possible")
				emptyMap()
			}

			// Current version, return as-is
			2 -> json.decodeFromJsonElement<Map<UUID, AuthenticationStoreServer>>(root["servers"]!!)

			null -> {
				Timber.e("Authentication Store is corrupt!")
				emptyMap()
			}

			else -> {
				Timber.e("Authentication Store is using an unknown version!")
				emptyMap()
			}
		}
	}

	private fun write(servers: Map<UUID, AuthenticationStoreServer>): Boolean {
		val root = JsonObject(mapOf(
			"version" to JsonPrimitive(2),
			"servers" to json.encodeToJsonElement(servers)
		))

		val content = json.encodeToString(root)
		tempStorePath.writeText(content)
		if (!tempStorePath.renameTo(storePath)) {
			Timber.e("Atomic rename failed for authentication store, writing directly")
			storePath.writeText(content)
			tempStorePath.delete()
		}

		return true
	}

	fun getServers(): Map<UUID, AuthenticationStoreServer> = synchronized(lock) {
		getStore().toMap()
	}

	fun getUsers(server: UUID): Map<UUID, AuthenticationStoreUser>? = synchronized(lock) {
		getStore()[server]?.users
	}

	fun getServer(serverId: UUID) = synchronized(lock) {
		getStore()[serverId]
	}

	fun getUser(serverId: UUID, userId: UUID) = synchronized(lock) {
		getStore()[serverId]?.users?.get(userId)
	}

	fun putServer(id: UUID, server: AuthenticationStoreServer): Boolean = synchronized(lock) {
		getStore()[id] = server
		write(getStore())
	}

	fun putUser(server: UUID, userId: UUID, userInfo: AuthenticationStoreUser): Boolean = synchronized(lock) {
		val serverInfo = getStore()[server] ?: return false

		getStore()[server] = serverInfo.copy(users = serverInfo.users.toMutableMap().apply { put(userId, userInfo) })

		write(getStore())
	}

	/**
	 * Removes the server and stored users from the credential store.
	 */
	fun removeServer(server: UUID): Boolean = synchronized(lock) {
		getStore().remove(server)
		write(getStore())
	}

	fun removeUser(server: UUID, user: UUID): Boolean = synchronized(lock) {
		val serverInfo = getStore()[server] ?: return false

		getStore()[server] = serverInfo.copy(users = serverInfo.users.toMutableMap().apply { remove(user) })

		write(getStore())
	}
}
