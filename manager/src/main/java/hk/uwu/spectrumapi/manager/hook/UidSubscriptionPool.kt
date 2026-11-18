package hk.uwu.spectrumapi.manager.hook

import android.os.Handler
import hk.uwu.spectrumapi.AnalysisRate
import hk.uwu.spectrumapi.ReusableSpectrumFrame
import hk.uwu.spectrumapi.SourceSelector
import hk.uwu.spectrumapi.SpectrumClient
import hk.uwu.spectrumapi.SpectrumOptions
import hk.uwu.spectrumapi.SpectrumStatus
import hk.uwu.spectrumapi.SpectrumSubscription
import hk.uwu.spectrumapi.manager.hook.render.NativeSixBandProjector
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Main-owned demand, worker-owned SDK reads. One explicit APP_UID stream per demanded full UID. */
class UidSubscriptionPool(private val main: Handler, private val sink: Sink) : AutoCloseable {
    fun interface Sink { fun frame(uid: Int, levels: FloatArray, silent: Boolean) }

    private val worker = Executors.newSingleThreadExecutor { action ->
        Thread(action, "SpectrumIslandData").apply { isDaemon = true }
    }
    private val entries = HashMap<Int, Entry>()
    private var closed = false

    fun demand(uids: Set<Int>, analysisHz: Int, updateHz: Int) {
        if (closed) return
        for (uid in HashSet(entries.keys)) if (uid !in uids) entries.remove(uid)!!.retire()
        for (uid in uids) if (uid >= 0 && !entries.containsKey(uid)) {
            val entry = Entry(uid)
            entries[uid] = entry
            entry.start(analysisHz, updateHz)
        }
    }

    /** Bounded event-triggered route checks share the data worker; never a media polling task. */
    fun execute(action: Runnable) {
        if (!closed) worker.execute(action)
    }

    /** Settings/retry/identity event only; never scheduled reconnect. */
    fun reset() {
        for (entry in ArrayList(entries.values)) entry.retire()
        entries.clear()
    }

    override fun close() {
        closed = true
        reset()
        worker.shutdown()
    }

    private inner class Entry(val uid: Int) : SpectrumSubscription.Listener {
        private val projector = NativeSixBandProjector()
        private val projected = FloatArray(6)
        private val mailbox = FloatArray(6)
        private val delivered = FloatArray(6)
        private var connecting: CompletableFuture<SpectrumClient>? = null
        private var opening: CompletableFuture<SpectrumSubscription>? = null
        private var client: SpectrumClient? = null
        private var stream: SpectrumSubscription? = null
        private var generation = 0L
        private var mailboxGeneration = 0L
        @Volatile private var retired = false
        private var configured = false
        private var posted = false
        private var silent = false
        private var sawFrame = false
        private val delivery = Runnable(::deliver)

        fun start(analysis: Int, updates: Int) {
            IslandLog.info("stream=open source=APP_UID uid=$uid analysisHz=$analysis updateHz=$updates")
            val options = SpectrumOptions.builder().source(SourceSelector.app(uid))
                .analysisRate(AnalysisRate.auto(analysis)).updateRateHz(updates).bandCount(32)
                .frequencyRange(20f, 10000f).outputMode(SpectrumOptions.OUTPUT_SPECTRUM).build()
            synchronized(this) { connecting = SpectrumClient.connect() }
            connecting!!.whenComplete connected@{ connected, error ->
                synchronized(this) {
                    if (retired) {
                        connected?.close()
                        return@connected
                    }
                    if (error != null) {
                        fail("connect", error)
                        return@connected
                    }
                    client = connected
                    opening = connected.subscribe(options)
                    opening!!.whenComplete subscribed@{ subscription, failure ->
                        synchronized(this) {
                            if (retired) {
                                subscription?.close()
                                return@subscribed
                            }
                            if (failure != null) {
                                fail("subscribe", failure)
                                return@subscribed
                            }
                            stream = subscription
                            try {
                                // Activation normally rotates paused metadata. Adopt edges only with a
                                // frame from the descriptor already mapped by the SDK, below.
                                subscription.setListener(worker, this)
                                subscription.setActive(true).whenComplete { _, activeError ->
                                    if (activeError != null) fail("activate", activeError)
                                    else if (!retired) IslandLog.info("stream=active uid=$uid")
                                }
                            } catch (invalid: RuntimeException) {
                                fail("metadata", invalid)
                            }
                        }
                    }
                }
            }
        }

        override fun onFrame(frame: ReusableSpectrumFrame) {
            if (retired) return
            val status = frame.status
            if (fatal(status)) {
                fail("status=$status", null)
                return
            }
            if (status != SpectrumStatus.OK && status != SpectrumStatus.SILENT &&
                status != SpectrumStatus.PARTIAL && status != SpectrumStatus.DROPPED) return
            synchronized(this) {
                if (retired) return
                val current = stream ?: return
                // getGeneration may run ahead of the reader while its descriptor is refreshed.
                // A frame matching that generation proves the new reader has actually produced it.
                synchronized(current) {
                    val next = frame.generation
                    if (next != current.generation) return // Old reader/frame: discard, do not reconnect.
                    if (!configured || next != generation) {
                        val edges = current.bandEdgesHz
                        if (edges.size != frame.bandCount + 1) return // Transitional metadata; wait.
                        try {
                            projector.configure(edges)
                        } catch (invalid: IllegalArgumentException) {
                            fail("band metadata", invalid)
                            return
                        }
                        generation = next
                        configured = true
                    }
                    try {
                        projector.project(frame, projected)
                    } catch (invalid: RuntimeException) {
                        fail("projection", invalid)
                        return
                    }
                    if (!sawFrame) {
                        sawFrame = true
                        IslandLog.info("stream=first_frame uid=$uid status=$status bands=${frame.bandCount}")
                    }
                    projected.copyInto(mailbox, endIndex = 6)
                    mailboxGeneration = next
                    silent = status == SpectrumStatus.SILENT
                }
                if (!posted) {
                    posted = true
                    main.post(delivery)
                }
            }
        }

        override fun onStateChanged(status: Int, currentGeneration: Long) {
            if (retired) return
            if (fatal(status)) {
                fail("state=$status", null)
                return
            }
            synchronized(this) {
                if (retired) return
                val current = stream ?: return
                if (currentGeneration != current.generation) return
                if (!configured || currentGeneration != generation || status == SpectrumStatus.IDLE || status == SpectrumStatus.SILENT) {
                    // Normal paused->active/topology rotations stay on this same session. Discard
                    // queued old audio while the SDK maps its replacement, without opening again.
                    mailbox.fill(0f)
                    silent = true
                    mailboxGeneration = currentGeneration
                    if (!posted) {
                        posted = true
                        main.post(delivery)
                    }
                }
            }
        }

        private fun fatal(status: Int): Boolean = status == SpectrumStatus.DISCONNECTED ||
            status == SpectrumStatus.UNSUPPORTED_BUILD || status == SpectrumStatus.UNSUPPORTED_PATH

        private fun deliver() {
            val idle: Boolean
            synchronized(this) {
                posted = false
                if (retired || closed || entries[uid] !== this) return
                val current = stream ?: return
                if (mailboxGeneration != current.generation) return
                mailbox.copyInto(delivered, endIndex = 6)
                idle = silent
            }
            sink.frame(uid, delivered, idle)
        }

        private fun fail(stage: String, failure: Throwable?) {
            synchronized(this) {
                if (retired) return
                IslandLog.warn("APP_UID=$uid stopped: $stage; explicit retry/target event required", failure)
                retire()
            }
            // Keep the failed entry as a tombstone while demand remains: no automatic retry.
            main.post {
                if (closed || entries[uid] !== this) return@post
                delivered.fill(0f)
                sink.frame(uid, delivered, true)
            }
        }

        @Synchronized
        fun retire() {
            if (retired) return
            retired = true
            IslandLog.info("stream=closed uid=$uid")
            main.removeCallbacks(delivery)
            connecting?.let { if (!it.isDone) it.cancel(true) }
            opening?.let { if (!it.isDone) it.cancel(true) }
            stream?.let { it.close(); stream = null }
            client?.let { it.close(); client = null }
        }
    }
}
