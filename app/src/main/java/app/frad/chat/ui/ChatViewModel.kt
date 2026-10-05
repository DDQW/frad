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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
import app.frad.chat.media.MediaSanitizer
import app.frad.chat.profile.Gender
import app.frad.chat.profile.Profile
import app.frad.chat.safety.BlockEntry
import app.frad.chat.safety.BlockList
import app.frad.chat.wideradius.CoarseLocation
import app.frad.chat.wideradius.Geohash
import app.frad.chat.wideradius.NodeLinks
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
    private val reportEvidence = app.frad.chat.safety.ReportEvidence(application)
    private val reportFlow = app.frad.chat.safety.ReportFlow(application, blockList, reportEvidence)

    // Local BLE is owned by LocalBleService, not this ViewModel, so it can keep running in the
    // background when Profile.alwaysVisible is on - see that service's doc comment. Null only for
    // the brief window before the same-process bind completes; every read below falls back to a
    // harmless idle/no-op rather than assuming that window has already closed.
    private val _bleController = MutableStateFlow<ChatController?>(null)
    private val wideController: ChatController =
        WideRangeChatController(application, identity, profile, WideRangeNode(application))

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            _bleController.value = (service as LocalBleService.LocalBinder).controller.also { it.setUiVisible(uiVisible) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            _bleController.value = null
        }
    }

    init {
        mediaFileStore.purgeExcept(contactStore.all().map { it.peerId }.toSet())
        viewModelScope.launch(Dispatchers.IO) { historyStore.pruneExpired() }
        application.bindService(Intent(application, LocalBleService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onCleared() {
        super.onCleared()
        // Wide-range lives with this ViewModel (unlike local BLE, which LocalBleService keeps
        // running): left on, its libp2p host would keep advertising and accepting chats - and
        // sending our profile to strangers - with no UI to show them, and the next ViewModel
        // would start a second host next to it.
        wideController.setBrowsing(false)
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
    val transferProgress: StateFlow<Float?> = _mode.flatMapLatest { mode ->
        controllerFlow(mode).flatMapLatest { it?.transferProgress ?: flowOf(null) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    // Declared before the init block below, which reads it as soon as it starts collecting.
    private val _visibleUntilMillis = MutableStateFlow(profile.visibleUntilMillis)

    /** When being visible ends by itself (0 = when switched off) - see [Profile.visibleUntilMillis]. */
    val visibleUntilMillis: StateFlow<Long> = _visibleUntilMillis.asStateFlow()

    /** The chat that just ended, so the person can still be saved, blocked or reported once
     *  they're gone - they may have left precisely because the chat went badly. Cleared when the
     *  user acts on it, dismisses it, or the next chat opens. */
    data class EndedChat(
        val peerId: String,
        val deviceFingerprint: String,
        val pseudonym: String,
        val messages: List<ChatMessage>,
    )

    private val _endedChat = MutableStateFlow<EndedChat?>(null)
    val endedChat: StateFlow<EndedChat?> = _endedChat.asStateFlow()

    init {
        viewModelScope.launch {
            var open: ChatUiState.Chatting? = null
            state.collect { current ->
                // A controller ends a time-limited visibility by itself (Profile.visibleUntilMillis).
                _visibleUntilMillis.value = profile.visibleUntilMillis
                if (current is ChatUiState.Chatting) {
                    open = current
                    _endedChat.value = null
                } else {
                    val ended = open ?: return@collect
                    open = null
                    // Blocked or reported from inside the chat: nothing left to do with them.
                    if (!blockList.isBlocked(ended.remotePeerId)) {
                        _endedChat.value = EndedChat(ended.remotePeerId, ended.remoteDeviceFingerprint, ended.remotePseudonym, ended.messages)
                    }
                }
            }
        }
    }

    fun saveEndedChat() {
        val ended = _endedChat.value ?: return
        _endedChat.value = null
        contactStore.save(ended.peerId, ended.pseudonym)
        // The texts are still in memory; the files went with the chat (see MediaFileStore).
        historyStore.backfillIfEmpty(ended.peerId, ended.messages.filter { it.kind == app.frad.chat.chat.MessageKind.TEXT })
    }

    /** Blocks the person from [endedChat]; [reason] set makes it a report (see [ReportFlow]),
     *  which also keeps the chat's transcript. */
    fun blockEndedChat(reason: String? = null) {
        val ended = _endedChat.value ?: return
        _endedChat.value = null
        if (reason != null) {
            reportFlow.report(ended.peerId, ended.deviceFingerprint, ended.pseudonym, reason, ended.messages)
        } else {
            blockList.block(ended.peerId, ended.deviceFingerprint, ended.pseudonym)
        }
    }

    fun dismissEndedChat() { _endedChat.value = null }

    /** One-off messages from whichever layer is active - see [ChatController.notices]. */
    val notices: Flow<String> = _mode.flatMapLatest { mode ->
        controllerFlow(mode).flatMapLatest { it?.notices ?: emptyFlow() }
    }
    val fileTransferAvailable: Boolean get() = activeController?.fileTransferAvailable ?: false
    val myPeerId: String get() = identity.peerId

    private val _errorEvent = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = _errorEvent.asStateFlow()
    fun consumeErrorEvent() { _errorEvent.value = null }

    var myPseudonym: String
        get() = profile.pseudonym
        set(value) { profile.pseudonym = value }

    var gender: Gender?
        get() = profile.gender
        set(value) { profile.gender = value }

    var age: Int?
        get() = profile.age
        set(value) { profile.age = value }

    var shareAge: Boolean
        get() = profile.shareAge
        set(value) { profile.shareAge = value }

    var bio: String
        get() = profile.bio
        set(value) { profile.bio = value }

    private val _onboarded = MutableStateFlow(profile.onboarded)

    /** False until the first-run setup is done - see [Profile.onboarded]. */
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    fun completeOnboarding(pseudonym: String, gender: Gender, age: Int, shareAge: Boolean) {
        profile.completeOnboarding(pseudonym, gender, age, shareAge)
        _onboarded.value = true
        // Permissions may well have been granted before (e.g. updating from a build without
        // onboarding), in which case nothing else would start the always-visible service now.
        if (bluetoothPermissionsGranted()) onPermissionsGranted()
    }

    private fun bluetoothPermissionsGranted(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
        val app = getApplication<Application>()
        return listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        ).all { app.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    var coarseGeohash: String?
        get() = profile.coarseGeohash
        set(value) { profile.coarseGeohash = value }

    var searchRadiusKm: Double
        get() = profile.searchRadiusKm
        set(value) { profile.searchRadiusKm = value }

    private val _bootstrapNodes = MutableStateFlow(profile.bootstrapNodes)

    /** The saved bootstrap/relay nodes, observable so the Profile tab picks up a confirmed
     *  [offerNodeLink] import while it's open. */
    val savedBootstrapNodes: StateFlow<List<String>> = _bootstrapNodes.asStateFlow()

    var bootstrapNodes: List<String>
        get() = profile.bootstrapNodes
        set(value) {
            profile.bootstrapNodes = value
            _bootstrapNodes.value = profile.bootstrapNodes
        }

    /** What wide-range still needs before it can find anyone, as a hint for the user - null
     *  once an area and at least one bootstrap/relay node are set. */
    fun wideRangeSetupMissing(): String? = when {
        profile.coarseGeohash == null && profile.bootstrapNodes.isEmpty() ->
            "Set your area and at least one server (Profile → Wide-range) first."
        profile.coarseGeohash == null -> "Set your area (Profile → Wide-range) first."
        profile.bootstrapNodes.isEmpty() -> "Add at least one server (Profile → Wide-range) first - or open a frad://node link someone shared."
        else -> null
    }

    var photoOnRequest: Boolean
        get() = profile.photoOnRequest
        set(value) { profile.photoOnRequest = value }

    var interests: Set<app.frad.chat.profile.Interest>
        get() = profile.interests
        set(value) { profile.interests = value }

    var wideRangeRelayOnly: Boolean
        get() = profile.wideRangeRelayOnly
        set(value) { profile.wideRangeRelayOnly = value }

    var appLock: Boolean
        get() = profile.appLock
        set(value) { profile.appLock = value }

    /** See [Profile.historyRetentionDays]; applied to what's already stored right away. */
    var historyRetentionDays: Int
        get() = profile.historyRetentionDays
        set(value) {
            profile.historyRetentionDays = value
            viewModelScope.launch(Dispatchers.IO) { historyStore.pruneExpired() }
        }

    private val _pendingNodeImport = MutableStateFlow<List<String>>(emptyList())

    /** Node addresses from a `frad://node` link waiting for the user to confirm - see [NodeLinks]. */
    val pendingNodeImport: StateFlow<List<String>> = _pendingNodeImport.asStateFlow()

    fun offerNodeLink(link: String) {
        val addresses = NodeLinks.parse(link).filterNot { it in profile.bootstrapNodes }
        if (addresses.isNotEmpty()) _pendingNodeImport.value = addresses
    }

    fun confirmNodeImport() {
        bootstrapNodes = (profile.bootstrapNodes + _pendingNodeImport.value).distinct()
        _pendingNodeImport.value = emptyList()
    }

    fun dismissNodeImport() { _pendingNodeImport.value = emptyList() }

    /** A `frad://node` link for the configured nodes, to share - see [NodeLinks]. */
    fun nodeShareLink(): String? = profile.bootstrapNodes.filter(NodeLinks::looksLikeNodeAddress).takeIf { it.isNotEmpty() }?.let(NodeLinks::build)

    /** Panic button: erases everything FRAD has stored - identity, profile, contacts, history,
     *  files, block list, keys in the Keystore - and closes the app. Irreversible by design. */
    fun wipeEverything() {
        val app = getApplication<Application>()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).clearApplicationUserData()
    }

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
    suspend fun useCurrentAreaAsGeohash(): String? {
        val fineHash = CoarseLocation.currentGeohash(getApplication(), Geohash.MAX_PRECISION) ?: return null
        val sharePrecision = Geohash.precisionForRadiusKm(profile.searchRadiusKm)
        profile.coarseGeohash = fineHash.take(sharePrecision)
        return fineHash
    }

    /** Called once by [app.frad.chat.MainActivity] as soon as it knows BLE permissions are
     *  granted (on launch if already granted, or right after the grant prompt) - starting the
     *  always-visible service any earlier would call into BLE APIs before they're allowed to be
     *  used. A no-op if [Profile.alwaysVisible] is off. */
    fun onPermissionsGranted() {
        // Nothing is advertised (or exchanged with anyone) before the first-run setup is done.
        if (profile.onboarded && profile.alwaysVisible) LocalBleService.startAlwaysVisible(getApplication())
    }

    /** Only switches while idle on the outgoing layer, mirroring [ChatController.setBrowsing]'s
     *  own guard against switching transport mid-chat. */
    fun setMode(newMode: ChatMode) {
        if (_mode.value == newMode) return
        activeController?.setBrowsing(false)
        _mode.value = newMode
    }

    fun setBrowsing(enabled: Boolean) {
        if (!enabled) setVisibleFor(null) // a limit belongs to one stretch of being visible
        activeController?.setBrowsing(enabled)
    }
    fun requestRandomChat() { activeController?.requestRandomChat() }
    fun sendMessage(text: String) { activeController?.sendMessage(text) }
    fun notifyTyping() { activeController?.notifyTyping() }
    fun endChat() { activeController?.endActiveConnection("you left") }
    fun acknowledgeEnded() { activeController?.acknowledgeEnded() }
    /** Whether FRAD is on screen - see [ChatController.setUiVisible]. */
    private var uiVisible = false

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        _bleController.value?.setUiVisible(visible)
        wideController.setUiVisible(visible)
    }

    /** Limits the current visibility to [minutes] from now, or lifts the limit (null). */
    fun setVisibleFor(minutes: Int?) {
        val until = minutes?.let { System.currentTimeMillis() + it * 60_000L } ?: 0L
        profile.visibleUntilMillis = until
        _visibleUntilMillis.value = until
    }

    fun answerIncomingFile(accept: Boolean) { activeController?.answerIncomingFile(accept) }
    fun cancelTransfer() { activeController?.cancelTransfer() }
    fun requestPhotoSwap() { activeController?.requestPhotoSwap() }
    fun answerPhotoSwap(accept: Boolean) { activeController?.answerPhotoSwap(accept) }
    fun blockActivePeer() { activeController?.blockActivePeer() }
    fun reportActivePeer(reason: String) { activeController?.reportActivePeer(reason) }

    /** Reads [uri], strips what photos/videos reveal beyond their content (see [MediaSanitizer]),
     *  and hands the result to the active controller - off the main thread. [afterRead] runs once
     *  the source has been read, so a temporary capture file can be deleted then. Surfaces
     *  [errorEvent] instead of sending if the file can't be read or cleaned, or is over the cap. */
    fun sendFile(uri: Uri, afterRead: () -> Unit = {}) {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val fileName = displayNameOf(resolver, uri) ?: "file"
        val isMedia = mimeType.startsWith("image/") || mimeType.startsWith("video/")
        val tooLarge = "That file is too large to send (max ${MAX_TRANSFER_FILE_BYTES / (1024 * 1024)} MB)."
        // Media may shrink a lot once cleaned; anything else is sent as is, so check before reading it.
        if (!isMedia && (sizeOf(resolver, uri) ?: 0L) > MAX_TRANSFER_FILE_BYTES) {
            afterRead()
            _errorEvent.value = tooLarge
            return
        }
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                runCatching { MediaSanitizer.prepare(app, uri, mimeType, fileName) }
            }
            afterRead()
            val file = prepared.getOrElse {
                _errorEvent.value = if (isMedia) {
                    "Couldn't remove the location and other hidden data from that file, so it wasn't sent."
                } else {
                    "Couldn't read that file."
                }
                return@launch
            }
            if (file.bytes.size > MAX_TRANSFER_FILE_BYTES) {
                _errorEvent.value = tooLarge
                return@launch
            }
            activeController?.sendFile(file.bytes, file.fileName, file.mimeType)
        }
    }

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
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

    fun blockedEntries(): List<BlockEntry> = blockList.entries()
    /** Unblocking also deletes the report kept with the block, if any. */
    fun unblock(entry: BlockEntry) {
        blockList.unblock(entry.key)
        reportEvidence.delete(entry.key)
    }

    /** The transcript kept with a report (see [app.frad.chat.safety.ReportEvidence]). */
    fun reportTranscript(entry: BlockEntry): String? = reportEvidence.transcriptFor(entry.key)
}
