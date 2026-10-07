package com.theveloper.pixelplay.data.network.spotify

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

/**
 * Web API Player endpoints, used for Spotify Connect output.
 *
 * Verified against developer.spotify.com/documentation/web-api/reference (October 2026):
 * - reads need `user-read-playback-state`, commands need `user-modify-playback-state`;
 * - every command is Premium-only and answers 204 on success;
 * - "the order of execution is not guaranteed when you use this API with other Player API
 *   endpoints", so callers sequence commands themselves.
 *
 * The `Authorization` header is passed per call, like [SpotifyApiService], so the client can
 * refresh the token and retry once on 401.
 */
interface SpotifyPlayerApiService {

    @GET("v1/me/player/devices")
    suspend fun getDevices(
        @Header("Authorization") authorization: String
    ): Response<SpotifyDevicesResponse>

    /** 200 with the state, or 204 with no body when nothing is playing. */
    @GET("v1/me/player")
    suspend fun getPlaybackState(
        @Header("Authorization") authorization: String
    ): Response<SpotifyPlaybackStateDto>

    @PUT("v1/me/player")
    suspend fun transferPlayback(
        @Header("Authorization") authorization: String,
        @Body body: SpotifyTransferRequest
    ): Response<Unit>

    @PUT("v1/me/player/play")
    suspend fun play(
        @Header("Authorization") authorization: String,
        @Query("device_id") deviceId: String?,
        @Body body: SpotifyPlayRequest
    ): Response<Unit>

    /** Resume: the same endpoint with no body keeps the current context and position. */
    @PUT("v1/me/player/play")
    suspend fun resume(
        @Header("Authorization") authorization: String,
        @Query("device_id") deviceId: String?
    ): Response<Unit>

    @PUT("v1/me/player/pause")
    suspend fun pause(
        @Header("Authorization") authorization: String,
        @Query("device_id") deviceId: String?
    ): Response<Unit>

    @POST("v1/me/player/next")
    suspend fun next(
        @Header("Authorization") authorization: String,
        @Query("device_id") deviceId: String?
    ): Response<Unit>

    @POST("v1/me/player/previous")
    suspend fun previous(
        @Header("Authorization") authorization: String,
        @Query("device_id") deviceId: String?
    ): Response<Unit>

    @PUT("v1/me/player/seek")
    suspend fun seek(
        @Header("Authorization") authorization: String,
        @Query("position_ms") positionMs: Long,
        @Query("device_id") deviceId: String?
    ): Response<Unit>

    /** `volume_percent` must be 0..100 inclusive. */
    @PUT("v1/me/player/volume")
    suspend fun setVolume(
        @Header("Authorization") authorization: String,
        @Query("volume_percent") volumePercent: Int,
        @Query("device_id") deviceId: String?
    ): Response<Unit>

    @PUT("v1/me/player/shuffle")
    suspend fun setShuffle(
        @Header("Authorization") authorization: String,
        @Query("state") state: Boolean,
        @Query("device_id") deviceId: String?
    ): Response<Unit>

    /** `state` is "track", "context" or "off". */
    @PUT("v1/me/player/repeat")
    suspend fun setRepeat(
        @Header("Authorization") authorization: String,
        @Query("state") state: String,
        @Query("device_id") deviceId: String?
    ): Response<Unit>
}
