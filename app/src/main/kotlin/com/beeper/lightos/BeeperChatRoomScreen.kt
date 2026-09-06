package com.beeper.lightos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.shared.getOrNull
import androidx.compose.foundation.layout.size

import com.thelightphone.sdk.ui.LightBottomBar

import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.lifecycle.viewModelScope
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.media
import net.folivo.trixnity.client.room
import net.folivo.trixnity.client.room.getTimelineEventReactionAggregation
import net.folivo.trixnity.client.room.message.reply
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.user
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.MessageEventContent
import net.folivo.trixnity.core.model.events.m.ReactionEventContent
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import java.util.Calendar

// ── Helpers ────────────────────────────────────────────────────────────────────

/** Reusable OkHttp client for image downloads (created once). */
private val imageHttpClient by lazy { okhttp3.OkHttpClient() }

/** Format a millisecond timestamp as HH:mm for in-conversation display. */
private fun formatMessageTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    return String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

object MatrixMediaCache {
    private val memoryCache = android.util.LruCache<String, android.graphics.Bitmap>(30)

    suspend fun getThumbnail(
        client: MatrixClient,
        media: RoomMessageEventContent.FileBased
    ): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val (file, url) = when (media) {
            is RoomMessageEventContent.FileBased.Image -> Pair(media.file, media.url)
            is RoomMessageEventContent.FileBased.Video -> Pair(media.info?.thumbnailFile, media.info?.thumbnailUrl)
            else -> return@withContext null
        }
        
        val key = file?.url ?: url ?: return@withContext null
        
        memoryCache.get(key)?.let { return@withContext it }
        
        val context = BeeperRepository.appContext
        val cacheDir = context?.let { java.io.File(it.cacheDir, "matrix_images") }
        val cacheFileName = java.net.URLEncoder.encode(key, "UTF-8")
        val cacheFile = cacheDir?.let { java.io.File(it, cacheFileName) }
        
        if (cacheFile != null && cacheFile.exists()) {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (bitmap != null) {
                    memoryCache.put(key, bitmap)
                    return@withContext bitmap
                }
            } catch (e: Exception) {
                // Ignore, proceed to fetch
            }
        }
        
        try {
            val mediaResult = if (file != null) {
                client.media.getEncryptedMedia(file)
            } else if (url != null) {
                client.media.getMedia(url)
            } else {
                null
            }
            
            val mediaData = mediaResult?.getOrNull()
            if (mediaData != null) {
                val byteList = mutableListOf<Byte>()
                mediaData.collect { byteList.addAll(it.toList()) }
                val bytes = byteList.toByteArray()
                
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    memoryCache.put(key, bitmap)
                    if (cacheFile != null) {
                        try {
                            if (!cacheDir.exists()) cacheDir.mkdirs()
                            java.io.FileOutputStream(cacheFile).use { it.write(bytes) }
                        } catch (e: Exception) {}
                    }
                    return@withContext bitmap
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MatrixMediaCache", "Failed to load media thumbnail", e)
        }
        
        null
    }
}

/**
 * Composable that asynchronously downloads and renders a Matrix media thumbnail (Image or Video).
 * Falls back to a text label while loading or on failure.
 */
@Composable
private fun MatrixMedia(
    client: MatrixClient,
    media: RoomMessageEventContent.FileBased,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(media) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(media) { mutableStateOf(false) }

    LaunchedEffect(media) {
        val cached = MatrixMediaCache.getThumbnail(client, media)
        if (cached != null) {
            bitmap = cached
        } else {
            failed = true
        }
    }

    when {
        bitmap != null -> {
            Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Media",
                    contentScale = ContentScale.Inside,
                    modifier = Modifier.fillMaxSize()
                )
                if (media is RoomMessageEventContent.FileBased.Video) {
                    // Play icon overlay
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(LightThemeTokens.colors.background.copy(alpha = 0.5f), shape = androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier.size(24.dp).padding(start = 4.dp)
                        ) {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(0f, 0f)
                                lineTo(size.width, size.height / 2f)
                                lineTo(0f, size.height)
                                close()
                            }
                            drawPath(path, color = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                }
            }
        }
        failed -> {
            if (media is RoomMessageEventContent.FileBased.Video) {
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 240.dp)
                        .background(LightThemeTokens.colors.background),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                LightThemeTokens.colors.background.copy(alpha = 0.5f),
                                shape = androidx.compose.foundation.shape.CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier.size(24.dp).padding(start = 4.dp)
                        ) {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(0f, 0f)
                                lineTo(size.width, size.height / 2f)
                                lineTo(0f, size.height)
                                close()
                            }
                            drawPath(path, color = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                    LightText(
                        text = "Video",
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        variant = LightTextVariant.Fine
                    )
                }
            } else {
                LightText(text = "[Photo]", variant = LightTextVariant.Fine, lighten = true)
            }
        }
        else -> LightText(text = "[Loading…]", variant = LightTextVariant.Fine, lighten = true)
    }
}

