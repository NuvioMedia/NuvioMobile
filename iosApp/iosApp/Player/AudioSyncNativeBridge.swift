import ComposeApp
import CryptoKit
import Darwin
import Foundation
import Libavcodec
import Libavformat
import Libavutil
import Libswresample
import Network
import SherpaOnnxC
import UIKit

private let audioSyncSampleRate: Int32 = 16_000
private let audioSyncNoTimestamp = Int64.min
private let audioSyncMaxSeekMissMs: Int64 = 30_000
private let audioSyncMinSpeedRatio = 3.0

enum AudioSyncNativeRegistration {
    static func register() {
        AudioSyncNativeRegistry.shared.register(bridge: AudioSyncNativeBridgeImpl())
    }
}

final class AudioSyncNativeBridgeImpl: NSObject, AudioSyncNativeBridge {
    private let monitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "NuvioAudioSyncNetwork")
    private let pathLock = NSLock()
    private var unmetered = false

    override init() {
        super.init()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.pathLock.lock()
            self.unmetered = path.status == .satisfied && !path.isExpensive && !path.isConstrained
            self.pathLock.unlock()
        }
        monitor.start(queue: monitorQueue)
    }

    func isNetworkUnmetered() -> Bool {
        pathLock.lock()
        defer { pathLock.unlock() }
        return unmetered
    }

    func readSpots(
        url: String,
        headers: [String: String],
        spotsMs: [KotlinLong],
        spotMs: Int64,
        targetBytes: Int64,
        maxBytes: Int64,
        minSpotMs: Int64,
        audioLanguage: String?,
        sink: any AudioSyncNativePcmSink
    ) -> AudioSyncNativeSpotResult {
        AudioSyncSpotDecoder(url: url, headers: headers, audioLanguage: audioLanguage).read(
            spotsMs: spotsMs.map { $0.int64Value },
            spotMs: spotMs,
            targetBytes: targetBytes,
            maxBytes: maxBytes,
            minSpotMs: minSpotMs,
            sink: sink
        )
    }

    func createRecognizer(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String,
        threads: Int32,
        provider: String
    ) -> (any AudioSyncNativeRecognizer)? {
        AudioSyncSherpaRecognizer(
            encoder: encoder,
            decoder: decoder,
            joiner: joiner,
            tokens: tokens,
            threads: threads,
            provider: provider
        )
    }

    func downloadFile(
        url: String,
        destinationPath: String,
        expectedSize: Int64,
        sha256: String,
        progress: any AudioSyncNativeDownloadProgress
    ) -> String? {
        AudioSyncModelDownload(
            url: url,
            destinationPath: destinationPath,
            expectedSize: expectedSize,
            sha256: sha256,
            progress: progress
        ).run()
    }

    func isMpvAudioTapSupported() -> Bool {
        AudioSyncMpvTapRegistry.isSupported()
    }

    func isMpvAudioTapAvailable() -> Bool {
        AudioSyncMpvTapRegistry.isAvailable()
    }

    func enableMpvAudioTap(ringCapacitySamples: Int32) -> Int64 {
        AudioSyncMpvTapRegistry.enable(ringCapacitySamples: ringCapacitySamples)
    }

    func setMpvAudioTapPaused(token: Int64, paused: Bool) {
        AudioSyncMpvTapRegistry.setCallerPaused(token: token, paused: paused)
    }

    func readMpvAudioTap(token: Int64, outSamples: NSMutableData, maxSamples: Int32, outMeta: AudioSyncNativeTapChunk) -> Int32 {
        AudioSyncMpvTapRegistry.read(token: token, outSamples: outSamples, maxSamples: maxSamples, outMeta: outMeta)
    }

    func disableMpvAudioTap(token: Int64) {
        AudioSyncMpvTapRegistry.disable(token: token)
    }
}

