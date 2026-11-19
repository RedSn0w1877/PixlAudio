package com.theveloper.pixelplay.di

import javax.inject.Qualifier

/**
 * Qualifier for Deezer Retrofit instance.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeezerRetrofit

/**
 * Qualifier for Fast OkHttpClient (Short timeouts).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FastOkHttpClient

/**
 * Qualifier for the OkHttpClient used to talk to YouTube's private InnerTube API.
 *
 * It must NOT be the shared client: that one force-overwrites `User-Agent` on every
 * request, which contradicts the client identity InnerTube reads from the request body
 * and gets the call rejected.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class YouTubeOkHttpClient

/**
 * Qualifier for the OkHttpClient the music brain uses for the open-data hosts (Deezer, MusicBrainz, ListenBrainz, Wikidata,
 * iTunes; docs/brain-spec.md, 3.7). Its User-Agent names the app and never the listener, which MusicBrainz and Wikimedia ask for;
 * the shared client overwrites User-Agent, so it must not be used for these.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class KnowledgeOkHttpClient

/**
 * Qualifier for the Retrofit instance pointed at api.spotify.com.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SpotifyRetrofit

/**
 * Qualifier for the Retrofit instance pointed at accounts.spotify.com (OAuth only).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SpotifyAccountsRetrofit

/**
 * Qualifier for the Retrofit instance pointed at oauth2.googleapis.com (YouTube sign-in).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleOAuthRetrofit

/**
 * Qualifier for Gson instance configured for backup serialization.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupGson

/**
 * Qualifier for application-lifetime coroutine scope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope
