package com.fll.archaeologyform

import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.CdnSettings
import com.google.api.services.youtube.model.LiveBroadcast
import com.google.api.services.youtube.model.LiveBroadcastContentDetails
import com.google.api.services.youtube.model.LiveBroadcastSnippet
import com.google.api.services.youtube.model.LiveBroadcastStatus
import com.google.api.services.youtube.model.LiveStream
import com.google.api.services.youtube.model.LiveStreamSnippet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class YouTubeLiveManager(private val context: Context) {

    companion object {
        private const val TAG = "YouTubeLive"
        const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"
        const val RTMP_BASE = "rtmp://a.rtmp.youtube.com/live2"
    }

    data class StreamCredentials(
        val broadcastId: String,
        val streamId: String,
        val rtmpUrl: String   // full rtmp://…/live2/STREAM_KEY
    )

    private fun buildYouTube(accountEmail: String): YouTube {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(YOUTUBE_SCOPE)
        ).also { it.selectedAccountName = accountEmail }
        return YouTube.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("MARP").build()
    }

    /** Creates a broadcast + stream, binds them, returns RTMP credentials. */
    suspend fun createBroadcastAndStream(accountEmail: String): Result<StreamCredentials> =
        withContext(Dispatchers.IO) {
            try {
                val yt = buildYouTube(accountEmail)
                val label = "MARP – ${SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date())}"

                // 1. Create broadcast
                val broadcast = yt.liveBroadcasts()
                    .insert(
                        listOf("snippet", "status", "contentDetails"),
                        LiveBroadcast().apply {
                            snippet = LiveBroadcastSnippet().apply {
                                title = label
                                scheduledStartTime = DateTime(System.currentTimeMillis())
                            }
                            status = LiveBroadcastStatus().apply {
                                privacyStatus = "unlisted"
                            }
                            contentDetails = LiveBroadcastContentDetails().apply {
                                enableAutoStart = true
                                enableAutoStop  = true
                            }
                        }
                    ).execute()

                // 2. Create stream
                val stream = yt.liveStreams()
                    .insert(
                        listOf("snippet", "cdn"),
                        LiveStream().apply {
                            snippet = LiveStreamSnippet().apply { title = label }
                            cdn = CdnSettings().apply {
                                ingestionType = "rtmp"
                                resolution = "480p"
                                frameRate = "30fps"
                            }
                        }
                    ).execute()

                // 3. Bind
                yt.liveBroadcasts()
                    .bind(broadcast.id, listOf("id", "contentDetails"))
                    .setStreamId(stream.id)
                    .execute()

                val ingestionInfo = stream.cdn.ingestionInfo
                val ingestionAddress = ingestionInfo.ingestionAddress
                val streamName = ingestionInfo.streamName
                Log.d(TAG, "Ingestion address: $ingestionAddress")
                Log.d(TAG, "Stream name/key:   $streamName")
                Result.success(
                    StreamCredentials(
                        broadcastId = broadcast.id,
                        streamId = stream.id,
                        rtmpUrl = "$ingestionAddress/$streamName"
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "createBroadcastAndStream failed", e)
                Result.failure(e)
            }
        }

    /**
     * Polls broadcast status until it reaches "live" (auto-started by enableAutoStart)
     * or tries a manual transition if it reaches "ready". Returns true on success.
     */
    /**
     * Polls until BOTH the stream is "active" (RTMP data flowing) AND the broadcast
     * is "ready", then transitions to live. Also detects if enableAutoStart already
     * flipped it to "live" automatically.
     */
    suspend fun waitForStreamThenGoLive(
        broadcastId: String,
        streamId: String,
        accountEmail: String,
        onStatus: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val yt = buildYouTube(accountEmail)
        repeat(30) { attempt ->
            try {
                val broadcastStatus = yt.liveBroadcasts()
                    .list(listOf("status")).setId(listOf(broadcastId)).execute()
                    .items?.firstOrNull()?.status?.lifeCycleStatus

                val streamStatus = yt.liveStreams()
                    .list(listOf("status")).setId(listOf(streamId)).execute()
                    .items?.firstOrNull()?.status?.streamStatus

                Log.d(TAG, "attempt=$attempt broadcast=$broadcastStatus stream=$streamStatus")
                onStatus("YouTube: broadcast=$broadcastStatus stream=$streamStatus")

                when {
                    broadcastStatus == "live" -> {
                        Log.d(TAG, "Already live (auto-started)")
                        return@withContext true
                    }
                    broadcastStatus == "ready" && streamStatus == "active" -> {
                        return@withContext try {
                            yt.liveBroadcasts()
                                .transition("live", broadcastId, listOf("id", "status"))
                                .execute()
                            Log.d(TAG, "Transitioned to live")
                            true
                        } catch (e: Exception) {
                            Log.e(TAG, "Transition failed", e)
                            false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Polling failed", e)
            }
            Thread.sleep(3_000)
        }
        Log.e(TAG, "Never went live after 90s")
        false
    }

    /** Ends the broadcast. */
    suspend fun endBroadcast(broadcastId: String, accountEmail: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                buildYouTube(accountEmail)
                    .liveBroadcasts()
                    .transition("complete", broadcastId, listOf("id", "status"))
                    .execute()
            }.onFailure { Log.e(TAG, "endBroadcast failed", it) }
        }
}