/// Detaches libmpv's live audio tap (`patches/libmpv/0010-audio-tap.patch`) from MPVPlayerBridge.
/// MPVPlayerViewController only registers/unregisters its `mpv_handle*` on init and teardown.
///
/// Lock hierarchy is strictly `lifecycleLock -> tapLock` so `setCallerPaused`, `setBackgroundPaused`,
/// and `read` never wait behind `mpv_audio_tap_create` (`mp_dispatch_lock`), while `detach` still
/// serializes tap teardown strictly before `mpv_terminate_destroy`.
enum AudioSyncMpvTapRegistry {
    private struct Functions {
        static let expectedApiVersion: UInt32 = 1
        static let configByteSize: UInt32 = 16
        static let chunkByteSize: UInt32 = 32
        static let flagEof: Int32 = 1 << 2

        typealias ApiVersionFn = @convention(c) () -> UInt32
        typealias CreateFn = @convention(c) (OpaquePointer?, UnsafeRawPointer?) -> OpaquePointer?
        typealias ReadFn = @convention(c) (OpaquePointer?, UnsafeMutablePointer<Float>?, UInt32, UnsafeMutableRawPointer?) -> Int32
        typealias SetPausedFn = @convention(c) (OpaquePointer?, Int32) -> Void
        typealias DestroyFn = @convention(c) (OpaquePointer?) -> Void

        let create: CreateFn
        let read: ReadFn
        let setPaused: SetPausedFn
        let destroy: DestroyFn

        static let resolved: Functions? = {
            let rtldDefault = UnsafeMutableRawPointer(bitPattern: -2)
            guard
                let versionSym = dlsym(rtldDefault, "mpv_audio_tap_api_version"),
                let createSym = dlsym(rtldDefault, "mpv_audio_tap_create"),
                let readSym = dlsym(rtldDefault, "mpv_audio_tap_read"),
                let pauseSym = dlsym(rtldDefault, "mpv_audio_tap_set_paused"),
                let destroySym = dlsym(rtldDefault, "mpv_audio_tap_destroy")
            else {
                return nil
            }
            let apiVersion = unsafeBitCast(versionSym, to: ApiVersionFn.self)
            guard apiVersion() == expectedApiVersion else {
                return nil
            }
            return Functions(
                create: unsafeBitCast(createSym, to: CreateFn.self),
                read: unsafeBitCast(readSym, to: ReadFn.self),
                setPaused: unsafeBitCast(pauseSym, to: SetPausedFn.self),
                destroy: unsafeBitCast(destroySym, to: DestroyFn.self)
            )
        }()
    }

    private static let lifecycleLock = NSLock()
    private static let tapLock = NSLock()
    private static var activeMpv: OpaquePointer?
    private static var hasAttachedMpv = false
    private static var activeTap: OpaquePointer?
    private static var activeToken: Int64 = 0
    private static var nextToken: Int64 = 0
    private static var callerPaused = false
    private static var backgroundPaused = false
    private static var lifecycleInstalled = false

    static func attach(_ mpv: OpaquePointer?) {
        installLifecycleObserversIfNeeded()
        var staleTap: OpaquePointer?
        lifecycleLock.lock()
        if activeMpv != mpv {
            activeMpv = mpv
            tapLock.lock()
            hasAttachedMpv = (mpv != nil)
            staleTap = activeTap
            activeTap = nil
            activeToken = 0
            callerPaused = false
            tapLock.unlock()
        }
        lifecycleLock.unlock()
        if let staleTap, let fns = Functions.resolved {
            fns.destroy(staleTap)
        }
    }

    static func detach(_ mpv: OpaquePointer) {
        var tapToDestroy: OpaquePointer?
        lifecycleLock.lock()
        if activeMpv == mpv {
            activeMpv = nil
            tapLock.lock()
            hasAttachedMpv = false
            tapToDestroy = activeTap
            activeTap = nil
            activeToken = 0
            callerPaused = false
            tapLock.unlock()
        }
        lifecycleLock.unlock()
        if let tapToDestroy, let fns = Functions.resolved {
            fns.destroy(tapToDestroy)
        }
    }

