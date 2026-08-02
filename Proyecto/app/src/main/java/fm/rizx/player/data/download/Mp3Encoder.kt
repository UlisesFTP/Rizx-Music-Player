package fm.rizx.player.data.download

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.OutputStream

/**
 * Encodes 16-bit PCM to MP3. One instance per file; not thread-safe (LAME's state machine isn't).
 *
 * An interface so the engine can be swapped without touching the pipeline — the shipped one is a pure
 * Java LAME, and if a phone ever proves it too slow the replacement is native LAME behind this same
 * pair of methods.
 */
interface Mp3Encoder {
    /** Encodes [frames] interleaved frames from [pcm] (L R L R…, mono = 1/frame) into [out]. */
    fun encode(pcm: ShortArray, frames: Int, out: OutputStream)

    /** Flushes LAME's internal buffer. Call exactly once, after the last [encode]. */
    fun finish(out: OutputStream)
}

/**
 * jump3r (LAME 3.98.4 in pure Java) at 320 kbps CBR — the format's ceiling, which for a transcode from
 * an already-lossy source is the setting that adds the least *further* loss.
 *
 * **Why this drives `mp3.*` directly instead of the library's own `lowlevel.LameEncoder`:** that class
 * types its API in `javax.sound.sampled`, which Android does not have — merely loading it throws. So
 * this reproduces the two things it did that matter, with the parts that don't apply switched off:
 *
 * - the module graph below is its `nInitParams` wiring verbatim — LAME-in-Java is fifteen mutually
 *   referencing modules, and the graph is the price of admission;
 * - PCM enters via `lame_encode_buffer_int`, which wants each sample in the int's **top 16 bits**
 *   (`sample << 16`), one array per channel — the same scaling its `doEncodeBuffer` applied.
 *
 * Switched off, deliberately: ReplayGain analysis (`findReplayGain` — we don't read the result, and it
 * is pure extra CPU per sample), the automatic ID3 writer (jaudiotagger tags the finished file), and
 * the Xing/Info header (`bWriteVbrTag`) — it must be patched into frame 0 *after* encoding, which is
 * seek-back bookkeeping that buys nothing for constant-bitrate audio: with CBR every player derives
 * duration from the bitrate exactly.
 */
class Jump3rMp3Encoder(
    sampleRateHz: Int,
    private val channels: Int,
    bitrateKbps: Int = BITRATE_KBPS,
) : Mp3Encoder {

    private val lame = Lame()
    private val gfp: LameGlobalFlags

    init {
        require(channels in 1..2) { "mp3 is mono or stereo, got $channels channels" }
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        gfp = lame.lame_init()
        gfp.num_channels = channels
        gfp.in_samplerate = sampleRateHz
        gfp.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        gfp.brate = bitrateKbps
        gfp.quality = QUALITY
        gfp.bWriteVbrTag = false
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false
        val rc = lame.lame_init_params(gfp)
        check(rc >= 0) { "LAME rejected ${sampleRateHz}Hz/${channels}ch @${bitrateKbps}kbps (rc=$rc)" }
    }

    private val left = IntArray(FRAMES_PER_CALL)
    private val right = IntArray(FRAMES_PER_CALL)
    private val mp3buf = ByteArray(Lame.LAME_MAXMP3BUFFER)

    override fun encode(pcm: ShortArray, frames: Int, out: OutputStream) {
        var at = 0
        while (at < frames) {
            val n = minOf(FRAMES_PER_CALL, frames - at)
            if (channels == 2) {
                for (i in 0 until n) {
                    left[i] = pcm[(at + i) * 2].toInt() shl 16
                    right[i] = pcm[(at + i) * 2 + 1].toInt() shl 16
                }
            } else {
                for (i in 0 until n) left[i] = pcm[at + i].toInt() shl 16
            }
            val bytes = lame.lame_encode_buffer_int(
                gfp, left, if (channels == 2) right else left, n, mp3buf, 0, mp3buf.size,
            )
            check(bytes >= 0) { "LAME encode error $bytes" }
            out.write(mp3buf, 0, bytes)
            at += n
        }
    }

    override fun finish(out: OutputStream) {
        val bytes = lame.lame_encode_flush(gfp, mp3buf, 0, mp3buf.size)
        if (bytes > 0) out.write(mp3buf, 0, bytes)
        lame.lame_close(gfp)
    }

    companion object {
        const val BITRATE_KBPS = 320

        /**
         * LAME's `-q` knob: psychoacoustic effort, not bitrate. 3 ≈ the `-h` switch — measurably better
         * than the default 5, and the CPU it costs is background CPU on a file, not latency on a tap.
         */
        const val QUALITY = 3

        /** Frames handed to LAME per call; sized so the mp3 buffer above can never be too small. */
        const val FRAMES_PER_CALL = 4608
    }
}
