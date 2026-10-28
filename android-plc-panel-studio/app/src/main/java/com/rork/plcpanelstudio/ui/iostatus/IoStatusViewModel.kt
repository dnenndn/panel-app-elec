package com.rork.plcpanelstudio.ui.iostatus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rork.plcpanelstudio.data.BridgeResult
import com.rork.plcpanelstudio.data.ControlLock
import com.rork.plcpanelstudio.data.DeviceStatus
import com.rork.plcpanelstudio.data.IoSections
import com.rork.plcpanelstudio.data.MemoryCheck
import com.rork.plcpanelstudio.data.Panel
import com.rork.plcpanelstudio.data.PlcDevice
import com.rork.plcpanelstudio.data.TagValueStore
import com.rork.plcpanelstudio.data.WatchedTag
import com.rork.plcpanelstudio.data.WorkspaceRepository
import com.rork.plcpanelstudio.data.buildIoSections
import com.rork.plcpanelstudio.data.canForceOutput
import com.rork.plcpanelstudio.data.checkMemoryBit
import com.rork.plcpanelstudio.data.errorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val POLL_INTERVAL_MS = 700L

data class IoStatusUiState(
    val panels: List<Panel> = emptyList(),
    val selectedPanelId: String? = null,
    val panel: Panel? = null,
    val device: PlcDevice? = null,
    val sections: IoSections = IoSections.EMPTY,
    val status: DeviceStatus = DeviceStatus.UNKNOWN,
    /** True while the PLC is answering; false shows every value as "not known". */
    val live: Boolean = false,
    val lastUpdateMillis: Long? = null,
    val error: String? = null,
    /** Outputs being forced right now, address -> forced value. */
    val forced: Map<String, Int> = emptyMap(),
    /** True while forcing is allowed (the control password was entered). */
    val controlUnlocked: Boolean = false,
    val hasControlPassword: Boolean = false
)

/**
 * Backs the I/O view: every input, output and watched memory bit of one panel with its live
 * value, and the forcing of outputs.
 *
 * A forced output is written to the PLC and then written again whenever the PLC reports a
 * different value, so it is held while this screen is open. Leaving the screen releases every
 * force ([leaveScreen]). Forcing needs the control password; releasing never does.
 */
class IoStatusViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WorkspaceRepository.get(application)
    private val controlLock = ControlLock.get(application)

    private val _uiState = MutableStateFlow(IoStatusUiState())
    val uiState: StateFlow<IoStatusUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var selectedId: String? = null

    /** What is shown: what the PLC reports, plus our own writes until the PLC confirms them. */
    private var tags = TagValueStore()

    /** Outputs being forced (address -> value); insertion order is kept for the banner. */
    private val forced = linkedMapOf<String, Int>()

    private var live = false
    private var status = DeviceStatus.UNKNOWN
    private var lastUpdate: Long? = null
    private var error: String? = null

    /** Starts (or restarts) reading. Call when the screen appears. */
    fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            // Panels or devices edited elsewhere show up right away.
            launch { repository.state.collect { publish() } }
            while (isActive) {
                poll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops reading (forces are kept, but no longer held until [start] is called again). */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** The screen is really being left: release every force and lock the controls again. */
    fun leaveScreen() {
        forced.clear()
        controlLock.lock()
        publish()
    }

    override fun onCleared() {
        super.onCleared()
        stop()
        forced.clear()
        controlLock.lock()
    }

    fun selectPanel(id: String) {
        if (id == selectedId) return
        selectedId = id
        forced.clear()              // a force belongs to the panel's PLC, so it ends when the panel changes
        tags = TagValueStore()
        live = false
        status = DeviceStatus.UNKNOWN
        error = null
        publish()
    }

    // ------------------------------------------------------------------ memory bits

    /**
     * Adds memory bit [body] ("byte.bit", the M is added here) to the selected panel.
     * Returns a message to show when it is refused, or null when it was added.
     */
    fun addMemory(body: String, name: String): String? {
        val panel = currentPanel() ?: return "Choose a panel first."
        return when (val check = checkMemoryBit(body, panel)) {
            is MemoryCheck.Error -> check.message
            is MemoryCheck.Ok -> {
                repository.addWatchedMemory(panel.id, WatchedTag(check.address, name.trim()))
                publish()
                null
            }
        }
    }

    fun removeMemory(address: String) {
        val panel = currentPanel() ?: return
        repository.removeWatchedMemory(panel.id, address)
        publish()
    }

    // ------------------------------------------------------------------ forcing

    /**
     * Forces output [address] to [value] (0 or 1). Returns a message to show when it cannot be
     * done, or null when the force was started.
     */
    fun forceOutput(address: String, value: Int): String? {
        if (!controlLock.isUnlocked) {
            publish()
            return "The controls are locked. Enter the password first."
        }
        controlLock.touch()
        val panel = currentPanel() ?: return "Choose a panel first."
        if (!canForceOutput(panel, address)) return "Only outputs (Q) can be forced."
        val device = repository.device(panel.deviceId) ?: return "No PLC linked to this panel."

        val target = if (value > 0) 1 else 0
        forced[address] = target
        tags.beginWrite(address, target)   // shown at once
        publish()
        viewModelScope.launch {
            writeForced(device, address, target)
            publish()
        }
        return null
    }

    /** Stops forcing [address]. Nothing is written; the PLC's program takes the output back. */
    fun releaseForce(address: String) {
        forced.remove(address)
        publish()
    }

    fun releaseAllForces() {
        forced.clear()
        publish()
    }

    /** Writes a forced value again whenever the PLC reports something else. */
    private suspend fun holdForcedOutputs(device: PlcDevice) {
        for ((address, value) in forced.toMap()) {
            if (tags.values[address] == value) continue
            writeForced(device, address, value)
        }
    }

    private suspend fun writeForced(device: PlcDevice, address: String, value: Int) {
        tags.beginWrite(address, value)
        when (val result = repository.bridge.write(device, address, value)) {
            is BridgeResult.Success -> tags.endWrite(address, result.data)
            is BridgeResult.Failure -> {
                tags.endWrite(address, null)
                error = "Write failed: ${result.reason}"
            }
        }
    }

    // ------------------------------------------------------------------ control password

    /** Unlocks the controls. Returns an error message to show, or null when unlocked. */
    suspend fun unlockControl(password: String): String? {
        val result = withContext(Dispatchers.Default) { controlLock.unlock(password) }
        publish()
        return result.errorMessage()
    }

    /** Creates the first password (and unlocks). Returns an error message, or null. */
    suspend fun createControlPassword(password: String, confirm: String): String? {
        val problem = withContext(Dispatchers.Default) { controlLock.createPassword(password, confirm) }
        publish()
        return problem
    }

    /** Changes the password. Returns an error message, or null. */
    suspend fun changeControlPassword(current: String, new: String, confirm: String): String? {
        val problem = withContext(Dispatchers.Default) { controlLock.changePassword(current, new, confirm) }
        publish()
        return problem
    }

    fun lockControl() {
        controlLock.lock()
        publish()
    }

    // ------------------------------------------------------------------ reading

    private fun currentPanel(): Panel? {
        val workspace = repository.state.value
        return workspace.panels.firstOrNull { it.id == selectedId }
            ?: workspace.panels.firstOrNull { it.id == workspace.activePanelId }
            ?: workspace.panels.firstOrNull()
    }

    private suspend fun poll() {
        val panel = currentPanel()
        selectedId = panel?.id
        if (panel == null) {
            publish()
            return
        }
        val device = repository.device(panel.deviceId)
        val addresses = buildIoSections(panel, emptyMap()).addresses
        when {
            device == null -> {
                tags = TagValueStore(); live = false; status = DeviceStatus.UNKNOWN
                error = "No PLC linked to this panel"
            }
            addresses.isEmpty() -> {
                tags = TagValueStore(); live = false; error = null
            }
            else -> {
                val startedAt = tags.now()
                when (val result = repository.bridge.read(device, addresses)) {
                    is BridgeResult.Success -> {
                        // The user may have switched panel while the PLC was answering.
                        if (selectedId != panel.id) return
                        // A reply that predates one of our own writes must not put the old value back.
                        tags.applyPoll(result.data, startedAt)
                        live = true; status = DeviceStatus.CONNECTED
                        lastUpdate = System.currentTimeMillis(); error = null
                        holdForcedOutputs(device)
                    }
                    is BridgeResult.Failure -> {
                        if (selectedId != panel.id) return
                        tags = TagValueStore(); live = false; status = DeviceStatus.OFFLINE
                        error = result.reason
                    }
                }
            }
        }
        publish()
    }

    private fun publish() {
        val workspace = repository.state.value
        val panel = currentPanel()
        _uiState.value = IoStatusUiState(
            panels = workspace.panels,
            selectedPanelId = panel?.id,
            panel = panel,
            device = repository.device(panel?.deviceId),
            sections = if (panel == null) IoSections.EMPTY else buildIoSections(panel, tags.values, forced.toMap()),
            status = status,
            live = live,
            lastUpdateMillis = lastUpdate,
            error = error,
            forced = forced.toMap(),
            controlUnlocked = controlLock.isUnlocked,
            hasControlPassword = controlLock.hasPassword
        )
    }
}