    static func isSupported() -> Bool {
        Functions.resolved != nil
    }

    /// Reads `hasAttachedMpv` under `tapLock` so Main-thread ticks never block behind `lifecycleLock` / `mp_dispatch_lock`.
    static func isAvailable() -> Bool {
        guard Functions.resolved != nil else { return false }
        tapLock.lock()
        let available = hasAttachedMpv
        tapLock.unlock()
        return available
    }

    static func enable(ringCapacitySamples: Int32) -> Int64 {
        guard let fns = Functions.resolved else { return 0 }
        var replacedTap: OpaquePointer?
        var assignedToken: Int64 = 0

        lifecycleLock.lock()
        if let ctx = activeMpv {
            var cfgStorage: (UInt32, UInt32, UInt32, UInt32) = (
                Functions.configByteSize,
                Functions.expectedApiVersion,
                ringCapacitySamples > 0 ? UInt32(ringCapacitySamples) : 0,
                0
            )
            let created = withUnsafeBytes(of: &cfgStorage) { raw -> OpaquePointer? in
                fns.create(ctx, raw.baseAddress)
            }
            if let created {
                tapLock.lock()
                replacedTap = activeTap
                activeTap = created
                nextToken += 1
                activeToken = nextToken
                assignedToken = activeToken
                callerPaused = false
                fns.setPaused(created, backgroundPaused ? 1 : 0)
                tapLock.unlock()
            }
        }
        lifecycleLock.unlock()

        if let replacedTap {
            fns.destroy(replacedTap)
        }
        return assignedToken
    }

    static func setCallerPaused(token: Int64, paused: Bool) {
        guard token != 0 else { return }
        tapLock.lock()
        if token == activeToken {
            callerPaused = paused
            if let tap = activeTap, let fns = Functions.resolved {
                fns.setPaused(tap, (callerPaused || backgroundPaused) ? 1 : 0)
            }
        }
        tapLock.unlock()
    }

    static func read(token: Int64, outSamples: NSMutableData, maxSamples: Int32, outMeta: AudioSyncNativeTapChunk) -> Int32 {
        guard let fns = Functions.resolved else { return -1 }
        let capacitySamples = Int32(outSamples.length / MemoryLayout<Float>.size)
        let limit = min(maxSamples, capacitySamples)
        guard limit > 0 else { return -1 }

        tapLock.lock()
        defer { tapLock.unlock() }
        guard token != 0, token == activeToken, let tap = activeTap else {
            outMeta.sampleRate = 0
            outMeta.sampleCount = 0
            outMeta.generation = 0
            outMeta.ptsUs = 0
            outMeta.flags = Functions.flagEof
            return 0
        }

        // Stack-allocated 32-byte C-layout buffer matching struct mpv_audio_tap_chunk.
        var chunkStorage: (UInt64, UInt64, UInt64, UInt64) = (0, 0, 0, 0)
        return withUnsafeMutableBytes(of: &chunkStorage) { raw -> Int32 in
            raw.storeBytes(of: Functions.chunkByteSize, toByteOffset: 0, as: UInt32.self)
            let samplesPtr = outSamples.mutableBytes.assumingMemoryBound(to: Float.self)
            let n = fns.read(tap, samplesPtr, UInt32(limit), raw.baseAddress)
            outMeta.sampleRate = Int32(bitPattern: raw.load(fromByteOffset: 4, as: UInt32.self))
            outMeta.sampleCount = Int32(bitPattern: raw.load(fromByteOffset: 8, as: UInt32.self))
            outMeta.generation = Int32(bitPattern: raw.load(fromByteOffset: 12, as: UInt32.self))
            outMeta.ptsUs = raw.load(fromByteOffset: 16, as: Int64.self)
            outMeta.flags = Int32(bitPattern: raw.load(fromByteOffset: 24, as: UInt32.self))
            return n
        }
    }

