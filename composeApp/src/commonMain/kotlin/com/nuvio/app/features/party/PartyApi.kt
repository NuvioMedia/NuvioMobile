package com.nuvio.app.features.party

import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal data class PartyIdentity(
    val deviceId: String,
    val displayName: String,
    val avatarUrl: String?,
    val avatarColor: String?,
    val profileIndex: Int?,
)

internal object PartyApi {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    suspend fun create(identity: PartyIdentity, media: PartyMedia, playback: PartyPlayback?): PartySnapshot =
        callWithProfile("watch_party_create", identity.profileIndex, buildJsonObject {
            putIdentity(identity)
            put("p_media", json.encodeToJsonElement(media))
            put("p_playback", playback?.let { json.encodeToJsonElement(it) } ?: JsonNull)
        })

    suspend fun join(code: String, identity: PartyIdentity): PartySnapshot =
        callWithProfile("watch_party_join", identity.profileIndex, buildJsonObject {
            put("p_code", code)
            putIdentity(identity)
        })

    suspend fun linkProfile(deviceId: String, profileIndex: Int, memberId: String?): PartySnapshot =
        call("watch_party_link_profile", buildJsonObject {
            put("p_device_id", deviceId)
            put("p_profile_index", profileIndex)
            memberId?.let { put("p_member_id", it) }
        })

    suspend fun preview(code: String): PartyPreview {
        val body = SupabaseProvider.client.postgrest.rpc("watch_party_preview", buildJsonObject { put("p_code", code) }).data
        return json.decodeFromString(PartyPreview.serializer(), body)
    }

    suspend fun heartbeat(partyId: String, deviceId: String): PartySnapshot =
        call("watch_party_heartbeat", buildJsonObject {
            put("p_party_id", partyId)
            put("p_device_id", deviceId)
        })

    suspend fun update(partyId: String, deviceId: String, media: PartyMedia?, playback: PartyPlayback?): PartySnapshot =
        call("watch_party_update", buildJsonObject {
            put("p_party_id", partyId)
            put("p_device_id", deviceId)
            media?.let { put("p_media", json.encodeToJsonElement(it)) }
            playback?.let { put("p_playback", json.encodeToJsonElement(it)) }
        })

    suspend fun leave(partyId: String, deviceId: String): PartySnapshot =
        call("watch_party_leave", buildJsonObject {
            put("p_party_id", partyId)
            put("p_device_id", deviceId)
        })

    suspend fun end(partyId: String, deviceId: String): PartySnapshot =
        call("watch_party_end", buildJsonObject {
            put("p_party_id", partyId)
            put("p_device_id", deviceId)
        })

    suspend fun setHostOnly(partyId: String, deviceId: String, hostOnly: Boolean): PartySnapshot =
        call("watch_party_set_host_only", buildJsonObject {
            put("p_party_id", partyId)
            put("p_device_id", deviceId)
            put("p_host_only", hostOnly)
        })

    suspend fun removeMember(partyId: String, deviceId: String, memberId: String): PartySnapshot =
        call("watch_party_remove_member", buildJsonObject {
            put("p_party_id", partyId)
            put("p_device_id", deviceId)
            put("p_member_id", memberId)
        })

    suspend fun sendMessage(partyId: String, deviceId: String, body: String, clientId: String): PartyChatSendResult {
        val params = buildJsonObject {
            put("p_party_id", partyId)
            put("p_device_id", deviceId)
            put("p_body", body)
            put("p_client_id", clientId)
        }
        val response = SupabaseProvider.client.postgrest.rpc("watch_party_send_message", params).data
        return json.decodeFromString(PartyChatSendResult.serializer(), response)
    }

    suspend fun listMessages(partyId: String, deviceId: String, after: Long?): PartyChatHistory {
        val params = buildJsonObject {
            put("p_party_id", partyId)
            put("p_device_id", deviceId)
            after?.let { put("p_after", it) }
        }
        val response = SupabaseProvider.client.postgrest.rpc("watch_party_list_messages", params).data
        return json.decodeFromString(PartyChatHistory.serializer(), response)
    }

    suspend fun clock(): Long =
        SupabaseProvider.client.postgrest.rpc("watch_party_clock").data.trim().toLong()

    suspend fun enabled(): Boolean =
        SupabaseProvider.client.postgrest.rpc("watch_party_enabled").data.trim().toBooleanStrict()

    private suspend fun call(function: String, params: JsonObject): PartySnapshot {
        val body = SupabaseProvider.client.postgrest.rpc(function, params).data
        return json.decodeFromString(PartySnapshot.serializer(), body)
    }

    private suspend fun callWithProfile(function: String, profileIndex: Int?, params: JsonObject): PartySnapshot {
        if (profileIndex == null) return call(function, params)
        return try {
            call(function, JsonObject(params + ("p_profile_index" to JsonPrimitive(profileIndex))))
        } catch (error: PostgrestRestException) {
            if (error.code != MISSING_FUNCTION) throw error
            call(function, params)
        }
    }

    private fun JsonObjectBuilder.putIdentity(identity: PartyIdentity) {
        put("p_device_id", identity.deviceId)
        put("p_display_name", identity.displayName)
        put("p_avatar_url", identity.avatarUrl)
        put("p_avatar_color", identity.avatarColor)
    }

    private const val MISSING_FUNCTION = "PGRST202"
}