@Composable
private fun MatrixAudioPlayer(
    client: MatrixClient,
    audio: RoomMessageEventContent.FileBased.Audio,
    modifier: Modifier = Modifier,
) {
    var localFile by remember(audio) { mutableStateOf<java.io.File?>(null) }
    var isLoading by remember(audio) { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(0) }
    val mediaPlayer = remember(audio) { android.media.MediaPlayer() }

    // Download audio file once
    LaunchedEffect(audio) {
        withContext(Dispatchers.IO) {
            try {
                val mediaResult = if (audio.file != null) {
                    client.media.getEncryptedMedia(audio.file!!)
                } else if (audio.url != null) {
                    client.media.getMedia(audio.url!!)
                } else null

                val mediaData = mediaResult?.getOrNull()
                if (mediaData != null) {
                    val byteList = mutableListOf<Byte>()
                    mediaData.collect { byteList.addAll(it.toList()) }
                    val bytes = byteList.toByteArray()
                    val tmpPath = java.nio.file.Files.createTempFile("beeper_audio_", ".tmp")
                    val tmp = tmpPath.toFile()
                    tmp.writeBytes(bytes)
                    localFile = tmp
                    mediaPlayer.setDataSource(tmp.absolutePath)
                    mediaPlayer.prepare()
                    duration = mediaPlayer.duration
                    mediaPlayer.setOnCompletionListener {
                        isPlaying = false
                        progress = 0f
                        mediaPlayer.seekTo(0)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MatrixAudio", "Failed to load audio", e)
            } finally {
                isLoading = false
            }
        }
    }

    // Progress ticker
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val dur = mediaPlayer.duration
            val cur = mediaPlayer.currentPosition
            progress = if (dur > 0) cur.toFloat() / dur.toFloat() else 0f
            kotlinx.coroutines.delay(200)
        }
    }

    androidx.compose.runtime.DisposableEffect(audio) {
        onDispose {
            if (mediaPlayer.isPlaying) mediaPlayer.stop()
            mediaPlayer.release()
            localFile?.delete()
        }
    }

    val durationStr = if (duration > 0) {
        val s = duration / 1000
        "%d:%02d".format(s / 60, s % 60)
    } else "--:--"
    val currentStr = if (duration > 0) {
        val s = (progress * duration / 1000).toInt()
        "%d:%02d".format(s / 60, s % 60)
    } else "0:00"

    val iconColor = LightThemeTokens.colors.content

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 0.3f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp())
    ) {
        // Play/Pause button
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    LightThemeTokens.colors.content.copy(alpha = 0.15f),
                    shape = androidx.compose.foundation.shape.CircleShape
                )
                .lightClickable {
                    if (isLoading || localFile == null) return@lightClickable
                    if (isPlaying) {
                        mediaPlayer.pause()
                        isPlaying = false
                    } else {
                        mediaPlayer.start()
                        isPlaying = true
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                LightText("…", variant = LightTextVariant.Fine)
            } else if (isPlaying) {
                // Pause icon (two vertical bars)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(width = 3.dp, height = 14.dp).background(iconColor))
                    Box(Modifier.size(width = 3.dp, height = 14.dp).background(iconColor))
                }
            } else {
                // Play triangle
                androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp).padding(start = 2.dp)) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, size.height / 2f)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, color = iconColor)
                }
            }
        }

        // Progress bar + time
        Column(modifier = Modifier.weight(1f)) {
            // Track bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 3.dp)
                    .background(LightThemeTokens.colors.content.copy(alpha = 0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .heightIn(min = 3.dp)
                        .background(LightThemeTokens.colors.content)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LightText(currentStr, variant = LightTextVariant.Fine, lighten = true)
                LightText(durationStr, variant = LightTextVariant.Fine, lighten = true)
            }
        }
    }
}


private fun formatDateSeparator(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val msg = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()
    val sameDay = msg.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            msg.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val yesterday = run {
        val y = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -1) }
        msg.get(Calendar.YEAR) == y.get(Calendar.YEAR) &&
                msg.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }
    if (sameDay) return "Today"
    if (yesterday) return "Yesterday"
    val days   = arrayOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat")
    val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
    return "${days[msg.get(Calendar.DAY_OF_WEEK) - 1]} ${months[msg.get(Calendar.MONTH)]} ${msg.get(Calendar.DAY_OF_MONTH)}"
}

/**
 * Resolve event content to (displayText, imageContent?) pair.
 * Returns null to skip the event entirely.
 */
