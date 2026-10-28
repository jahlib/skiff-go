// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.device

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.skiff.go.midi.MidiConnection
import com.skiff.go.midi.MidiDeviceDesc
import com.skiff.go.midi.MidiEngine
import com.skiff.go.proto.Cmd
import com.skiff.go.proto.Desc
import com.skiff.go.proto.Dump
import com.skiff.go.proto.Frame
import com.skiff.go.proto.Info
import com.skiff.go.proto.Library
import com.skiff.go.proto.LibraryContext
import com.skiff.go.proto.LibraryRead
import com.skiff.go.proto.Parse
import com.skiff.go.proto.Step
import com.skiff.go.proto.Patch
import com.skiff.go.proto.Requests
import com.skiff.go.proto.Tracks
import com.skiff.go.proto.unframe
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * The link to a Skiff FM-1, as the Skiff Editor (web/editor.html) drives it: every request through one [Wire] (one in
 * flight, replies matched to their request, knob SETs coalesced per control), WATCH 3 for the device's pushes, and
 * TRANSPORT as the keep-alive (the device pushes nothing about its transport; WATCH ends 3 s after the last request).
 * DUMP and the strips are read again every 5 s as a safety net for what pushes miss (other tracks' sends, a lost push).
 */
class DeviceController(private val appContext: Context) {
    private val engine = MidiEngine(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<MidiDeviceDesc>>(emptyList())
    val devices: StateFlow<List<MidiDeviceDesc>> = _devices.asStateFlow()

    @Volatile private var conn: MidiConnection? = null
    @Volatile private var link: Wire? = null
    private var jobs = ArrayList<Job>()
    /** Multi-request operations (a load, a reload, a patch): one at a time, and the 5 s refresh waits for them. */
    private val busy = Mutex()
    private val touchedAt = ConcurrentHashMap<String, Long>()
    private val descCache = HashMap<Int, List<Desc?>>()
    @Volatile private var reloadWanted = false
    private var reloadJob: Job? = null
    @Volatile private var selfReloadUntil = 0L
    @Volatile private var selfReloads = 0

    val midiAvailable: Boolean get() = engine.available

    /** The computed playhead (Skiff's protocol has none) and whether the Sequencer is on screen (a faster poll). */
    val playhead = Playhead()
    @Volatile private var sequencerShown = false
    fun setSequencerShown(on: Boolean) { sequencerShown = on }

    fun refreshDevices() { _devices.value = engine.devices() }

    // ---------------------------------------------------------------- connect ---

    /** The port names Skiff Editor 2.0.1 tries (Windows and macOS keep the name of the first firmware they saw). */
    private val unitPort = Regex("fm-1|smk|elite|mke-p37|starry|felucca|skiff|composite|sinco|usb-midi", RegexOption.IGNORE_CASE)

    /** Connects to the first likely port that answers INFO (editor.html connect, 2.0.1), else the only port there is. */
    fun connectPreferred() {
        refreshDevices()
        val all = _devices.value
        val likely = all.filter { unitPort.containsMatchIn(it.name) && it.hasInput && it.hasOutput }
        val ids = (likely.ifEmpty { all.filter { it.hasInput && it.hasOutput } }).map { it.id }
        scope.launch { openFirst(ids) }
    }

    fun connect(deviceId: Int) { scope.launch { openFirst(listOf(deviceId)) } }

    private suspend fun openFirst(ids: List<Int>) {
        closeLink()
        if (ids.isEmpty()) { _state.value = DeviceState(status = "No MIDI device"); return }
        _state.value = DeviceState(link = Link.CONNECTING, status = "Connecting…")
        engine.requestUsbPermission(appContext)
        for (id in ids) {
            val l = openLink(id) ?: continue
            val info = try {
                Parse.info(l.request(Requests.info(), timeout = 500, retries = 2))   // the first frame after opening can be lost
            } catch (e: Exception) {
                null
            }
            if (info == null) { closeLink(); continue }
            val name = engine.devices().firstOrNull { it.id == id }?.name ?: "device"
            _state.update { it.copy(deviceName = name, link = Link.LOADING, status = "Loading…", info = info) }
            try {
                busy.withLock { load(l, info) }
            } catch (e: Exception) {
                Log.w(TAG, "load failed", e)
                closeLink()
                _state.update { it.copy(link = Link.DISCONNECTED, status = "The device stopped answering", error = e.message, progress = null) }
            }
            return
        }
        _state.update { it.copy(link = Link.DISCONNECTED, status = "No FM-1 answered") }
    }

    private suspend fun openLink(id: Int): Wire? {
        var l: Wire? = null
        var c: MidiConnection? = null
        c = engine.open(id, onReceive = { bytes -> unframe(bytes)?.let { f -> l?.receive(f) } },
            onLost = { scope.launch { if (conn != null && conn === c) { closeLink(); _state.value = DeviceState(status = "Device removed") } } })
            ?: return null
        val opened = c
        conn = opened
        return Wire({ opened.send(it) }, scope, ::onPush).also { l = it; link = it }
    }

    fun disconnect() {
        scope.launch {
            runCatching { link?.request(Requests.watch(0), timeout = 200, retries = 0) }
            closeLink()
            _state.value = DeviceState()
        }
    }

    private fun closeLink() {
        jobs.forEach { it.cancel() }; jobs.clear()
        reloadJob?.cancel(); reloadJob = null
        link?.close(); link = null
        conn?.close(); conn = null
        touchedAt.clear()
        descCache.clear()
    }

    // ---------------------------------------------------------------- load ---

    private suspend fun load(l: Wire, info: Info) {
        if (!info.isSkiff) {
            closeLink()
            _state.update { it.copy(link = Link.WRONG_FIRMWARE, status = wrongFirmware(info)) }
            return
        }
        val total = info.pcount + info.gcount + info.nengines
        var done = 0
        fun step() { done++; if (done % 6 == 0 || done == total) _state.update { it.copy(progress = done to total) } }
        val pdesc = List(info.pcount) { i -> descOrNull(l, 0, i).also { step() } }
        val gdesc = List(info.gcount) { i -> descOrNull(l, 1, i).also { step() } }
        val names = ArrayList<List<String>>()
        val titles = ArrayList<List<String>>()
        for (e in 0 until info.nengines) {
            val n = runCatching { Parse.names(l.request(Requests.names(e))) }.getOrNull()
            names += n?.names ?: emptyList(); titles += n?.titles ?: emptyList()
            step()
        }
        val dump = Parse.dump(l.request(Requests.dump()), info)
        descCache[dump.engine] = pdesc.drop(info.pe0)
        _state.update { it.copy(pdesc = pdesc, gdesc = gdesc, names = names, titles = titles, dump = dump) }
        val (tracks, strips) = readStrips(l, info, null)
        val watch = Parse.watch(l.request(Requests.watch(3)))
        val transport = Parse.transport(l.request(Requests.transport()))
        _state.update {
            it.copy(link = Link.READY, status = "Connected", tracks = tracks, strips = strips, watch = watch,
                transport = transport, progress = null, error = null)
        }
        jobs += scope.launch { keepAlive(l) }
        jobs += scope.launch { refresher(l) }
    }

    private suspend fun descOrNull(l: Wire, scope: Int, id: Int): Desc? = try {
        Parse.desc(l.request(Requests.desc(scope, id)))
    } catch (e: IOException) {
        if (l.closed) throw e
        null
    }

    /** Why this firmware is not for Skiff Go. Nothing else is sent to it: Skiff's commands mean other things there. */
    private fun wrongFirmware(info: Info): String = when {
        info.chainRows != 16 && info.chainRows > 0 -> "This FM-1 runs SLOOP (protocol ${info.chainRows}): use Sloop Go for it."
        info.chainRows == 16 -> "This FM-1 runs Felucca or an older Skiff (${info.version}): install Skiff 2.0 or later."
        else -> "Unknown firmware (${info.version})."
    }

    /** TRACK, and each track's PAN and REVERB send by TRACK_DUMP ([prev]: keep those, no TRACK_DUMP). */
    private suspend fun readStrips(l: Wire, info: Info, prev: List<Strip>?): Pair<Tracks, List<Strip>> {
        val t = Parse.tracks(l.request(Requests.track()))
        val strips = t.tracks.mapIndexed { i, e ->
            val p = prev?.getOrNull(i)
            if (p != null) Strip(e.level, e.mute, p.pan, p.rev)
            else {
                val d = runCatching { Parse.trackDump(l.request(Requests.trackDump(i)), info) }.getOrNull()
                Strip(e.level, e.mute, d?.p?.getOrNull(Pid.PAN), d?.p?.getOrNull(Pid.REV))
            }
        }
        val sel = t.selected
        val dump = _state.value.dump
        val merged = strips.mapIndexed { i, s ->     // the selected track: its pan / send as the Sound page has them
            if (i == sel && dump != null) s.copy(pan = dump.p.getOrNull(Pid.PAN) ?: s.pan, rev = dump.p.getOrNull(Pid.REV) ?: s.rev) else s
        }
        return t to merged
    }

    /** After a sound load, a track change or a RELOAD: DUMP, the engine's descriptions, the strips. */
    private suspend fun afterSoundChange(l: Wire) {
        val info = _state.value.info ?: return
        val t = Parse.tracks(l.request(Requests.track()))
        _state.update { it.copy(tracks = t) }
        val dump = Parse.dump(l.request(Requests.dump()), info)
        val pdesc = rereadEngineDesc(l, info, dump)
        _state.update { it.copy(dump = dump, pdesc = pdesc) }
        val (tracks, strips) = readStrips(l, info, _state.value.strips.takeIf { it.isNotEmpty() })
        _state.update { it.copy(tracks = tracks, strips = strips) }
        val trackChanged = _state.value.stepsTrack != tracks.selected
        if (trackChanged) {
            _state.update { it.copy(steps = emptyList(), stepsTrack = -1, stepsRead = 0, canUndo = false, canRedo = false) }
            undo.clear(); redo.clear()
        }
        if ((trackChanged || stepsStale) && sequencerShown) readSteps(l)   // (not shown: read when it is)
    }

    /** The engine's eight parameters change meaning with it: cached per engine, but SAMPLE's and SLICE's (the user's samples). */
    private suspend fun rereadEngineDesc(l: Wire, info: Info, dump: Dump): List<Desc?> {
        val cur = _state.value.pdesc
        val name = info.engines.getOrNull(dump.engine) ?: ""
        val keep = name != "SAMPLE" && name != "SLICE"
        val eng = if (keep) descCache[dump.engine] else null
        val fresh = eng ?: List(8) { k -> if (info.pe0 + k < info.pcount) descOrNull(l, 0, info.pe0 + k) else null }
        if (keep) descCache[dump.engine] = fresh
        return cur.take(info.pe0) + fresh.take(info.pcount - info.pe0)
    }

    private fun scheduleReload() {
        reloadWanted = true
        if (reloadJob?.isActive == true) return
        val l = link ?: return
        reloadJob = scope.launch {
            while (reloadWanted && isActive) {
                reloadWanted = false
                runCatching { busy.withLock { afterSoundChange(l) } }.onFailure { Log.w(TAG, "reload", it) }
            }
        }
    }

    // ---------------------------------------------------------------- keep-alive, refresh ---

    private suspend fun keepAlive(l: Wire) {
        while (scope.isActive && !l.closed) {
            delay(if (sequencerShown || _state.value.transport?.running == true) 250 else 700)
            if (_state.value.link != Link.READY) continue
            // Silent for nearly 3 s (the app paused, a long flash write): the device may have ended WATCH. Skiff keeps what
            // it has not pushed yet on a WATCH while watching (INFO 53 01 bit 0); what was missed is read again anyway.
            if (l.lastSent > 0 && SystemClock.elapsedRealtime() - l.lastSent > 2500) {
                runCatching { _state.update { s -> s.copy(watch = Parse.watch(l.request(Requests.watch(3), retries = 0))) } }
                scheduleReload()
            }
            runCatching {
                val t0 = SystemClock.elapsedRealtime()
                val t = Parse.transport(l.request(Requests.transport(), timeout = 300, retries = 0, key = "ping"))
                val rtt = (SystemClock.elapsedRealtime() - t0).toInt()
                _state.update { it.copy(transport = t, rttMs = rtt) }
                playhead.onTransport(t, _state.value.tempo, SystemClock.elapsedRealtime())
                if (t.selected != _state.value.selectedTrack) scheduleReload()   // selected on the device, push missed
            }
        }
    }

    /** Every 5 s, when nothing else is going on: DUMP (values the user is not touching) and the strips. */
    private suspend fun refresher(l: Wire) {
        while (scope.isActive && !l.closed) {
            delay(5000)
            val s = _state.value
            val info = s.info ?: continue
            if (s.link != Link.READY || !l.idle || recentlyTouched(1500) || busy.isLocked) continue
            runCatching {
                busy.withLock {
                    val dump = Parse.dump(l.request(Requests.dump()), info)
                    val old = _state.value.dump
                    if (old != null && (dump.engine != old.engine || dump.preset != old.preset)) { afterSoundChange(l); return@withLock }
                    if (old != null) {
                        for (i in dump.p.indices) if (touched("s:0:$i")) dump.p[i] = old.p.getOrElse(i) { dump.p[i] }
                        for (i in dump.g.indices) if (touched("s:1:$i")) dump.g[i] = old.g.getOrElse(i) { dump.g[i] }
                    }
                    _state.update { it.copy(dump = dump) }
                    val (tracks, strips) = readStrips(l, info, _state.value.strips.takeIf { it.isNotEmpty() })
                    _state.update { st ->
                        st.copy(tracks = tracks, strips = strips.mapIndexed { i, x ->
                            val o = st.strips.getOrNull(i)
                            if (o != null && touched("mix:$i")) x.copy(level = o.level, mute = o.mute) else x
                        })
                    }
                }
            }
        }
    }

    private fun touched(key: String): Boolean =
        SystemClock.elapsedRealtime() - (touchedAt[key] ?: 0L) < 600 || link?.hasQueued(key) == true

    private fun recentlyTouched(ms: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        return touchedAt.values.any { now - it < ms }
    }

    // ---------------------------------------------------------------- pushes ---

    private fun onPush(f: Frame) {
        val s = _state.value
        if (s.link != Link.READY || s.dump == null) return
        runCatching {
            when (f.cmd) {
                Cmd.CHANGED -> {
                    val pv = Parse.param(f.args)
                    if (touched("s:${pv.scope}:${pv.id}")) return   // the user's value wins; their SET follows
                    _state.update { st -> st.withValue(pv.scope, pv.id, pv.value) }
                }
                Cmd.RELOAD -> {
                    if (selfReloads > 0 && SystemClock.elapsedRealtime() < selfReloadUntil) { selfReloads--; return }
                    stepsStale = true
                    scheduleReload()
                }
                Cmd.STEP_CHANGED -> {
                    val (index, track) = Parse.stepChanged(f.args)
                    if (track == s.selectedTrack && s.stepsReady && index < Step.NSTEP && !touched("st:$track:$index")) readStep(track, index)
                }
                Cmd.TRACK_CHANGED -> {
                    val pv = Parse.trackParam(f.args)
                    if (pv.scope == s.selectedTrack) return           // (scope holds the track here)
                    _state.update { st -> st.withStrip(pv.scope, pv.id, pv.value, touched("mix:${pv.scope}"), touched("pan:${pv.scope}")) }
                }
            }
        }.onFailure { Log.w(TAG, "bad push ${f.cmd}", it) }
    }

    /** A value of the selected track's sound (or a global) changed: the dump and the selected strip follow. */
    private fun DeviceState.withValue(scope: Int, id: Int, v: Int): DeviceState {
        val d = dump ?: return this
        val arr = if (scope == 0) d.p.copyOf() else d.g.copyOf()
        if (id !in arr.indices) return this
        arr[id] = v
        val nd = if (scope == 0) d.copy(p = arr) else d.copy(g = arr)
        val sel = selectedTrack
        val st = if (scope == 0) strips.mapIndexed { i, x ->
            if (i != sel) x else when (id) {
                Pid.LEVEL -> x.copy(level = v); Pid.MUTE -> x.copy(mute = v != 0)
                Pid.PAN -> x.copy(pan = v); Pid.REV -> x.copy(rev = v); else -> x
            }
        } else strips
        return copy(dump = nd, strips = st)
    }

    private fun DeviceState.withStrip(track: Int, id: Int, v: Int, mixTouched: Boolean, panTouched: Boolean): DeviceState =
        copy(strips = strips.mapIndexed { i, x ->
            if (i != track) x else when {
                id == Pid.LEVEL && !mixTouched -> x.copy(level = v)
                id == Pid.MUTE && !mixTouched -> x.copy(mute = v != 0)
                id == Pid.PAN && !panTouched -> x.copy(pan = v)
                else -> x
            }
        })

    /** Our own PRESET / SET of G_ENGSEL: firmware without INFO 53 01 bit 1 answers it with a RELOAD to skip. */
    private fun selfLoad() {
        if ((_state.value.info?.liveSyncCaps ?: 0) and 2 != 0) return
        val now = SystemClock.elapsedRealtime()
        selfReloads = if (now < selfReloadUntil) selfReloads + 1 else 1
        selfReloadUntil = now + 1500
    }

    // ---------------------------------------------------------------- sound ---

    /**
     * A knob of the Sound page: shown at once, sent as fast as the device answers (one SET per control in flight, one
     * queued with the latest value), the device's clamped value taken back when nothing newer is queued.
     */
    fun setParam(scope: Int, id: Int, value: Int) {
        val s = _state.value
        val l = link ?: return
        if (s.link != Link.READY) return
        val d = s.desc(scope, id) ?: return
        val v = value.coerceIn(d.min, d.max)
        val key = "s:$scope:$id"
        touchedAt[key] = SystemClock.elapsedRealtime()
        _state.update { it.withValue(scope, id, v) }
        this.scope.launch {
            runCatching { Parse.param(l.request(Requests.set(scope, id, v), key = key)) }
                .onSuccess { r -> if (!l.hasQueued(key) && r.value != v) _state.update { it.withValue(scope, id, r.value) } }
        }
    }

    /** The global id of G_ENGSEL (DESC label "ENG"; 20 by default). */
    private fun gEng(): Int = _state.value.gdesc.indexOfFirst { it?.label == "ENGINE" || it?.label == "ENG" }.takeIf { it >= 0 } ?: 20

    private fun refused(engine: Int): Boolean {
        val s = _state.value
        val info = s.info ?: return true
        if (info.engineAllowed(s.selectedTrack, engine)) return false
        _state.update { it.copy(notice = "${info.engines.getOrNull(engine) ?: engine}: not on T${s.selectedTrack + 1} (a light track)") }
        return true
    }

    /** A factory preset onto the selected track (its steps and own parameters stay). */
    fun choosePreset(engine: Int, preset: Int) = op {
        if (refused(engine)) return@op
        selfLoad()
        Parse.preset(it.request(Requests.preset(engine, preset), timeout = 1000))
        afterSoundChange(it)
        _state.update { s -> s.copy(notice = "Loaded ${s.presetName ?: "preset ${preset + 1}"}") }
    }

    /** INIT: the engine's defaults, then its first preset, as G_ENGSEL does on the device. */
    fun initSound() = op {
        val e = _state.value.dump?.engine ?: return@op
        selfLoad()
        it.request(Requests.set(1, gEng(), e), timeout = 1000)
        afterSoundChange(it)
    }

    fun selectTrack(track: Int) = op {
        val t = Parse.tracks(it.request(Requests.track(track)))
        _state.update { s -> s.copy(tracks = t) }
        afterSoundChange(it)                              // (TRACK pushes no RELOAD: read it here)
    }

    /** TRANSPORT op: [Requests.Op] PLAY, STOP, RESTART, REC. */
    fun transport(op: Int) {
        val l = link ?: return
        scope.launch {
            runCatching { Parse.transport(l.request(Requests.transport(op))) }
                .onSuccess { t ->
                    _state.update { it.copy(transport = t) }
                    playhead.onTransport(t, _state.value.tempo, SystemClock.elapsedRealtime(), startedHere = true)
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** One multi-request operation: a reload or the refresh waits for it. */
    private fun op(block: suspend (Wire) -> Unit) {
        val l = link ?: return
        if (_state.value.link != Link.READY) return
        scope.launch {
            runCatching { busy.withLock { block(l) } }.onFailure { e ->
                if (!l.closed) _state.update { it.copy(notice = "Error: ${e.message}") }
            }
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }
    fun say(message: String) = _state.update { it.copy(notice = message) }

    // ---------------------------------------------------------------- mixer ---

    /** Level and mute of any track: TRACK_MIX, the latest of a drag winning. */
    fun setMix(track: Int, level: Int, mute: Boolean) {
        val l = link ?: return
        val key = "mix:$track"
        touchedAt[key] = SystemClock.elapsedRealtime()
        val lv = level.coerceIn(0, 127)
        _state.update { s -> s.withMix(track, lv, mute) }
        scope.launch {
            runCatching { Parse.trackMix(l.request(Requests.trackMix(track, lv, mute), key = key)) }
                .onSuccess { r -> if (!l.hasQueued(key)) _state.update { s -> s.withMix(track, r.level, r.mute) } }
        }
    }

    private fun DeviceState.withMix(track: Int, level: Int, mute: Boolean): DeviceState {
        val st = copy(strips = strips.mapIndexed { i, x -> if (i == track) x.copy(level = level, mute = mute) else x },
            tracks = tracks?.let { t -> t.copy(tracks = t.tracks.mapIndexed { i, e -> if (i == track) e.copy(level = level, mute = mute) else e }) })
        if (track != selectedTrack) return st
        val d = st.dump ?: return st
        val p = d.p.copyOf()
        if (Pid.LEVEL in p.indices) p[Pid.LEVEL] = level
        if (Pid.MUTE in p.indices) p[Pid.MUTE] = if (mute) 1 else 0
        return st.copy(dump = d.copy(p = p))
    }

    /** PAN or the REVERB send of a track: the selected one's as any Sound knob, another's by TRACK_PARAM. */
    fun setTrackParam(track: Int, id: Int, value: Int) {
        val s = _state.value
        if (track == s.selectedTrack) { setParam(0, id, value); return }
        val l = link ?: return
        val d = s.desc(0, id)
        val v = if (d != null) value.coerceIn(d.min, d.max) else value
        val key = (if (id == Pid.PAN) "pan:" else "tp$id:") + track
        touchedAt[key] = SystemClock.elapsedRealtime()
        _state.update { st -> st.withTrackValue(track, id, v) }
        scope.launch {
            runCatching { Parse.trackParam(l.request(Requests.trackParam(track, id, v), key = key)) }
                .onSuccess { r -> if (!l.hasQueued(key)) _state.update { st -> st.withTrackValue(track, id, r.value) } }
        }
    }

    private fun DeviceState.withTrackValue(track: Int, id: Int, v: Int) = copy(strips = strips.mapIndexed { i, x ->
        if (i != track) x else when (id) { Pid.PAN -> x.copy(pan = v); Pid.REV -> x.copy(rev = v); else -> x }
    })

    // ---------------------------------------------------------------- steps ---

    @Volatile private var stepsStale = true

    private class StepEdit(val track: Int, val indices: List<Int>, val before: List<Step>, val after: List<Step>)
    private val undo = ArrayDeque<StepEdit>()
    private val redo = ArrayDeque<StepEdit>()

    /** The Sequencer is shown or hidden: its steps are read when it opens (64 requests), and polled faster while it is. */
    fun sequencerShown(on: Boolean) {
        setSequencerShown(on)
        if (on) ensureSteps()
    }

    @Volatile private var stepsQueued = false

    /**
     * Reads the selected track's steps if they are not read (its first LEN steps first, so the roll fills as it loads). Safe
     * to call again and again: one read is queued at a time and it checks, when its turn comes, that the steps are still
     * wanted (a track switch reads them itself).
     */
    fun ensureSteps() {
        val s = _state.value
        if (link == null || s.link != Link.READY || (s.stepsReady && !stepsStale) || stepsQueued) return
        stepsQueued = true
        op {
            try {
                val st = _state.value
                if (!(st.stepsReady && !stepsStale)) readSteps(it)
            } finally { stepsQueued = false }
        }
    }

    private suspend fun readSteps(l: Wire) {
        val track = _state.value.selectedTrack
        val len = _state.value.patternLength
        val order = (0 until len) + (len until Step.NSTEP)
        val got = arrayOfNulls<Step>(Step.NSTEP)
        stepsStale = false
        // another track's steps are blank until read; the same track's stay on screen and are replaced when the read is in
        if (_state.value.stepsTrack != track) _state.update { it.copy(steps = emptyList(), stepsTrack = -1, stepsRead = 0) }
        var done = 0
        for (i in order) {
            if (_state.value.selectedTrack != track) return             // another track picked meanwhile: its reload follows
            got[i] = runCatching { Parse.trackStep(l.request(Requests.trackStepGet(track, i))).step }.getOrNull()
                ?: if (l.closed) throw IOException("closed") else Step()
            done++
            if (done == len) {                                           // the pattern shows now; the rest reads on behind it
                _state.update { it.copy(steps = got.map { s -> s ?: Step() }, stepsTrack = track, stepsRead = done) }
            } else if (done % 16 == 0) _state.update { it.copy(stepsRead = done) }
        }
        // the steps past LEN come in; the ones the user edited meanwhile stay as they are
        _state.update { st ->
            if (st.stepsTrack != track) st
            else st.copy(steps = st.steps.mapIndexed { i, cur -> if (i >= len) got[i] ?: cur else cur }, stepsRead = Step.NSTEP)
        }
    }

    private fun readStep(track: Int, index: Int) {
        val l = link ?: return
        scope.launch {
            runCatching { Parse.trackStep(l.request(Requests.trackStepGet(track, index), key = "rd:$track:$index")) }.onSuccess { r ->
                if (_state.value.stepsTrack == track && !touched("st:$track:$index"))
                    _state.update { st -> st.copy(steps = st.steps.toMutableList().also { it[index] = r.step }) }
            }
        }
    }

    /**
     * Writes edited steps (the whole list as the roll computed it): the changed ones show at once and go out one TRACK_STEP
     * each (the latest of a step wins in the queue), the device's answer taken back when nothing newer is queued. One
     * undo for the lot.
     */
    fun editSteps(next: List<Step>, record: Boolean = true) {
        val s = _state.value
        val l = link ?: return
        if (!s.stepsReady || next.size != Step.NSTEP) return
        val idx = next.indices.filter { next[it] != s.steps[it] }
        if (idx.isEmpty()) return
        val track = s.selectedTrack
        if (record) {
            undo.addLast(StepEdit(track, idx, idx.map { s.steps[it] }, idx.map { next[it] }))
            if (undo.size > 50) undo.removeFirst()
            redo.clear()
        }
        val now = SystemClock.elapsedRealtime()
        for (i in idx) touchedAt["st:$track:$i"] = now
        _state.update { st -> st.copy(steps = next, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
        for (i in idx) {
            val key = "st:$track:$i"
            val sent = next[i]
            scope.launch {
                runCatching { Parse.trackStep(l.request(Requests.trackStepSet(track, i, sent), key = key)) }
                    .onSuccess { r ->
                        if (!l.hasQueued(key) && r.step != sent && _state.value.stepsTrack == track)
                            _state.update { st -> st.copy(steps = st.steps.toMutableList().also { it[i] = r.step }) }
                    }
                    .onFailure { e -> if (!l.closed) _state.update { it.copy(notice = "Step ${i + 1} not written: ${e.message}") } }
            }
        }
    }

    fun undoSteps() {
        val e = undo.removeLastOrNull() ?: return
        redo.addLast(e)
        applyEdit(e, e.before)
    }

    fun redoSteps() {
        val e = redo.removeLastOrNull() ?: return
        undo.addLast(e)
        applyEdit(e, e.after)
    }

    private fun applyEdit(e: StepEdit, steps: List<Step>) {
        val s = _state.value
        if (e.track != s.selectedTrack || !s.stepsReady) { undo.clear(); redo.clear(); _state.update { it.copy(canUndo = false, canRedo = false) }; return }
        val next = s.steps.toMutableList()
        e.indices.forEachIndexed { k, i -> next[i] = steps[k] }
        editSteps(next, record = false)
        _state.update { it.copy(canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
    }

    // ---------------------------------------------------------------- files ---

    fun libraryContext(): LibraryContext? {
        val s = _state.value
        val info = s.info ?: return null
        return LibraryContext(Library.paramKeys(s.pdesc.map { it?.label }, info.pe0, info.pcount), info.engines, info.version, info.pe0)
    }

    /** The current sound as a "felucca-patch" file of the Skiff Editor. */
    fun patchJson(): String? {
        val s = _state.value
        val info = s.info ?: return null
        val dump = s.dump ?: return null
        return Library.patchFile(info.version, info.engines, s.presetName, dump).toString(2)
    }

    fun readFile(text: String): LibraryRead {
        val ctx = libraryContext() ?: throw IllegalStateException("not connected")
        return Library.read(JSONObject(text), ctx)
    }

    /**
     * Plays a patch on the selected track without writing flash, as the Skiff Editor's audition and UP_LOAD do: SET the
     * engine (its defaults and first preset), then every parameter that differs, but the track's own (LEVEL, PAN, ARP,
     * the pattern's ..); an FM6 sound's own patch onto the track. The device takes the SETs within 1.5 s of the engine
     * change as part of that load (SAVE held on the device undoes it all).
     */
    fun applyPatch(pt: Patch) = op { l ->
        val s = _state.value
        val info = s.info ?: return@op
        if (refused(pt.engine)) return@op
        selfLoad()
        l.request(Requests.set(1, gEng(), pt.engine), timeout = 1000)
        val dump = Parse.dump(l.request(Requests.dump()), info)
        val n = minOf(info.pcount, pt.p.size)
        for (i in 0 until n) {
            val v = pt.p[i] ?: continue
            if (v == dump.p[i] || Pid.trackOwn(i, info.pe0)) continue
            l.request(Requests.set(0, i, v))
        }
        pt.fm6?.let { if (info.fm6Factory > 0) l.request(Requests.fm6Put(0, s.selectedTrack, it), timeout = 1000) }
        afterSoundChange(l)
        _state.update { it.copy(notice = "Loaded ${pt.name} on T${s.selectedTrack + 1}") }
    }

    fun release() {
        closeLink()
        scope.cancel()
        engine.release()
    }

    companion object {
        private const val TAG = "SkiffGo"
    }
}
