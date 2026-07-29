package com.beeper.lightos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import android.Manifest
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.viewModelScope
import net.folivo.trixnity.client.room
import net.folivo.trixnity.client.user
import net.folivo.trixnity.client.key
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import java.util.Calendar

data class RoomSummary(
    val roomId: String,
    val displayName: String,
    val lastMessage: String,
    val unreadCount: Long,
    val lastTimestamp: Long,
    val isFavorite: Boolean = false,
)

/** Format a millisecond timestamp for display in the chat list. */
private fun formatRoomTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val msg = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()
    val sameDay = msg.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            msg.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val withinWeek = (now.timeInMillis - timestamp) < 7L * 24 * 60 * 60 * 1000
    val thisYear = msg.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
    val days   = arrayOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat")
    return when {
        sameDay    -> String.format("%02d:%02d", msg.get(Calendar.HOUR_OF_DAY), msg.get(Calendar.MINUTE))
        withinWeek -> days[msg.get(Calendar.DAY_OF_WEEK) - 1]
        thisYear   -> "${months[msg.get(Calendar.MONTH)]} ${msg.get(Calendar.DAY_OF_MONTH)}"
        else       -> "${months[msg.get(Calendar.MONTH)]} ${msg.get(Calendar.DAY_OF_MONTH)}, ${msg.get(Calendar.YEAR)}"
    }
}