    static func disable(token: Int64) {
        guard token != 0 else { return }
        var tapToDestroy: OpaquePointer?
        tapLock.lock()
        if token == activeToken {
            tapToDestroy = activeTap
            activeTap = nil
            activeToken = 0
            callerPaused = false
        }
        tapLock.unlock()
        if let tapToDestroy, let fns = Functions.resolved {
            fns.destroy(tapToDestroy)
        }
    }

    private static func setBackgroundPaused(_ paused: Bool) {
        tapLock.lock()
        backgroundPaused = paused
        if let tap = activeTap, let fns = Functions.resolved {
            fns.setPaused(tap, (callerPaused || backgroundPaused) ? 1 : 0)
        }
        tapLock.unlock()
    }

    private static func installLifecycleObserversIfNeeded() {
        tapLock.lock()
        let alreadyInstalled = lifecycleInstalled
        lifecycleInstalled = true
        tapLock.unlock()
        guard !alreadyInstalled else { return }
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: nil
        ) { _ in
            setBackgroundPaused(true)
        }
        NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: nil
        ) { _ in
            setBackgroundPaused(false)
        }
    }
}

private final class AudioSyncSpotDecoder {
    private let url: String
    private let headers: [String: String]
    private let audioLanguage: String?
    private let microseconds = AVRational(num: 1, den: 1_000_000)

    init(url: String, headers: [String: String], audioLanguage: String?) {
        self.url = url
        self.headers = headers
        self.audioLanguage = audioLanguage
    }

