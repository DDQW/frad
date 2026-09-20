package me.woelki.friendradar.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import me.woelki.friendradar.ble.BleChatController
import me.woelki.friendradar.chat.ChatController
import me.woelki.friendradar.chat.ChatMessage
import me.woelki.friendradar.chat.ChatUiState
import me.woelki.friendradar.chat.MAX_TRANSFER_FILE_BYTES
import me.woelki.friendradar.contacts.ChatHistoryStore
import me.woelki.friendradar.contacts.Contact
import me.woelki.friendradar.contacts.ContactStore
import me.woelki.friendradar.crypto.Identity
import me.woelki.friendradar.data.MediaFileStore
import me.woelki.friendradar.profile.Profile
import me.woelki.friendradar.safety.BlockList
import me.woelki.friendradar.wideradius.CoarseLocation
import me.woelki.friendradar.wideradius.Geohash
import me.woelki.friendradar.wideradius.WideRangeChatController
import me.woelki.friendradar.wideradius.WideRangeNode

/** Which discovery layer [ChatViewModel] is currently routing through. */
enum class ChatMode { LOCAL_BLE, WIDE_RANGE }

@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest, used to keep `state`/`transferStatus` a single stable flow across mode switches
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val identity = Identity.loadOrCreate(application)
    private val profile = Profile(application)
    private val contactStore = ContactStore(application)
    private val historyStore = ChatHistoryStore(application)
    private val mediaFileStore = MediaFileStore(application)
    private val blockList = BlockList(application)

    private val bleController: ChatController = BleChatController(application, identity, profile)
    private val wideController: ChatController =
        WideRangeChatController(application, identity, profile, WideRangeNode(application))

    private val _mode = MutableStateFlow(ChatMode.LOCAL_BLE)
    val mode: StateFlow<ChatMode> = _mode.asStateFlow()
    val wideRangeAvailable: Boolean get() = WideRangeNode.isSupported

    private fun controllerFor(mode: ChatMode): ChatController = if (mode == ChatMode.LOCAL_BLE) bleController else wideController
    private val activeController: ChatController get() = controllerFor(_mode.value)

    // flatMapLatest keeps this a single stable StateFlow reference across mode switches, so
    // RadarScreen's collectAsState() never needs to know two controllers exist underneath.
    val state: StateFlow<ChatUiState> = _mode.flatMapLatest { controllerFor(it).state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState.Idle)
    val transferStatus: StateFlow<String?> = _mode.flatMapLatest { controllerFor(it).transferStatus }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val fileTransferAvailable: Boolean get() = activeController.fileTransferAvailable
    val myPeerId: String get() = identity.peerId

    private val _errorEvent = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = _errorEvent.asStateFlow()
    fun consumeErrorEvent() { _errorEvent.value = null }

    var myPseudonym: String
        get() = profile.pseudonym
        set(value) { profile.pseudonym = value }

    var coarseGeohash: String?
        get() = profile.coarseGeohash
        set(value) { profile.coarseGeohash = value }

    var searchRadiusKm: Double
        get() = profile.searchRadiusKm
        set(value) { profile.searchRadiusKm = value }

    var bootstrapNodes: List<String>
        get() = profile.bootstrapNodes
        set(value) { profile.bootstrapNodes = value }

    /** Looks up a one-shot, coarse-only location fix (requires the caller to already hold
     *  ACCESS_COARSE_LOCATION - see RadarScreen's "Use my area" button) and immediately reduces
     *  it to a [Geohash] cell, discarding the raw coordinate; stores and returns the result, or
     *  null if the permission isn't granted or no location is available yet. */
    fun useCurrentAreaAsGeohash(): String? {
        val precision = Geohash.precisionForRadiusKm(profile.searchRadiusKm)
        val geohash = CoarseLocation.lastKnownGeohash(getApplication(), precision) ?: return null
        profile.coarseGeohash = geohash
        return geohash
    }

    /** Only switches while idle on the outgoing layer, mirroring [ChatController.setBrowsing]'s
     *  own guard against switching transport mid-chat. */
    fun setMode(newMode: ChatMode) {
        if (_mode.value == newMode) return
        controllerFor(_mode.value).setBrowsing(false)
        _mode.value = newMode
    }

    fun setBrowsing(enabled: Boolean) = activeController.setBrowsing(enabled)
    fun requestRandomChat() = activeController.requestRandomChat()
    fun sendMessage(text: String) = activeController.sendMessage(text)
    fun endChat() = activeController.endActiveConnection("you left")
    fun blockActivePeer() = activeController.blockActivePeer()
    fun reportActivePeer(reason: String) = activeController.reportActivePeer(reason)

    /** Reads [uri] fully into memory (files this small are the whole point of the 25 MB cap)
     *  and hands it to the active controller; surfaces [errorEvent] instead of sending if the
     *  file can't be read or is over the cap. */
    fun sendFile(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            _errorEvent.value = "Couldn't read that file."
            return
        }
        if (bytes.size > MAX_TRANSFER_FILE_BYTES) {
            _errorEvent.value = "That file is too large to send (max ${MAX_TRANSFER_FILE_BYTES / (1024 * 1024)} MB)."
            return
        }
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val fileName = displayNameOf(resolver, uri) ?: "file"
        activeController.sendFile(bytes, fileName, mimeType)
    }

    private fun displayNameOf(resolver: ContentResolver, uri: Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return null
    }

    fun contacts(): List<Contact> = contactStore.all()
    fun isContactSaved(peerId: String): Boolean = contactStore.isSaved(peerId)

    fun saveContact(peerId: String, alias: String) {
        contactStore.save(peerId, alias)
        // Backfill this session's messages (sent/received before the save happened, so the
        // active controller hadn't started persisting them yet) into their history.
        val current = state.value
        if (current is ChatUiState.Chatting && current.remotePeerId == peerId) {
            historyStore.backfillIfEmpty(peerId, current.messages)
        }
    }

    fun removeContact(peerId: String) {
        contactStore.remove(peerId)
        historyStore.clear(peerId)
        mediaFileStore.delete(peerId)
    }

    fun historyWith(peerId: String): List<ChatMessage> = historyStore.messagesFor(peerId)

    fun blockedPeerIds(): List<String> = blockList.blockedIds().toList()
    fun unblock(peerId: String) = blockList.unblock(peerId)
}
