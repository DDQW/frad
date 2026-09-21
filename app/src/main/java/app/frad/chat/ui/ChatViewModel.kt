package app.frad.chat.ui

import android.app.Application
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import app.frad.chat.ble.LocalBleService
import app.frad.chat.chat.ChatController
import app.frad.chat.chat.ChatMessage
import app.frad.chat.chat.ChatUiState
import app.frad.chat.chat.MAX_TRANSFER_FILE_BYTES
import app.frad.chat.contacts.ChatHistoryStore
import app.frad.chat.contacts.Contact
import app.frad.chat.contacts.ContactStore
import app.frad.chat.crypto.Identity
import app.frad.chat.data.MediaFileStore
import app.frad.chat.profile.Gender
import app.frad.chat.profile.Profile
import app.frad.chat.safety.BlockList
import app.frad.chat.wideradius.CoarseLocation
import app.frad.chat.wideradius.Geohash
import app.frad.chat.wideradius.WideRangeChatController
import app.frad.chat.wideradius.WideRangeNode

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

    // Local BLE is owned by LocalBleService, not this ViewModel, so it can keep running in the
    // background when Profile.alwaysVisible is on - see that service's doc comment. Null only for
    // the brief window before the same-process bind completes; every read below falls back to a
    // harmless idle/no-op rather than assuming that window has already closed.
    private val _bleController = MutableStateFlow<ChatController?>(null)
    private val wideController: ChatController =
        WideRangeChatController(application, identity, profile, WideRangeNode(application))

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            _bleController.value = (service as LocalBleService.LocalBinder).controller
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            _bleController.value = null
        }
    }

    init {
        application.bindService(Intent(application, LocalBleService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unbindService(serviceConnection)
    }

    private val _mode = MutableStateFlow(ChatMode.LOCAL_BLE)
    val mode: StateFlow<ChatMode> = _mode.asStateFlow()
    val wideRangeAvailable: Boolean get() = WideRangeNode.isSupported

    private fun controllerFlow(mode: ChatMode): Flow<ChatController?> =
        if (mode == ChatMode.LOCAL_BLE) _bleController else flowOf(wideController)
    private val activeController: ChatController?
        get() = if (_mode.value == ChatMode.LOCAL_BLE) _bleController.value else wideController

    // flatMapLatest keeps this a single stable StateFlow reference across mode switches, so
    // RadarScreen's collectAsState() never needs to know two controllers exist underneath.
    val state: StateFlow<ChatUiState> = _mode.flatMapLatest { mode ->
        controllerFlow(mode).flatMapLatest { it?.state ?: flowOf(ChatUiState.Idle) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState.Idle)
    val transferStatus: StateFlow<String?> = _mode.flatMapLatest { mode ->
        controllerFlow(mode).flatMapLatest { it?.transferStatus ?: flowOf(null) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val fileTransferAvailable: Boolean get() = activeController?.fileTransferAvailable ?: false
    val myPeerId: String get() = identity.peerId

    private val _errorEvent = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = _errorEvent.asStateFlow()
    fun consumeErrorEvent() { _errorEvent.value = null }

    var myPseudonym: String
        get() = profile.pseudonym
        set(value) { profile.pseudonym = value }

    var gender: Gender
        get() = profile.gender
        set(value) { profile.gender = value }

    var age: Int?
        get() = profile.age
        set(value) { profile.age = value }

    var bio: String
        get() = profile.bio
        set(value) { profile.bio = value }

    var coarseGeohash: String?
        get() = profile.coarseGeohash
        set(value) { profile.coarseGeohash = value }

    var searchRadiusKm: Double
        get() = profile.searchRadiusKm
        set(value) { profile.searchRadiusKm = value }

    var bootstrapNodes: List<String>
        get() = profile.bootstrapNodes
        set(value) { profile.bootstrapNodes = value }

    /** See [Profile.alwaysVisible] / [LocalBleService]. Setting this also immediately starts or
     *  drops the persistent foreground service - safe to call any time this ViewModel's UI is
     *  reachable at all, since that already implies BLE permissions were granted. */
    var alwaysVisible: Boolean
        get() = profile.alwaysVisible
        set(value) {
            profile.alwaysVisible = value
            val app = getApplication<Application>()
            if (value) LocalBleService.startAlwaysVisible(app) else LocalBleService.stopAlwaysVisible(app)
        }

    /** Looks up a one-shot, coarse-only location fix (requires the caller to already hold
     *  ACCESS_COARSE_LOCATION - see RadarScreen's "Use my area" button), immediately reduces it
     *  to a [Geohash] cell at [Geohash.MAX_PRECISION], and discards the raw coordinate - not the
     *  (typically much coarser) precision actually shared for the wide-range layer, since a
     *  geohash prefix at any shorter length is exactly that string truncated (geohash's
     *  hierarchical property). Only that truncated, search-radius-matching prefix is what
     *  actually gets stored/shared; the finer hash returned here exists only so the caller can
     *  show an accurate area name for it (see AreaLookup) - reverse-geocoding the coarser stored
     *  hash instead previously showed a place tens of km off, easily a different city entirely
     *  once the search radius (and so the shared hash's cell) is wider than "Neighborhood".
     *  Returns null if the permission isn't granted or no location is available yet. */
    fun useCurrentAreaAsGeohash(): String? {
        val fineHash = CoarseLocation.lastKnownGeohash(getApplication(), Geohash.MAX_PRECISION) ?: return null
        val sharePrecision = Geohash.precisionForRadiusKm(profile.searchRadiusKm)
        profile.coarseGeohash = fineHash.take(sharePrecision)
        return fineHash
    }

    /** Called once by [app.frad.chat.MainActivity] as soon as it knows BLE permissions are
     *  granted (on launch if already granted, or right after the grant prompt) - starting the
     *  always-visible service any earlier would call into BLE APIs before they're allowed to be
     *  used. A no-op if [Profile.alwaysVisible] is off. */
    fun onPermissionsGranted() {
        if (profile.alwaysVisible) LocalBleService.startAlwaysVisible(getApplication())
    }

    /** Only switches while idle on the outgoing layer, mirroring [ChatController.setBrowsing]'s
     *  own guard against switching transport mid-chat. */
    fun setMode(newMode: ChatMode) {
        if (_mode.value == newMode) return
        activeController?.setBrowsing(false)
        _mode.value = newMode
    }

    fun setBrowsing(enabled: Boolean) { activeController?.setBrowsing(enabled) }
    fun requestRandomChat() { activeController?.requestRandomChat() }
    fun sendMessage(text: String) { activeController?.sendMessage(text) }
    fun endChat() { activeController?.endActiveConnection("you left") }
    fun blockActivePeer() { activeController?.blockActivePeer() }
    fun reportActivePeer(reason: String) { activeController?.reportActivePeer(reason) }

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
        activeController?.sendFile(bytes, fileName, mimeType)
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