    func read(
        spotsMs: [Int64],
        spotMs: Int64,
        targetBytes: Int64,
        maxBytes: Int64,
        minSpotMs: Int64,
        sink: any AudioSyncNativePcmSink
    ) -> AudioSyncNativeSpotResult {
        var formatContext: UnsafeMutablePointer<AVFormatContext>?
        var options: OpaquePointer?
        let userAgent = headers.first { $0.key.caseInsensitiveCompare("User-Agent") == .orderedSame }?.value
        let headerText = headers
            .filter { $0.key.caseInsensitiveCompare("User-Agent") != .orderedSame }
            .map { "\($0.key): \($0.value)\r\n" }
            .joined()
        if !headerText.isEmpty { av_dict_set(&options, "headers", headerText, 0) }
        if let userAgent { av_dict_set(&options, "user_agent", userAgent, 0) }
        av_dict_set(&options, "rw_timeout", "20000000", 0)
        let opened = avformat_open_input(&formatContext, url, nil, &options)
        av_dict_free(&options)
        guard opened >= 0, let context = formatContext else {
            return AudioSyncNativeSpotResult(sampled: 0, bytes: 0, failure: "stream could not be opened")
        }
        defer { avformat_close_input(&formatContext) }
        guard avformat_find_stream_info(context, nil) >= 0 else {
            return AudioSyncNativeSpotResult(sampled: 0, bytes: 0, failure: "stream info unavailable")
        }
        guard let streamIndex = chooseAudioStream(context),
              let stream = context.pointee.streams[streamIndex],
              let parameters = stream.pointee.codecpar,
              let codec = avcodec_find_decoder(parameters.pointee.codec_id) else {
            return AudioSyncNativeSpotResult(sampled: 0, bytes: 0, failure: "no audio track")
        }
        for index in 0..<Int(context.pointee.nb_streams) where index != streamIndex {
            context.pointee.streams[index]?.pointee.discard = AVDISCARD_ALL
        }
        var codecContext = avcodec_alloc_context3(codec)
        defer { avcodec_free_context(&codecContext) }
        guard let decoder = codecContext,
              avcodec_parameters_to_context(decoder, parameters) >= 0,
              avcodec_open2(decoder, codec, nil) >= 0 else {
            return AudioSyncNativeSpotResult(sampled: 0, bytes: 0, failure: "audio decoder unavailable")
        }
        var packetStorage = av_packet_alloc()
        var frameStorage = av_frame_alloc()
        defer {
            av_packet_free(&packetStorage)
            av_frame_free(&frameStorage)
        }
        guard let packet = packetStorage, let frame = frameStorage else {
            return AudioSyncNativeSpotResult(sampled: 0, bytes: 0, failure: "out of memory")
        }
        let timeBase = stream.pointee.time_base
        let startTimeUs: Int64 = context.pointee.start_time != audioSyncNoTimestamp
            ? context.pointee.start_time
            : 0
        let startedAt = Date()
        var sampled: Int32 = 0
        var failure: String?
        for (index, spotStartMs) in spotsMs.enumerated() {
            if sink.isCancelled() || bytesRead(context) >= maxBytes { break }
            let seekTargetUs = spotStartMs * 1_000 + startTimeUs
            guard av_seek_frame(context, -1, seekTargetUs, AVSEEK_FLAG_BACKWARD) >= 0 else {
                failure = "stream cannot seek"
                break
            }
            avcodec_flush_buffers(decoder)
            let resampler = AudioSyncResampler()
            let spotStartBytes = bytesRead(context)
            let share = max(0, targetBytes - spotStartBytes) / Int64(spotsMs.count - index)
            let endUs = (spotStartMs + spotMs) * 1_000
            var firstUs: Int64?
            var seekMissed = false
            while !sink.isCancelled() {
                if av_read_frame(context, packet) < 0 { break }
                defer { av_packet_unref(packet) }
                guard packet.pointee.stream_index == Int32(streamIndex) else { continue }
                let rawTime = packet.pointee.pts != audioSyncNoTimestamp ? packet.pointee.pts : packet.pointee.dts
                guard rawTime != audioSyncNoTimestamp else { continue }
                let timeUs = av_rescale_q(rawTime, timeBase, microseconds) - startTimeUs
                if firstUs == nil {
                    if abs(timeUs / 1_000 - spotStartMs) > audioSyncMaxSeekMissMs {
                        seekMissed = true
                        break
                    }
                    firstUs = timeUs
                }
                if timeUs >= endUs { break }
                let readBytes = bytesRead(context)
                if readBytes >= maxBytes { break }
                let spotBytes = readBytes - spotStartBytes
                if spotBytes >= share && !wantsMore(
                    spotBytes: spotBytes,
                    readUs: timeUs - (firstUs ?? timeUs),
                    minSpotMs: minSpotMs,
                    downloadedBytes: readBytes,
                    startedAt: startedAt
                ) {
                    break
                }
                decode(
                    packet: packet,
                    decoder: decoder,
                    frame: frame,
                    resampler: resampler,
                    timeBase: timeBase,
                    startTimeUs: startTimeUs,
                    sink: sink
                )
            }
            if !seekMissed && !sink.isCancelled() {
                decode(
                    packet: nil,
                    decoder: decoder,
                    frame: frame,
                    resampler: resampler,
                    timeBase: timeBase,
                    startTimeUs: startTimeUs,
                    sink: sink
                )
            }
            resampler.close()
            if seekMissed {
                failure = "stream cannot seek"
                break
            }
            if firstUs != nil { sampled += 1 }
            sink.onSpotEnd()
        }
        return AudioSyncNativeSpotResult(sampled: sampled, bytes: bytesRead(context), failure: failure)
    }

    private func bytesRead(_ context: UnsafeMutablePointer<AVFormatContext>) -> Int64 {
        guard let io = context.pointee.pb else { return 0 }
        return io.pointee.bytes_read
    }