private fun resolveContent(content: Any?, isReply: Boolean = false): Pair<String, RoomMessageEventContent.FileBased?>? {
    if (content == null) return null
    if (content is net.folivo.trixnity.core.model.events.UnknownEventContent) {
        // Try to extract body directly from the raw JSON
        try {
            val rawValue = content.raw
            val bodyElement = rawValue["body"]
            val bodyText = if (bodyElement is kotlinx.serialization.json.JsonPrimitive) {
                bodyElement.content
            } else {
                bodyElement?.toString()
            }
            if (bodyText != null) {
                var text = bodyText
                if (isReply || text.startsWith(">")) {
                    val index = text.indexOf("\n\n")
                    if (index != -1) {
                        text = text.substring(index + 2).trimStart()
                    }
                }
                return Pair(text, null)
            }
        } catch (e: Exception) {
            // Fallthrough to null return
        }
        return null
    }
    if (content is RoomMessageEventContent.TextBased) {
        var text = content.body
        if (isReply) {
            val formatted = content.formattedBody
            if (content.format == "org.matrix.custom.html" && formatted != null && formatted.contains("<mx-reply>")) {
                val withoutReply = formatted.replace(Regex("<mx-reply>.*?</mx-reply>", RegexOption.DOT_MATCHES_ALL), "")
                text = android.text.Html.fromHtml(withoutReply, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            } else if (text.startsWith(">")) {
                val index = text.indexOf("\n\n")
                if (index != -1) {
                    text = text.substring(index + 2).trimStart()
                }
            }
        }
        return Pair(text, null)
    }
    if (content is RoomMessageEventContent.FileBased.Image) {
        var text = content.body
        if (isReply || text.startsWith(">")) {
            val formatted = content.formattedBody
            if (formatted != null && formatted.contains("<mx-reply>")) {
                val withoutReply = formatted.replace(Regex("<mx-reply>.*?</mx-reply>", RegexOption.DOT_MATCHES_ALL), "")
                text = android.text.Html.fromHtml(withoutReply, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            } else {
                val index = text.indexOf("\n\n")
                if (index != -1) text = text.substring(index + 2).trimStart()
                else if (text.startsWith(">")) text = text.substringAfter("\n").trimStart()
            }
        }
        val caption = text.takeIf { t -> 
            t.isNotBlank() && t != content.fileName && !t.matches(Regex("(?i).*\\.(jpg|jpeg|png|gif|webp|heic)$")) 
        }
        val finalStr = if (caption != null) "[Photo] $caption" else "[Photo]"
        return Pair(finalStr, content)
    }
    if (content is RoomMessageEventContent.FileBased.Video) {
        var text = content.body
        if (isReply || text.startsWith(">")) {
            val formatted = content.formattedBody
            if (formatted != null && formatted.contains("<mx-reply>")) {
                val withoutReply = formatted.replace(Regex("<mx-reply>.*?</mx-reply>", RegexOption.DOT_MATCHES_ALL), "")
                text = android.text.Html.fromHtml(withoutReply, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            } else {
                val index = text.indexOf("\n\n")
                if (index != -1) text = text.substring(index + 2).trimStart()
                else if (text.startsWith(">")) text = text.substringAfter("\n").trimStart()
            }
        }
        val caption = text.takeIf { t -> 
            t.isNotBlank() && t != content.fileName && !t.matches(Regex("(?i).*\\.(mp4|mov|mkv|webm)$")) 
        }
        val finalStr = if (caption != null) "[Video] $caption" else "[Video]"
        return Pair(finalStr, content)
    }
    if (content is RoomMessageEventContent.FileBased.Audio) {
        var text = content.body
        if (isReply || text.startsWith(">")) {
            val formatted = content.formattedBody
            if (formatted != null && formatted.contains("<mx-reply>")) {
                val withoutReply = formatted.replace(Regex("<mx-reply>.*?</mx-reply>", RegexOption.DOT_MATCHES_ALL), "")
                text = android.text.Html.fromHtml(withoutReply, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            } else {
                val index = text.indexOf("\n\n")
                if (index != -1) text = text.substring(index + 2).trimStart()
                else if (text.startsWith(">")) text = text.substringAfter("\n").trimStart()
            }
        }
        val caption = text.takeIf { t -> 
            t.isNotBlank() && t != content.fileName && !t.matches(Regex("(?i).*\\.(mp3|wav|ogg|m4a)$")) 
        }
        val finalStr = if (caption != null) caption else ""
        return Pair(finalStr, content)  // pass content through so UI can render a player
    }
    if (content is RoomMessageEventContent.FileBased.File)   return Pair("[File: ${content.body}]", null)
    // Skip anything else silently
    return null
}

// ── Data model ─────────────────────────────────────────────────────────────────

enum class SendState { SENT, PENDING, FAILED }

data class ChatMessage(
    val id: String,
    val senderName: String,
    val content: String,
    val isMine: Boolean,
    val timestamp: Long,
    /** Non-null when this is an image or video message. */
    val mediaContent: RoomMessageEventContent.FileBased? = null,
    val reactions: Map<String, Int> = emptyMap(),
    val replyToText: String? = null,
    val replyToName: String? = null,
    val isRead: Boolean = false,
    val sendState: SendState = SendState.SENT,
    val transactionId: String? = null,
    val isSystemMessage: Boolean = false,
)

// ── ViewModel ──────────────────────────────────────────────────────────────────

class BeeperChatRoomViewModel(
    private val client: MatrixClient,
    val roomId: String,
) : LightViewModel<Unit>() {

    private val matrixRoomId = RoomId(roomId)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _outboxMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = kotlinx.coroutines.flow.combine(_messages, _outboxMessages) { timelineMsgs, outboxMsgs ->
        val timelineTxIds = timelineMsgs.mapNotNull { it.transactionId }.toSet()
        val filteredOutbox = outboxMsgs.filter { it.transactionId == null || !timelineTxIds.contains(it.transactionId) }
        timelineMsgs + filteredOutbox
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    private val _messageLimit = MutableStateFlow(10)
    
    private val _canLoadMore = MutableStateFlow(false)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore.asStateFlow()

    fun loadMore() {
        _messageLimit.value += 10
    }

    private val _roomName = MutableStateFlow("Chat")
    val roomName: StateFlow<String> = _roomName.asStateFlow()

    init {
        viewModelScope.launch {
            // Resolve display name for the top bar
            client.room.getById(matrixRoomId).collect { room ->
                if (room != null) {
                    val explicit = room.name?.explicitName
                    if (explicit != null) {
                        _roomName.value = explicit
                    } else {
                        val heroName = room.name?.heroes?.firstOrNull()?.let { heroId ->
                            client.user.getById(matrixRoomId, heroId).firstOrNull()?.name
                                ?: heroId.localpart
                        }
                        if (heroName != null) _roomName.value = heroName
                    }
                }
            }
        }

        viewModelScope.launch {
            // Monitor outbox for send errors and pending messages
            launch {
                client.room.getOutbox(matrixRoomId).flatMapLatest { outboxList ->
                    if (outboxList.isEmpty()) {
                        kotlinx.coroutines.flow.flowOf(emptyList())
                    } else {
                        kotlinx.coroutines.flow.combine(outboxList) { it.toList() }
                    }
                }.collect { outboxMsgs ->
                    val chatMsgs = outboxMsgs.mapNotNull { msg ->
                        if (msg == null) return@mapNotNull null
                        
                        val content = msg.content
                        val (text, mediaContent) = resolveContent(content, isReply = false) ?: Pair("[Unknown]", null)

                        val senderName = client.user.getById(matrixRoomId, client.userId).firstOrNull()?.name ?: client.userId.localpart

                        android.util.Log.d("BeeperChatRoom", "Outbox msg mapping: txId=${msg.transactionId} text=$text sentAt=${msg.sentAt} error=${msg.sendError}")

                        ChatMessage(
                            id = msg.transactionId,
                            senderName = senderName,
                            content = text,
                            isMine = true,
                            timestamp = msg.createdAt.toEpochMilliseconds(),
                            mediaContent = mediaContent,
                            sendState = when {
                                msg.sendError != null -> SendState.FAILED
                                msg.sentAt != null -> SendState.SENT
                                else -> SendState.PENDING
                            },
                            transactionId = msg.transactionId
                        )
                    }
                    _outboxMessages.value = chatMsgs
                }
            }

            val room = client.room.getById(matrixRoomId).firstOrNull() ?: return@launch

            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val timeline = client.room.getTimeline(matrixRoomId) { eventFlow ->
                eventFlow.flatMapLatest { timelineEvent ->
                    val reactionAggregationFlow = client.room.getTimelineEventReactionAggregation(matrixRoomId, timelineEvent.event.id)
                    val contentCast = timelineEvent.content?.getOrNull() as? MessageEventContent
                    val replyToId = contentCast?.relatesTo?.replyTo?.eventId
                    val replyFlow = if (replyToId != null) {
                        client.room.getTimelineEvent(matrixRoomId, replyToId)
                    } else {
                        flowOf(null)
                    }

                    combine(reactionAggregationFlow, replyFlow) { reactionAgg, replyEvent ->
                            val event   = timelineEvent.event
                            val content = timelineEvent.content?.getOrNull()
                            val senderId = event.sender
                            val isMine   = senderId == client.userId

                            android.util.Log.d(
                                "BeeperChatRoom",
                                "Mapping event: id=${event.id.full} sender=$senderId isMine=$isMine failure=${timelineEvent.content?.isFailure}"
                            )

                            // Resolve content text + optional image URL
                            val (text, mediaContent, isSystemMessage) = when {
                                content is net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent -> {
                                    val resolved = resolveContent(content, isReply = replyToId != null) ?: return@combine null
                                    Triple(resolved.first, resolved.second, false)
                                }
                                // Decryption / unknown failure
                                timelineEvent.content?.isFailure == true -> {
                                    val exName = timelineEvent.content?.exceptionOrNull()
                                        ?.let { it::class.simpleName } ?: ""
                                    
                                    val msg = if (exName.contains("Decrypt", ignoreCase = true)) {
                                        val encryptedContent = event.content as? net.folivo.trixnity.core.model.events.m.room.EncryptedMessageEventContent.MegolmEncryptedMessageEventContent
                                        if (encryptedContent != null) {
                                            BeeperRepository.restoreMegolmSession(matrixRoomId, encryptedContent.sessionId)
                                        }
                                        "[Encrypted — waiting for key…]"
                                    } else {
                                        "[Encrypted / unknown error: $exName]"
                                    }
                                    Triple(msg, null, false)
                                }
                                // Still-encrypted event (key not yet arrived)
                                event.content is net.folivo.trixnity.core.model.events.m.room.EncryptedMessageEventContent -> {
                                    val encryptedContent = event.content as? net.folivo.trixnity.core.model.events.m.room.EncryptedMessageEventContent.MegolmEncryptedMessageEventContent
                                    if (encryptedContent != null) {
                                        BeeperRepository.restoreMegolmSession(matrixRoomId, encryptedContent.sessionId)
                                    }
                                    Triple("[Encrypted — waiting for key…]", null, false)
                                }
                                // Member join/leave
                                event is net.folivo.trixnity.core.model.events.ClientEvent.RoomEvent.StateEvent<*> && event.content is net.folivo.trixnity.core.model.events.m.room.MemberEventContent -> {
                                    val memberEvent = event.content as net.folivo.trixnity.core.model.events.m.room.MemberEventContent
                                    val membership = memberEvent.membership
                                    val targetId = event.stateKey  // state_key is the affected user's ID

                                    // Check prev_content to detect profile updates (JOIN → JOIN = no real membership change)
                                    val prevContent = event.unsigned?.previousContent as? net.folivo.trixnity.core.model.events.m.room.MemberEventContent
                                    val prevMembership = prevContent?.membership

                                    // Skip profile updates: same membership as before means only name/avatar changed
                                    if (prevMembership == membership) return@combine null

                                    val targetName = memberEvent.displayName 
                                        ?: prevContent?.displayName 
                                        ?: targetId.substringBefore(":")
                                    val senderNameObj = client.user.getById(matrixRoomId, senderId).firstOrNull()?.name ?: senderId.localpart

                                    // sender == target: user acting on themselves (join/leave)
                                    // sender != target: admin acting on someone else (kick/ban/invite)
                                    val isSelf = senderId.full == targetId

                                    val msg = when (membership) {
                                        net.folivo.trixnity.core.model.events.m.room.Membership.JOIN ->
                                            if (isSelf) "$targetName joined the chat" else "$senderNameObj added $targetName"
                                        net.folivo.trixnity.core.model.events.m.room.Membership.LEAVE ->
                                            if (isSelf) "$targetName left the chat" else "$senderNameObj removed $targetName"
                                        net.folivo.trixnity.core.model.events.m.room.Membership.INVITE ->
                                            "$senderNameObj invited $targetName"
                                        net.folivo.trixnity.core.model.events.m.room.Membership.BAN ->
                                            "$senderNameObj banned $targetName"
                                        else -> return@combine null
                                    }
                                    Triple(msg, null, true)
                                }
                                // Redacted (deleted) message — show as system message
                                event.content is net.folivo.trixnity.core.model.events.RedactedEventContent -> {
                                    val redactorName = client.user
                                        .getById(matrixRoomId, senderId)
                                        .firstOrNull()?.name ?: senderId.localpart
                                    Triple("$redactorName deleted this message", null, true)
                                }
                                // State events, redactions, etc. — skip
                                else -> {
                                    android.util.Log.d("BeeperChatRoom", "Skipped event: id=${event?.id?.full}, eventClass=${event?.let { it::class.simpleName }}, contentClass=${event?.content?.let { it::class.simpleName }}")
                                    return@combine null
                                }
                            }

                            val senderName = client.user
                                .getById(matrixRoomId, senderId)
                                .firstOrNull()?.name ?: senderId.localpart

                            // originTimestamp is Long (ms since epoch) on ClientEvent.RoomEvent
                            val ts: Long = try {
                                event.originTimestamp
                            } catch (_: Exception) { 0L }

                            val reactions = reactionAgg?.reactions?.mapValues { it.value.size } ?: emptyMap()
                            
                            val replyContent = replyEvent?.content?.getOrNull()
                            val replyText = replyContent?.let { 
                                val isRep = (it as? MessageEventContent)?.relatesTo?.replyTo?.eventId != null
                                resolveContent(it, isReply = isRep)?.first 
                            }
                            val replySenderName = replyEvent?.event?.sender?.let { sId ->
                                client.user.getById(matrixRoomId, sId).firstOrNull()?.name ?: sId.localpart
                            }

                            val txId = event.unsigned?.transactionId
                            android.util.Log.d("BeeperChatRoom", "Timeline msg mapping: id=${event.id.full} txId=$txId text=$text")

                            ChatMessage(
                                id           = event.id.full,
                                senderName   = senderName,
                                content      = text,
                                isMine       = isMine,
                                timestamp    = ts,
                                mediaContent = mediaContent,
                                reactions    = reactions,
                                replyToText  = replyText,
                                replyToName  = replySenderName,
                                transactionId = txId,
                                isSystemMessage = isSystemMessage,
                            )
                        }
                }
            }

            val lastEventId = room.lastEventId ?: return@launch
            timeline.init(lastEventId)

            // Keep loading newer events, and load history if we don't have enough
            viewModelScope.launch {
                combine(timeline.state, _messages, _messageLimit) { state, msgs, limit ->
                    Triple(state, msgs, limit)
                }.collect { (state, msgs, limit) ->
                    if (state.canLoadAfter && !state.isLoadingAfter) {
                        timeline.loadAfter()
                    }
                    if (state.canLoadBefore && !state.isLoadingBefore && msgs.size < limit) {
                        timeline.loadBefore()
                    }
                    _canLoadMore.value = state.canLoadBefore || msgs.size >= limit
                }
            }

            val latestReadEventIdFlow = client.user.getAllReceipts(matrixRoomId)
                .flatMapLatest { receiptsMap ->
                    val otherUsersFlows = receiptsMap.filterKeys { it != client.userId }.values
                    if (otherUsersFlows.isEmpty()) flowOf(null as String?)
                    else combine(otherUsersFlows) { userReceiptsArray ->
                        var latestEventId: String? = null
                        var maxTs = 0L
                        for (receipts in userReceiptsArray) {
                            val readReceipt = receipts?.receipts?.get(net.folivo.trixnity.core.model.events.m.ReceiptType.Read)
                                ?: receipts?.receipts?.get(net.folivo.trixnity.core.model.events.m.ReceiptType.FullyRead)
                            if (readReceipt != null) {
                                val ts = readReceipt.receipt.timestamp
                                if (ts > maxTs) {
                                    maxTs = ts
                                    latestEventId = readReceipt.eventId.full
                                }
                            }
                        }
                        latestEventId
                    }
                }

            combine(timeline.state, _messageLimit) { state, limit -> Pair(state, limit) }
                .flatMapLatest { (state, limit) ->
                    val flows = state.elements
                    if (flows.isEmpty()) flowOf(emptyList())
                    else combine(flows) { it.toList().filterNotNull().takeLast(limit) }
                }
                .combine(latestReadEventIdFlow) { resolved, latestReadId ->
                    if (latestReadId != null) {
                        val readIndex = resolved.indexOfLast { it.id == latestReadId }
                        if (readIndex != -1) {
                            resolved.mapIndexed { index, msg -> 
                                if (index <= readIndex) msg.copy(isRead = true) else msg 
                            }
                        } else resolved
                    } else resolved
                }
                .collect { resolved ->
                    val deduped = mutableListOf<ChatMessage>()
                    for (msg in resolved) {
                        if (msg.isSystemMessage && !msg.content.endsWith("deleted this message")) {
                            val duplicate = deduped.findLast { it.isSystemMessage && it.content == msg.content }
                            // Skip if we already saw this exact system message in this timeline batch
                            if (duplicate != null) {
                                continue
                            }
                        }
                        deduped.add(msg)
                    }
                    _messages.value = deduped
                }
        }
    }

    fun sendMessage(text: String, replyToId: String? = null) {
        if (text.isBlank()) return
        viewModelScope.launch {
            try {
                android.util.Log.d("BeeperChatRoom", "Sending message: $text")
                client.room.sendMessage(matrixRoomId) {
                    var htmlText = text
                    var fallbackBody = text

                    if (replyToId != null) {
                        val replyEvent = client.room.getTimelineEvent(matrixRoomId, EventId(replyToId)).firstOrNull()
                        if (replyEvent != null) {
                            reply(replyEvent)

                            val repliedContent = replyEvent.content?.getOrNull()
                            if (repliedContent is net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent) {
                                val repliedBody = repliedContent.body
                                val repliedSender = replyEvent.event.sender.full
                                val repliedEventId = replyEvent.event.id.full
                                fallbackBody = "> <$repliedSender> $repliedBody\n\n$text"
                                
                                val escapedText = text.replace("&", "&amp;")
                                    .replace("<", "&lt;")
                                    .replace(">", "&gt;")
                                    .replace("\n", "<br/>")
                                    
                                htmlText = """
                                    <mx-reply>
                                      <blockquote>
                                        <a href="https://matrix.to/#/${matrixRoomId.full}/$repliedEventId">In reply to</a> <a href="https://matrix.to/#/$repliedSender">$repliedSender</a>
                                        <br/>
                                        $repliedBody
                                      </blockquote>
                                    </mx-reply>
                                    $escapedText
                                """.trimIndent()
                            }
                        }
                    }
                    text(body = fallbackBody, format = "org.matrix.custom.html", formattedBody = htmlText)
                }
                android.util.Log.d("BeeperChatRoom", "Message put into outbox successfully")
            } catch (e: Exception) {
                android.util.Log.e("BeeperChatRoom", "Error sending message", e)
            }
        }
    }

    fun sendReaction(eventId: String, emoji: String) {
        viewModelScope.launch {
            try {
                client.api.room.sendMessageEvent(
                    matrixRoomId,
                    ReactionEventContent(RelatesTo.Annotation(EventId(eventId), emoji))
                )
            } catch (e: Exception) {
                android.util.Log.e("BeeperChatRoom", "Error sending reaction", e)
            }
        }
    }

    fun retryMessage(transactionId: String) {
        viewModelScope.launch {
            try {
                client.room.retrySendMessage(matrixRoomId, transactionId)
            } catch (e: Exception) {
                android.util.Log.e("BeeperChatRoom", "Error retrying message", e)
            }
        }
    }

    fun cancelMessage(transactionId: String) {
        viewModelScope.launch {
            try {
                client.room.cancelSendMessage(matrixRoomId, transactionId)
            } catch (e: Exception) {
                android.util.Log.e("BeeperChatRoom", "Error canceling message", e)
            }
        }
    }

    fun unsendMessage(eventId: String) {
        viewModelScope.launch {
            try {
                client.api.room.redactEvent(
                    roomId = matrixRoomId,
                    eventId = net.folivo.trixnity.core.model.EventId(eventId),
                    txnId = java.util.UUID.randomUUID().toString(),
                    reason = null
                )
            } catch (e: Exception) {
                android.util.Log.e("BeeperChatRoom", "Error unsending message", e)
            }
        }
    }
}

// ── Screen ─────────────────────────────────────────────────────────────────────

class BeeperChatRoomScreen(
    sealedActivity: SealedLightActivity,
    private val roomId: String,
) : LightScreen<Unit, BeeperChatRoomViewModel>(sealedActivity) {

    override val viewModelClass: Class<BeeperChatRoomViewModel>
        get() = BeeperChatRoomViewModel::class.java

    override fun createViewModel() =
        BeeperChatRoomViewModel(BeeperRepository.getClient()!!, roomId)

    @Composable
    override fun Content() {
        val permissionLauncher = com.thelightphone.sdk.rememberPermissionRequestLauncher(android.Manifest.permission.RECORD_AUDIO)
        androidx.compose.runtime.LaunchedEffect(permissionLauncher) {
            val result = com.thelightphone.sdk.checkPermission(android.Manifest.permission.RECORD_AUDIO)
            val isGranted = result.getOrNull()?.permissionResult == com.thelightphone.sdk.shared.LightServiceMethod.GetPermission.Result.Granted
            if (!isGranted && BeeperPermissions.shouldAsk(android.Manifest.permission.RECORD_AUDIO)) {
                permissionLauncher?.launch()
            }
        }

        val themeColors     by LightThemeController.colors.collectAsState()
        val messages        by viewModel.messages.collectAsState()
        val canLoadMore     by viewModel.canLoadMore.collectAsState()
        val roomName        by viewModel.roomName.collectAsState()
        val textFieldState = androidx.compose.foundation.text.input.rememberTextFieldState("")
        var isEditingMessage by remember { mutableStateOf(false) }
        
        var selectedMessage by remember { mutableStateOf<ChatMessage?>(null) }
        var isReplyingTo    by remember { mutableStateOf<ChatMessage?>(null) }
        var isReactingTo    by remember { mutableStateOf<ChatMessage?>(null) }
        
        
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()

        // Seamless pagination
        androidx.compose.runtime.LaunchedEffect(listState.firstVisibleItemIndex, canLoadMore) {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (canLoadMore && totalItems > 0 && listState.firstVisibleItemIndex >= totalItems - 5) {
                viewModel.loadMore()
            }
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                // ── Full-screen text input editor (system keyboard) ───────────
                if (isEditingMessage) {

                    BeeperSystemTextEditor(
                        title            = "Message",
                        state            = textFieldState,
                        onSubmit         = { result ->
                            val resultText = result.toString()
                            android.util.Log.d("BeeperChatRoom", "Submit: '$resultText'")
                            isEditingMessage = false
                            if (resultText.isNotBlank()) {
                                viewModel.sendMessage(resultText, isReplyingTo?.id)
                                textFieldState.edit { replace(0, length, "") }
                                isReplyingTo = null
                            }
                        },
                        onBack  = { isEditingMessage = false },
                        modifier = Modifier.fillMaxSize()
                    )
                    return@Box
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // ── Top bar ──────────────────────────────────────────────
                    LightTopBar(
                        leftButton  = LightBarButton.LightIcon(
                            icon    = LightIcons.BACK,
                            onClick = { goBack(null) },
                        ),
                        center      = LightTopBarCenter.Text(roomName),
                        rightButton = LightBarButton.LightIcon(
                            icon    = LightIcons.CAMERA,
                            onClick = { navigateTo(screenFactory = { BeeperCameraScreen(it, roomId) }) },
                        ),
                        modifier    = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    // ── Message list ─────────────────────────────────────────
                    // ── Message list ─────────────────────────────────────────
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        itemsIndexed(messages.asReversed()) { index, msg ->
                            val reversedMessages = messages.asReversed()
                            val olderMsg = reversedMessages.getOrNull(index + 1)
                            
                            // ── Date separator logic ────────────────────────────
                            var isFirstOfDay = false
                            if (msg.timestamp > 0L) {
                                val msgCal = Calendar.getInstance().also { it.timeInMillis = msg.timestamp }
                                val dayOfYear = msgCal.get(Calendar.YEAR) * 1000 + msgCal.get(Calendar.DAY_OF_YEAR)
                                
                                val olderDayOfYear = if (olderMsg != null && olderMsg.timestamp > 0L) {
                                    val olderCal = Calendar.getInstance().also { it.timeInMillis = olderMsg.timestamp }
                                    olderCal.get(Calendar.YEAR) * 1000 + olderCal.get(Calendar.DAY_OF_YEAR)
                                } else {
                                    -1
                                }
                                
                                if (dayOfYear != olderDayOfYear) {
                                    isFirstOfDay = true
                                }
                            }
                            
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (isFirstOfDay) {
                                    Row(
                                        modifier              = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 0.5f.gridUnitsAsDp()),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        LightText(
                                            text    = formatDateSeparator(msg.timestamp),
                                            variant = LightTextVariant.Fine,
                                            lighten = true,
                                        )
                                    }
                                }

                                // ── Short line separator between messages ────────
                                if (!isFirstOfDay && index < reversedMessages.size - 1) {
                                    Box(
                                        modifier = Modifier
                                            .padding(vertical = 0.2f.gridUnitsAsDp())
                                            .size(width = 1.5f.gridUnitsAsDp(), height = 1.dp)
                                            .background(androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.3f))
                                    )
                                }

                                if (msg.isSystemMessage) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 0.5f.gridUnitsAsDp()),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        LightText(
                                            text = msg.content,
                                            variant = LightTextVariant.Fine,
                                            lighten = true
                                        )
                                    }
                                } else {
                                    // ── Message bubble ────────────────────────────
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 0.4f.gridUnitsAsDp())
                                            .clickable { selectedMessage = msg },
                                    ) {
                                        // Sender name + time on the same row
                                        Row(
                                            modifier              = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment     = Alignment.CenterVertically,
                                        ) {
                                            LightText(
                                                text     = if (msg.isMine) "You" else msg.senderName,
                                                variant  = LightTextVariant.Fine,
                                                lighten  = true,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (msg.timestamp > 0L) {
                                                val statusStr = when (msg.sendState) {
                                                    SendState.FAILED -> " ⚠️ Failed"
                                                    SendState.PENDING -> " ⏳ Pending"
                                                    SendState.SENT -> {
                                                        if (msg.isMine) {
                                                            if (msg.isRead) " ✓✓" else " ✓"
                                                        } else ""
                                                    }
                                                }
                                                LightText(
                                                    text    = formatMessageTime(msg.timestamp) + statusStr,
                                                    variant = LightTextVariant.Fine,
                                                    lighten = true
                                                )
                                            }
                                        }
                                        // Message content — image or text
                                        if (msg.replyToText != null) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 0.2f.gridUnitsAsDp())
                                                    .graphicsLayer {
                                                        scaleX = 0.85f
                                                        scaleY = 0.85f
                                                        transformOrigin = TransformOrigin(0f, 0f)
                                                        alpha = 0.7f
                                                    }
                                            ) {
                                                LightText(
                                                    text = "Replying to ${msg.replyToName ?: "someone"}",
                                                    variant = LightTextVariant.Fine,
                                                    lighten = true
                                                )
                                                LightText(
                                                    text = msg.replyToText,
                                                    variant = LightTextVariant.Fine,
                                                    lighten = true,
                                                    maxLines = 2,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                            }
                                        }
        
                                        if (msg.mediaContent is RoomMessageEventContent.FileBased.Audio) {
                                            val client = BeeperRepository.getClient()
                                            if (client != null) {
                                                MatrixAudioPlayer(
                                                    client = client,
                                                    audio  = msg.mediaContent,
                                                    modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                                                )
                                            }
                                        } else if (msg.mediaContent != null) {
                                            val client = BeeperRepository.getClient()
                                            if (client != null) {
                                                MatrixMedia(
                                                    client   = client,
                                                    media    = msg.mediaContent,
                                                    modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                                                )
                                            }
                                        }
                                        
                                        val textToRender = if (msg.mediaContent is RoomMessageEventContent.FileBased.Audio && msg.content.startsWith("[Audio] ")) {
                                            msg.content.substringAfter("[Audio] ")
                                        } else if (msg.mediaContent is RoomMessageEventContent.FileBased.Audio && msg.content == "[Audio]") {
                                            null
                                        } else if (msg.mediaContent != null && msg.content.startsWith("[Photo] ")) {
                                            msg.content.substringAfter("[Photo] ")
                                        } else if (msg.mediaContent != null && msg.content.startsWith("[Video] ")) {
                                            msg.content.substringAfter("[Video] ")
                                        } else if (msg.mediaContent != null && (msg.content == "[Photo]" || msg.content == "[Video]")) {
                                            null
                                        } else {
                                            msg.content
                                        }
                                        
                                        if (textToRender != null) {
                                            LightText(
                                                text    = textToRender,
                                                variant = LightTextVariant.Copy,
                                                modifier = if (msg.mediaContent != null) Modifier.padding(top = 0.25f.gridUnitsAsDp()) else Modifier
                                            )
                                        }
                                        
                                        if (msg.reactions.isNotEmpty()) {
                                            Row(
                                                modifier = Modifier.padding(top = 0.2f.gridUnitsAsDp()),
                                                horizontalArrangement = Arrangement.spacedBy(0.4f.gridUnitsAsDp())
                                            ) {
                                                msg.reactions.forEach { (key, count) ->
                                                    LightText(
                                                        text = "$key $count",
                                                        variant = LightTextVariant.Fine,
                                                        lighten = true
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        if (canLoadMore) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 1f.gridUnitsAsDp()),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    LightText(
                                        text = "LOADING...",
                                        variant = LightTextVariant.Fine,
                                        lighten = true,
                                    )
                                }
                            }
                        }
                    }

                    // ── Reply preview ──────────────────────────────────────────
                    if (isReplyingTo != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                LightText(
                                    text = "Replying to ${isReplyingTo?.senderName ?: "someone"}",
                                    variant = LightTextVariant.Fine,
                                    lighten = true
                                )
                                LightText(
                                    text = isReplyingTo?.content ?: "",
                                    variant = LightTextVariant.Fine,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(modifier = Modifier.lightClickable(onClick = { isReplyingTo = null })) {
                                LightIcon(icon = LightIcons.CLOSE, modifier = Modifier.size(1.5f.gridUnitsAsDp()))
                            }
                        }
                    }

                    // ── Message input field ──────────────────────────────────
                    LightTextField(
                        label       = "Message",
                        value       = textFieldState.text.toString(),
                        placeholder = "Type a message",
                        onClick     = { isEditingMessage = true },
                        modifier    = Modifier
                            .fillMaxWidth()
                            .padding(1f.gridUnitsAsDp())
                    )
                }
                
                // ── Action Modals ────────────────────────────────────────────
                if (selectedMessage != null && isReactingTo == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(themeColors.background)
                    ) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LightText("Message Options", variant = LightTextVariant.Subheading)
                                val msg = selectedMessage
                                if (msg != null && msg.sendState != SendState.SENT && msg.transactionId != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(1f.gridUnitsAsDp())
                                            .lightClickable(onClick = {
                                                viewModel.retryMessage(msg.transactionId)
                                                selectedMessage = null
                                            })
                                    ) {
                                        LightText("Retry", variant = LightTextVariant.Copy, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(1f.gridUnitsAsDp())
                                            .lightClickable(onClick = {
                                                viewModel.cancelMessage(msg.transactionId)
                                                selectedMessage = null
                                            })
                                    ) {
                                        LightText("Cancel", variant = LightTextVariant.Copy, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(1f.gridUnitsAsDp())
                                            .lightClickable(onClick = {
                                                isReplyingTo = selectedMessage
                                                selectedMessage = null
                                            })
                                    ) {
                                        LightText("Reply", variant = LightTextVariant.Copy, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(1f.gridUnitsAsDp())
                                            .lightClickable(onClick = {
                                                isReactingTo = selectedMessage
                                                selectedMessage = null
                                            })
                                    ) {
                                        LightText("React", variant = LightTextVariant.Copy, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                    }
                                    if (msg != null && msg.isMine) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(1f.gridUnitsAsDp())
                                                .lightClickable(onClick = {
                                                    viewModel.unsendMessage(msg.id)
                                                    selectedMessage = null
                                                })
                                        ) {
                                            LightText("Unsend", variant = LightTextVariant.Copy, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                }
                            }
                        }
                        LightBottomBar(
                            items = listOf(
                                LightBarButton.LightIcon(
                                    icon = LightIcons.CLOSE,
                                    onClick = { selectedMessage = null },
                                ),
                            ),
                        )
                    }
                }
                
                if (isReactingTo != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(themeColors.background)
                    ) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LightText("React", variant = LightTextVariant.Copy)
                                val emojis1 = listOf("👍", "❤️", "😂")
                                val emojis2 = listOf("😮", "👌", "❓")
                                
                                @Composable
                                fun EmojiRow(emojis: List<String>) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp()),
                                        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp())
                                    ) {
                                        emojis.forEach { emoji ->
                                            Box(
                                                modifier = Modifier
                                                    .padding(0.5f.gridUnitsAsDp())
                                                    .lightClickable(onClick = {
                                                        viewModel.sendReaction(isReactingTo!!.id, emoji)
                                                        isReactingTo = null
                                                    })
                                            ) {
                                                LightText(emoji, variant = LightTextVariant.Copy)
                                            }
                                        }
                                    }
                                }
                                
                                EmojiRow(emojis1)
                                EmojiRow(emojis2)
                            }
                        }
                        LightBottomBar(
                            items = listOf(
                                LightBarButton.LightIcon(
                                    icon = LightIcons.CLOSE,
                                    onClick = { isReactingTo = null },
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}
