package com.theveloper.pixelplay.data.network.youtube

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * Flujo OAuth 2.0 "para dispositivos con entrada limitada" de Google (RFC 8628).
 *
 * Es el mismo que usan las apps de TV: la app enseña un código corto, el usuario lo teclea
 * en google.com/device y se identifica en la web de Google — la contraseña nunca pasa por
 * aquí. A cambio se obtiene un token que autentica las peticiones a la API interna de
 * YouTube, que es lo que hace falta para saltarse el "confirma que no eres un robot".
 */
interface GoogleOAuthService {

    /** Paso 1: pedir el código que el usuario tecleará en google.com/device. */
    @FormUrlEncoded
    @POST("device/code")
    suspend fun requestDeviceCode(
        @Field("client_id") clientId: String,
        @Field("scope") scope: String
    ): Response<DeviceCodeResponse>

    /**
     * Paso 2: canjear el device_code por tokens. Se llama en bucle hasta que el usuario
     * termina; mientras tanto devuelve `error = authorization_pending`.
     */
    @FormUrlEncoded
    @POST("token")
    suspend fun pollToken(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("device_code") deviceCode: String,
        @Field("grant_type") grantType: String = "urn:ietf:params:oauth:grant-type:device_code"
    ): Response<TokenResponse>

    /** Renovar el access token cuando caduca, con el refresh token guardado. */
    @FormUrlEncoded
    @POST("token")
    suspend fun refreshToken(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("refresh_token") refreshToken: String,
        @Field("grant_type") grantType: String = "refresh_token"
    ): Response<TokenResponse>
}

data class DeviceCodeResponse(
    @SerializedName("device_code") val deviceCode: String,
    @SerializedName("user_code") val userCode: String,
    @SerializedName("verification_url") val verificationUrl: String?,
    // Algunas respuestas usan verification_uri en vez de verification_url.
    @SerializedName("verification_uri") val verificationUri: String?,
    @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("interval") val interval: Long?
) {
    /** La web donde el usuario teclea el código, sea cual sea el nombre del campo. */
    val verification: String
        get() = verificationUrl ?: verificationUri ?: "https://www.google.com/device"
}

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("expires_in") val expiresIn: Long?,
    @SerializedName("token_type") val tokenType: String?,
    @SerializedName("scope") val scope: String?,
    /** authorization_pending, slow_down, access_denied, expired_token… */
    @SerializedName("error") val error: String?
)