    private func wantsMore(spotBytes: Int64, readUs: Int64, minSpotMs: Int64, downloadedBytes: Int64, startedAt: Date) -> Bool {
        if readUs <= 0 || readUs >= minSpotMs * 1_000 { return false }
        let streamBytesPerSecond = Double(spotBytes) * 1_000_000.0 / Double(readUs)
        let elapsed = Date().timeIntervalSince(startedAt)
        let downloadBytesPerSecond = elapsed > 0 ? Double(downloadedBytes) / elapsed : 0
        return downloadBytesPerSecond >= audioSyncMinSpeedRatio * streamBytesPerSecond
    }

    private func decode(
        packet: UnsafeMutablePointer<AVPacket>?,
        decoder: UnsafeMutablePointer<AVCodecContext>,
        frame: UnsafeMutablePointer<AVFrame>,
        resampler: AudioSyncResampler,
        timeBase: AVRational,
        startTimeUs: Int64,
        sink: any AudioSyncNativePcmSink
    ) {
        guard avcodec_send_packet(decoder, packet.map { UnsafePointer($0) }) >= 0 else { return }
        while avcodec_receive_frame(decoder, frame) >= 0 {
            defer { av_frame_unref(frame) }
            let sampleCount = frame.pointee.nb_samples
            guard sampleCount > 0 else { continue }
            let bestEffort = frame.pointee.best_effort_timestamp
            let rawTime = bestEffort != audioSyncNoTimestamp ? bestEffort : frame.pointee.pts
            guard rawTime != audioSyncNoTimestamp else { continue }
            let timeUs = av_rescale_q(rawTime, timeBase, microseconds) - startTimeUs
            guard timeUs >= 0 else { continue }
            guard let samples = resampler.convert(frame: frame), !samples.isEmpty else { continue }
            let data = samples.withUnsafeBytes { Data($0) }
            sink.onPcm(samples: data, frames: Int32(samples.count), sampleRate: audioSyncSampleRate, timeUs: timeUs)
        }
    }

    private func chooseAudioStream(_ context: UnsafeMutablePointer<AVFormatContext>) -> Int? {
        let wanted = normalizedLanguage(audioLanguage)
        var first: Int?
        for index in 0..<Int(context.pointee.nb_streams) {
            guard let stream = context.pointee.streams[index],
                  let parameters = stream.pointee.codecpar,
                  parameters.pointee.codec_type == AVMEDIA_TYPE_AUDIO else { continue }
            if first == nil { first = index }
            guard let wanted,
                  let entry = av_dict_get(stream.pointee.metadata, "language", nil, 0),
                  let value = entry.pointee.value else { continue }
            if normalizedLanguage(String(cString: value)) == wanted { return index }
        }
        let best = av_find_best_stream(context, AVMEDIA_TYPE_AUDIO, -1, -1, nil, 0)
        return best >= 0 ? Int(best) : first
    }

    private func normalizedLanguage(_ language: String?) -> String? {
        guard let value = language?.trimmingCharacters(in: .whitespaces).lowercased(), !value.isEmpty,
              !["und", "mul", "zxx", "mis", "unknown"].contains(value) else { return nil }
        return value.split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map(String.init)
    }
}

private final class AudioSyncResampler {
    private var context: OpaquePointer?
    private var inputRate: Int32 = 0
    private var inputFormat: Int32 = -1
    private var inputChannels: Int32 = 0

    func convert(frame: UnsafeMutablePointer<AVFrame>) -> [Float]? {
        let rate = frame.pointee.sample_rate
        let format = frame.pointee.format
        let channels = frame.pointee.ch_layout.nb_channels
        guard rate > 0, channels > 0 else { return nil }
        if context == nil || rate != inputRate || format != inputFormat || channels != inputChannels {
            close()
            guard open(frame: frame) else { return nil }
            inputRate = rate
            inputFormat = format
            inputChannels = channels
        }
        let inputCount = frame.pointee.nb_samples
        let capacity = swr_get_out_samples(context, inputCount)
        guard capacity > 0, let extended = frame.pointee.extended_data else { return nil }
        var output = [Float](repeating: 0, count: Int(capacity))
        let produced: Int32 = output.withUnsafeMutableBytes { raw -> Int32 in
            var outputPointer: UnsafeMutablePointer<UInt8>? = raw.baseAddress?.assumingMemoryBound(to: UInt8.self)
            let input = UnsafeRawPointer(extended).assumingMemoryBound(to: UnsafePointer<UInt8>?.self)
            return swr_convert(context, &outputPointer, capacity, input, inputCount)
        }
        guard produced > 0 else { return nil }
        return Array(output.prefix(Int(produced)))
    }