/** Extract a short human-readable preview string from a Matrix message content. */
private fun contentPreview(content: Any?): String? {
    android.util.Log.e("BeeperPreviewDebug", "Content class: ${content?.let { it::class.simpleName }}, toString: $content")
    if (content == null) return null
    if (content is RoomMessageEventContent.TextBased) {
        var text = content.body
        val formatted = content.formattedBody
        val format = content.format
        
        if (format == "org.matrix.custom.html" && formatted != null && formatted.contains("<mx-reply>")) {
            val withoutReply = formatted.replace(Regex("<mx-reply>.*?</mx-reply>", RegexOption.DOT_MATCHES_ALL), "")
            val htmlText = android.text.Html.fromHtml(withoutReply, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            if (htmlText.isNotEmpty()) {
                text = htmlText
            } else {
                // Fallback to plain text if HTML was only the quote
                val index = text.indexOf("\n\n")
                if (index != -1) text = text.substring(index + 2).trimStart()
            }
        } else if (text.trimStart().startsWith(">")) {
            val index = text.indexOf("\n\n")
            if (index != -1) {
                text = text.substring(index + 2).trimStart()
            }
        }
        return text.take(80)
    }

    if (content is net.folivo.trixnity.core.model.events.UnknownEventContent ||
        content is net.folivo.trixnity.core.model.events.m.ReactionEventContent ||
        content is net.folivo.trixnity.core.model.events.RedactedEventContent ||
        content is net.folivo.trixnity.core.model.events.m.room.RedactionEventContent ||
        content is net.folivo.trixnity.core.model.events.m.room.MemberEventContent) {
        return null // Don't fall through to Media fallback for non-visual events
    }

    if (content is net.folivo.trixnity.core.model.events.m.room.EncryptedMessageEventContent) {
        return "[Encrypted]"
    }

    val name = content::class.simpleName?.lowercase() ?: ""

    return when {
        "image"   in name -> {
            if (content is net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent.FileBased.Image) {
                var text = content.body
                if (text.startsWith(">") || (content.formattedBody?.contains("<mx-reply>") == true)) {
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
                if (caption != null) "[Photo] $caption" else "[Photo]"
            } else "[Photo]"
        }
        "video"   in name -> {
            if (content is net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent.FileBased.Video) {
                var text = content.body
                if (text.startsWith(">") || (content.formattedBody?.contains("<mx-reply>") == true)) {
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
                if (caption != null) "[Video] $caption" else "[Video]"
            } else "[Video]"
        }
        "audio"   in name -> {
            if (content is net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent.FileBased.Audio) {
                var text = content.body
                if (text.startsWith(">") || (content.formattedBody?.contains("<mx-reply>") == true)) {
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
                if (caption != null) "[Audio] $caption" else "[Audio]"
            } else "[Audio]"
        }
        "file"    in name -> "[File]"
        "sticker" in name -> "[Sticker]"
        else              -> "[Media] (${content::class.simpleName})"
    }
}

class BeeperChatListViewModel : LightViewModel<Unit>() {
    private val _rooms = MutableStateFlow<List<RoomSummary>>(emptyList())
    val rooms: StateFlow<List<RoomSummary>> = _rooms.asStateFlow()

    private val _isVerified = MutableStateFlow(true)
    val isVerified: StateFlow<Boolean> = _isVerified.asStateFlow()

    private val prefs
        get() = BeeperRepository.appContext?.getSharedPreferences(
            "beeper_prefs", android.content.Context.MODE_PRIVATE
        )

    private val _showOnlyFavorites =
        MutableStateFlow(prefs?.getBoolean("show_only_favorites", true) ?: true)
    val showOnlyFavorites: StateFlow<Boolean> = _showOnlyFavorites.asStateFlow()

    private val latestRooms        = java.util.concurrent.ConcurrentHashMap<String, net.folivo.trixnity.client.store.Room>()
    private val latestRoomNames    = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val latestLastMessages = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val latestFavorites    = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun toggleFavoritesFilter() {
        val newValue = !_showOnlyFavorites.value
        _showOnlyFavorites.value = newValue
        prefs?.edit()?.putBoolean("show_only_favorites", newValue)?.apply()
        rebuildRooms()
    }

    private fun rebuildRooms() {
        val sortedRooms = latestRooms.values
            .filter { it.lastRelevantEventTimestamp != null }
            .filter { !_showOnlyFavorites.value || latestFavorites[it.roomId.full] == true }
            .sortedByDescending { it.lastRelevantEventTimestamp }
            .take(20)

        _rooms.value = sortedRooms.map { r ->
            RoomSummary(
                roomId      = r.roomId.full,
                displayName = latestRoomNames[r.roomId.full] ?: "Chat",
                lastMessage = latestLastMessages[r.roomId.full] ?: "",
                unreadCount = r.unreadMessageCount,
                lastTimestamp = r.lastRelevantEventTimestamp
                    ?.toEpochMilliseconds() ?: 0L,
                isFavorite  = latestFavorites[r.roomId.full] == true,
            )
        }
    }

    init {
        val client = BeeperRepository.getClient()
        if (client != null) {
            viewModelScope.launch {
                client.key.getTrustLevel(client.userId, client.deviceId).collect { level ->
                    _isVerified.value =
                        level is net.folivo.trixnity.crypto.key.DeviceTrustLevel.CrossSigned && level.verified
                }
            }
            viewModelScope.launch(Dispatchers.IO) {
                val jobs    = mutableMapOf<String, kotlinx.coroutines.Job>()
                val tagJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

                client.room.getAll().collect { roomFlowsMap ->
                    val currentRoomIds = roomFlowsMap.keys.map { it.full }.toSet()
                    if (currentRoomIds.isEmpty()) return@collect

                    // Cancel jobs for removed rooms
                    val it = jobs.iterator()
                    while (it.hasNext()) {
                        val entry = it.next()
                        if (entry.key !in currentRoomIds) {
                            entry.value.cancel()
                            tagJobs.remove(entry.key)?.cancel()
                            latestRooms.remove(entry.key)
                            latestRoomNames.remove(entry.key)
                            latestLastMessages.remove(entry.key)
                            latestFavorites.remove(entry.key)
                            it.remove()
                        }
                    }

                    // Launch collector for each new room
                    roomFlowsMap.forEach { (roomId, roomFlow) ->
                        val id = roomId.full
                        // Track the m.favourite tag (room account data) per room
                        if (tagJobs[id] == null) {
                            tagJobs[id] = launch {
                                client.room.getAccountData(
                                    roomId,
                                    net.folivo.trixnity.core.model.events.m.TagEventContent::class,
                                ).collect { tagContent ->
                                    val fav = tagContent?.tags?.containsKey(
                                        net.folivo.trixnity.core.model.events.m.TagEventContent.TagName.Favourite
                                    ) == true
                                    val previous = latestFavorites.put(id, fav)
                                    if (previous != fav) rebuildRooms()
                                }
                            }
                        }
                        if (jobs[id] == null) {
                            jobs[id] = launch {
                                roomFlow.flatMapLatest { room ->
                                    if (room == null) flowOf(null)
                                    else {
                                        // ── Detect bridge type from hero user's localpart ──
                                        val heroLocalpart = room.name?.heroes?.firstOrNull()?.localpart ?: ""
                                        var bridgeLocalpart = heroLocalpart
                                        if (bridgeLocalpart.isEmpty()) {
                                            val allUsers = client.user.getAll(room.roomId).firstOrNull()?.keys ?: emptySet()
                                            bridgeLocalpart = allUsers.find {
                                                it.localpart.startsWith("whatsapp_") ||
                                                it.localpart.startsWith("linkedin_") ||
                                                it.localpart.startsWith("telegram_") ||
                                                it.localpart.startsWith("instagram_") ||
                                                it.localpart.startsWith("discord_") ||
                                                it.localpart.startsWith("signal_") ||
                                                it.localpart.startsWith("slack_") ||
                                                it.localpart.startsWith("googlechat_") ||
                                                it.localpart.startsWith("imessage_") ||
                                                it.localpart.startsWith("android_sms_")
                                            }?.localpart ?: ""
                                        }
                                        
                                        val prefix = when {
                                            bridgeLocalpart.startsWith("whatsapp_")   -> "[WA] "
                                            bridgeLocalpart.startsWith("linkedin_")   -> "[LI] "
                                            bridgeLocalpart.startsWith("telegram_")   -> "[TG] "
                                            bridgeLocalpart.startsWith("instagram_")  -> "[IG] "
                                            bridgeLocalpart.startsWith("discord_")    -> "[DC] "
                                            bridgeLocalpart.startsWith("signal_")     -> "[SG] "
                                            bridgeLocalpart.startsWith("slack_")      -> "[SL] "
                                            bridgeLocalpart.startsWith("googlechat_") -> "[GC] "
                                            bridgeLocalpart.startsWith("imessage_")   -> "[iMsg] "
                                            bridgeLocalpart.startsWith("android_sms_")-> "[SMS] "
                                            else                                       -> ""
                                        }
                                        
                                        val explicitName = room.name?.explicitName
                                        val computedName = explicitName
                                            ?: room.name?.heroes?.mapNotNull { heroId ->
                                                client.user.getById(room.roomId, heroId).firstOrNull()?.name
                                                    ?: heroId.localpart
                                            }?.joinToString(", ")?.takeIf { it.isNotBlank() }
                                            ?: "Chat"
                                            
                                        val finalName = prefix + computedName

                                        val startId = room.lastRelevantEventId
                                        if (startId == null) {
                                            flowOf(Triple(room, finalName, ""))
                                        } else {
                                            client.room.getTimelineEvent(room.roomId, startId)
                                                .map { startEvent ->
                                                    // ── Fetch last message preview ──
                                                    var currentEventId: net.folivo.trixnity.core.model.EventId? = startId
                                                    var preview: String? = null
                                                    var attempts = 0
                                                    
                                                    while (currentEventId != null && preview == null && attempts < 20) {
                                                        try {
                                                            val timelineEvent = if (currentEventId == startId) startEvent
                                                            else client.room
                                                                .getTimelineEvent(room.roomId, currentEventId)
                                                                .firstOrNull { it == null || it.content != null }
                                                                
                                                            val eventContent = timelineEvent?.content?.getOrNull()
                                                            preview = contentPreview(eventContent)
                                                            
                                                            if (preview != null) break
                                                            currentEventId = timelineEvent?.previousEventId
                                                        } catch (e: Exception) {
                                                            break
                                                        }
                                                        attempts++
                                                    }
                                                    Triple(room, finalName, preview ?: "")
                                                }
                                        }
                                    }
                                }.collect { result ->
                                    if (result != null) {
                                        val (room, computedName, lastPreview) = result
                                        // Update memory structures
                                        if (lastPreview.isNotEmpty() || latestLastMessages[id] == null) {
                                            latestLastMessages[id] = lastPreview
                                        }
                                        latestRooms[id]        = room
                                        latestRoomNames[id]    = computedName

                                        rebuildRooms()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            BeeperRepository.logout()
        }
    }
}

class BeeperChatListScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, BeeperChatListViewModel>(sealedActivity) {

    override val viewModelClass: Class<BeeperChatListViewModel>
        get() = BeeperChatListViewModel::class.java

    override fun createViewModel() = BeeperChatListViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val rooms       by viewModel.rooms.collectAsState()
        val isVerified  by viewModel.isVerified.collectAsState()
        val showOnlyFavorites by viewModel.showOnlyFavorites.collectAsState()

        var showLogoutConfirmation by remember { mutableStateOf(false) }
        var showMenu by remember { mutableStateOf(false) }
        
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.POST_NOTIFICATIONS)
        androidx.compose.runtime.LaunchedEffect(Unit) {
            permissionLauncher?.launch()
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                LightTopBar(
                    leftButton  = LightBarButton.Text("☰", onClick = {
                        showMenu = true
                    }),
                    center      = LightTopBarCenter.Text(if (showOnlyFavorites) "Favorites" else "Chats"),
                    rightButton = LightBarButton.Text(
                        if (showOnlyFavorites) "All" else "Favs",
                        onClick = { viewModel.toggleFavoritesFilter() },
                    ),
                )

                // Key Backup debug logging (kept from original)
                val client = BeeperRepository.getClient()
                if (client != null) {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        val keyBackupService = client.di.get<net.folivo.trixnity.client.key.KeyBackupService>(
                            org.koin.core.qualifier.named<net.folivo.trixnity.client.key.KeyBackupService>()
                        )
                        keyBackupService.version.collect { version ->
                            android.util.Log.e("BeeperChatList", "KB VERSION: ${version?.version ?: "null"}")
                        }
                    }
                }

                // "Verify Session" banner
                if (!isVerified) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start   = 1f.gridUnitsAsDp(),
                                top     = 0.5f.gridUnitsAsDp(),
                                bottom  = 0.5f.gridUnitsAsDp(),
                            )
                            .lightClickable { navigateTo(screenFactory = { BeeperVerificationScreen(it) }) }
                    ) {
                        LightText(
                            text    = "Verify Session",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    }
                }

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    if (rooms.isEmpty() && showOnlyFavorites) {
                        LightText(
                            text     = "No favorite chats yet. Mark chats as favorite in Beeper, or tap \"All\" to show every chat.",
                            variant  = LightTextVariant.Fine,
                            lighten  = true,
                            modifier = Modifier.padding(
                                top = 1f.gridUnitsAsDp(),
                                end = 1f.gridUnitsAsDp(),
                            ),
                        )
                    }
                    rooms.forEachIndexed { index, room ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = if (index == 0) 0f.gridUnitsAsDp() else 0.75f.gridUnitsAsDp(),
                                    bottom = 0.75f.gridUnitsAsDp()
                                    // leave a right margin for the timestamp/badge
                                )
                                .lightClickable {
                                    navigateTo(screenFactory = { BeeperChatRoomScreen(it, room.roomId) })
                                }
                        ) {
                            // Row 1: Room name  ·  Timestamp
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 1f.gridUnitsAsDp()),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment     = Alignment.CenterVertically,
                            ) {
                                LightText(
                                    text     = room.displayName,
                                    variant  = LightTextVariant.Copy,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (room.lastTimestamp > 0L) {
                                    LightText(
                                        text    = formatRoomTimestamp(room.lastTimestamp),
                                        variant = LightTextVariant.Fine,
                                        lighten = true,
                                    )
                                }
                            }

                            // Row 2: Last message preview  ·  Unread badge
                            if (room.lastMessage.isNotEmpty() || room.unreadCount > 0L) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = 1f.gridUnitsAsDp()),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment     = Alignment.CenterVertically,
                                ) {
                                    LightText(
                                        text     = room.lastMessage,
                                        variant  = LightTextVariant.Fine,
                                        lighten  = room.unreadCount == 0L,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (room.unreadCount > 0L) {
                                        LightText(
                                            text    = if (room.unreadCount > 99L) "99+" else room.unreadCount.toString(),
                                            variant = LightTextVariant.Fine,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } // End LightScrollView
                

                } // End outer Column

                if (showMenu) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background)
                            .lightClickable { }, // Catch background clicks
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LightTopBar(
                                leftButton  = null,
                                center      = LightTopBarCenter.Text("Menu"),
                                rightButton = LightBarButton.Text("Close", onClick = {
                                    showMenu = false
                                }),
                                modifier    = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                            )
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = 1f.gridUnitsAsDp(),
                                        vertical = 0.75f.gridUnitsAsDp()
                                    )
                                    .lightClickable { 
                                        showMenu = false
                                        showLogoutConfirmation = true
                                    },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                LightText("Logout", variant = LightTextVariant.Copy)
                            }
                            
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                            
                            // Version label
                            val versionName = androidx.compose.runtime.remember {
                                try {
                                    me.ironfeet.beeper4lightos.BuildConfig.VERSION_NAME
                                } catch (e: Exception) {
                                    "Unknown"
                                }
                            }
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 0.5f.gridUnitsAsDp()),
                                contentAlignment = Alignment.Center
                            ) {
                                LightText(
                                    text = "v$versionName",
                                    variant = LightTextVariant.Fine,
                                    lighten = true
                                )
                            }
                        }
                    }
                }

                if (showLogoutConfirmation) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background)
                            .lightClickable { }, // Catch background clicks
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(1f.gridUnitsAsDp())
                        ) {
                            LightText("Are you sure you want to log out?", variant = LightTextVariant.Fine)
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 0.5f.gridUnitsAsDp())
                                    .lightClickable { 
                                        showLogoutConfirmation = false
                                        viewModel.logout()
                                        navigateTo(screenFactory = { BeeperLoginScreen(it) })
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                LightText("Confirm Logout", variant = LightTextVariant.Fine)
                            }
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 0.5f.gridUnitsAsDp())
                                    .lightClickable { showLogoutConfirmation = false },
                                contentAlignment = Alignment.Center
                            ) {
                                LightText("Cancel", variant = LightTextVariant.Fine, lighten = true)
                            }
                        }
                    }
                }
            }
        }
    }
}