    func close() {
        if context != nil { swr_free(&context) }
        context = nil
    }

    private func open(frame: UnsafeMutablePointer<AVFrame>) -> Bool {
        var outputLayout = AVChannelLayout()
        var inputLayout = AVChannelLayout()
        defer {
            av_channel_layout_uninit(&outputLayout)
            av_channel_layout_uninit(&inputLayout)
        }
        av_channel_layout_default(&outputLayout, 1)
        if frame.pointee.ch_layout.order != AV_CHANNEL_ORDER_UNSPEC {
            guard av_channel_layout_copy(&inputLayout, &frame.pointee.ch_layout) >= 0 else { return false }
        } else {
            av_channel_layout_default(&inputLayout, frame.pointee.ch_layout.nb_channels)
        }
        var created: OpaquePointer?
        let status = swr_alloc_set_opts2(
            &created,
            &outputLayout,
            AV_SAMPLE_FMT_FLT,
            audioSyncSampleRate,
            &inputLayout,
            AVSampleFormat(rawValue: frame.pointee.format),
            frame.pointee.sample_rate,
            0,
            nil
        )
        guard status >= 0, created != nil, swr_init(created) >= 0 else {
            swr_free(&created)
            return false
        }
        context = created
        return true
    }

    deinit {
        close()
    }
}

final class AudioSyncSherpaRecognizer: NSObject, AudioSyncNativeRecognizer {
    private let lock = NSLock()
    private var recognizer: OpaquePointer?

    init?(encoder: String, decoder: String, joiner: String, tokens: String, threads: Int32, provider: String) {
        let strings = [encoder, decoder, joiner, tokens, provider, "transducer", "greedy_search"].map { strdup($0) }
        defer { strings.forEach { free($0) } }
        var config = SherpaOnnxOfflineRecognizerConfig()
        config.feat_config.sample_rate = audioSyncSampleRate
        config.feat_config.feature_dim = 80
        config.model_config.transducer.encoder = UnsafePointer(strings[0])
        config.model_config.transducer.decoder = UnsafePointer(strings[1])
        config.model_config.transducer.joiner = UnsafePointer(strings[2])
        config.model_config.tokens = UnsafePointer(strings[3])
        config.model_config.num_threads = threads
        config.model_config.provider = UnsafePointer(strings[4])
        config.model_config.model_type = UnsafePointer(strings[5])
        config.decoding_method = UnsafePointer(strings[6])
        config.max_active_paths = 4
        guard let created = SherpaOnnxCreateOfflineRecognizer(&config) else { return nil }
        recognizer = created
        super.init()
    }

    func transcribe(samples: Data, frames: Int32) -> AudioSyncNativeTranscript {
        lock.lock()
        defer { lock.unlock() }
        let empty = AudioSyncNativeTranscript(tokens: [], timestamps: [])
        guard let recognizer, frames > 0, let stream = SherpaOnnxCreateOfflineStream(recognizer) else { return empty }
        defer { SherpaOnnxDestroyOfflineStream(stream) }
        samples.withUnsafeBytes { raw in
            guard let base = raw.baseAddress?.assumingMemoryBound(to: Float.self) else { return }
            SherpaOnnxAcceptWaveformOffline(stream, audioSyncSampleRate, base, frames)
        }
        SherpaOnnxDecodeOfflineStream(recognizer, stream)
        guard let result = SherpaOnnxGetOfflineStreamResult(stream) else { return empty }
        defer { SherpaOnnxDestroyOfflineRecognizerResult(result) }
        let count = Int(result.pointee.count)
        var tokens: [String] = []
        var timestamps: [KotlinDouble] = []
        if let array = result.pointee.tokens_arr {
            for index in 0..<count {
                tokens.append(array[index].map { String(cString: $0) } ?? "")
            }
        }
        if let times = result.pointee.timestamps {
            for index in 0..<count {
                timestamps.append(KotlinDouble(value: Double(times[index])))
            }
        }
        return AudioSyncNativeTranscript(tokens: tokens, timestamps: timestamps)
    }

    func close() {
        lock.lock()
        defer { lock.unlock() }
        if let recognizer {
            SherpaOnnxDestroyOfflineRecognizer(recognizer)
        }
        recognizer = nil
    }

    deinit {
        if let recognizer {
            SherpaOnnxDestroyOfflineRecognizer(recognizer)
        }
    }
}

private final class AudioSyncModelDownload: NSObject, URLSessionDownloadDelegate {
    private let url: String
    private let destinationPath: String
    private let expectedSize: Int64
    private let sha256: String
    private let progress: any AudioSyncNativeDownloadProgress
    private let finished = DispatchSemaphore(value: 0)
    private var downloadedFile: URL?
    private var failure: String?
    private var lastReported: Int64 = 0

    init(url: String, destinationPath: String, expectedSize: Int64, sha256: String, progress: any AudioSyncNativeDownloadProgress) {
        self.url = url
        self.destinationPath = destinationPath
        self.expectedSize = expectedSize
        self.sha256 = sha256
        self.progress = progress
    }

    func run() -> String? {
        guard let source = URL(string: url) else { return "invalid model URL" }
        let fileManager = FileManager.default
        let destination = URL(fileURLWithPath: destinationPath)
        let directory = destination.deletingLastPathComponent()
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            return error.localizedDescription
        }
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        session.downloadTask(with: source).resume()
        finished.wait()
        session.finishTasksAndInvalidate()
        if let failure { return failure }
        guard let file = downloadedFile else { return "model download failed" }
        defer { try? fileManager.removeItem(at: file) }
        guard let handle = try? FileHandle(forReadingFrom: file) else { return "could not read the downloaded model file" }
        var hasher = SHA256()
        var size: Int64 = 0
        while true {
            let chunk = handle.readData(ofLength: 1 << 20)
            if chunk.isEmpty { break }
            size += Int64(chunk.count)
            hasher.update(data: chunk)
        }
        try? handle.close()
        guard size == expectedSize else { return "Model file size \(size) != \(expectedSize)" }
        let hash = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard hash == sha256 else { return "Model file checksum mismatch" }
        do {
            if fileManager.fileExists(atPath: destination.path) {
                try fileManager.removeItem(at: destination)
            }
            try fileManager.moveItem(at: file, to: destination)
        } catch {
            return error.localizedDescription
        }
        var excluded = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? excluded.setResourceValues(values)
        progress.onBytes(written: size)
        return nil
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        if let response = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(response.statusCode) {
            failure = "HTTP \(response.statusCode)"
            return
        }
        let copy = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        do {
            try FileManager.default.moveItem(at: location, to: copy)
            downloadedFile = copy
        } catch {
            failure = error.localizedDescription
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        if totalBytesWritten > expectedSize {
            failure = "Model file exceeds expected size"
            downloadTask.cancel()
            return
        }
        if totalBytesWritten - lastReported >= 512 * 1_024 {
            lastReported = totalBytesWritten
            progress.onBytes(written: totalBytesWritten)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error, failure == nil {
            failure = error.localizedDescription
        }
        finished.signal()
    }
}
