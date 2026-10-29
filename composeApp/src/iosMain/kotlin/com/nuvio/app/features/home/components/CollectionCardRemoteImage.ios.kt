package com.nuvio.app.features.home.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import platform.CoreGraphics.CGImageRef
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetCount
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode
import platform.CoreGraphics.CGImageRelease
import kotlinx.cinterop.usePinned

private val gifHttpClient = HttpClient(Darwin)
private val gifDecodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private const val MaxCachedGifImages = 12
private const val DefaultGifFrameDelayCentiseconds = 10
private val gifImageCache = mutableMapOf<String, UIImage>()
private val gifImageCacheOrder = mutableListOf<String>()
private val gifImageInFlight = mutableMapOf<String, Deferred<UIImage?>>()

private data class GifFrame(
    val image: UIImage,
    val delayCentiseconds: Int,
)

private data class ExpandedGifFrames(
    val images: List<UIImage>,
    val tickCentiseconds: Int,
)

private class GifImageViewHolder {
    var imageView: UIImageView? = null

    fun clear() {
        imageView?.stopAnimating()
        imageView?.image = null
        imageView = null
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    if (!animateIfPossible) {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    var gifImage by remember(imageUrl) { mutableStateOf(cachedGifImage(imageUrl)) }

    LaunchedEffect(imageUrl) {
        gifImage = loadGifImage(imageUrl)
    }

    val imageViewHolder = remember(imageUrl) { GifImageViewHolder() }
    DisposableEffect(imageUrl) {
        onDispose {
            imageViewHolder.clear()
        }
    }

    UIKitView(
        modifier = modifier,
        factory = {
            UIImageView().apply {
                contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
                clipsToBounds = true
                userInteractionEnabled = false
                tag = imageUrl.hashCode().toLong()
                imageViewHolder.imageView = this
                updateGifImage(gifImage)
            }
        },
        update = { imageView ->
            imageViewHolder.imageView = imageView
            if (imageView.tag != imageUrl.hashCode().toLong()) {
                imageView.tag = imageUrl.hashCode().toLong()
            }
            imageView.updateGifImage(gifImage)
        },
    )
}

private fun UIImageView.updateGifImage(image: UIImage?) {
    if (this.image != image) {
        stopAnimating()
        this.image = image
    }
    if (image != null) {
        startAnimating()
    }
}

private fun cachedGifImage(imageUrl: String): UIImage? {
    val image = gifImageCache[imageUrl] ?: return null
    gifImageCacheOrder.remove(imageUrl)
    gifImageCacheOrder.add(imageUrl)
    return image
}

private fun storeGifImage(imageUrl: String, image: UIImage) {
    gifImageCache[imageUrl] = image
    gifImageCacheOrder.remove(imageUrl)
    gifImageCacheOrder.add(imageUrl)

    while (gifImageCacheOrder.size > MaxCachedGifImages) {
        val eldestKey = gifImageCacheOrder.removeFirstOrNull() ?: break
        gifImageCache.remove(eldestKey)
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun loadGifImage(imageUrl: String): UIImage? {
    cachedGifImage(imageUrl)?.let { return it }

    val request = gifImageInFlight[imageUrl] ?: gifDecodeScope.async {
        runCatching {
            val bytes = gifHttpClient.get(imageUrl).body<ByteArray>()
            bytes
                .takeIf { it.isNotEmpty() }
                ?.let { gifBytes ->
                    UIImage.gifImageWithBytes(
                        bytes = gifBytes,
                        frameDurations = parseGifFrameDurations(gifBytes),
                    )
                }
        }.getOrNull()
    }.also { gifImageInFlight[imageUrl] = it }

    val image = try {
        request.await()
    } finally {
        if (gifImageInFlight[imageUrl] === request) {
            gifImageInFlight.remove(imageUrl)
        }
    }

    if (image != null) {
        storeGifImage(imageUrl, image)
    }

    return image
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData() =
    usePinned { pinned ->
        CFDataCreate(
            allocator = null,
            bytes = pinned.addressOf(0).reinterpret(),
            length = size.toLong(),
        )
    }

@OptIn(ExperimentalForeignApi::class)
private fun UIImage.Companion.gifImageWithBytes(
    bytes: ByteArray,
    frameDurations: List<Int>,
): UIImage? {
    return runCatching {
        val data = bytes.toCFData() ?: return null
        try {
            val source = CGImageSourceCreateWithData(data, null) ?: return null
            try {
                val count = CGImageSourceGetCount(source).toInt()
                val frames = mutableListOf<GifFrame>()

                for (index in 0 until count) {
                    val imageRef: CGImageRef = CGImageSourceCreateImageAtIndex(source, index.toULong(), null) ?: continue
                    try {
                        frames.add(
                            GifFrame(
                                image = UIImage.imageWithCGImage(imageRef),
                                delayCentiseconds = frameDurations.getOrNull(index)
                                    ?.coerceAtLeast(1)
                                    ?: DefaultGifFrameDelayCentiseconds,
                            )
                        )
                    } finally {
                        CGImageRelease(imageRef)
                    }
                }

                if (frames.isEmpty()) return null

                val expanded = expandedGifFrames(frames)
                val durationSeconds = (expanded.images.size * expanded.tickCentiseconds) / 100.0
                UIImage.animatedImageWithImages(expanded.images, durationSeconds)
            } finally {
                CFRelease(source)
            }
        } finally {
            CFRelease(data)
        }
    }.getOrNull()
}

private fun expandedGifFrames(frames: List<GifFrame>): ExpandedGifFrames {
    val normalizedDelays = frames.map { it.delayCentiseconds.coerceAtLeast(1) }
    val tickCentiseconds = normalizedDelays.reduce(::greatestCommonDivisor)
    val expandedSize = normalizedDelays.sumOf { it / tickCentiseconds }
    val expandedFrames = ArrayList<UIImage>(expandedSize)

    frames.forEach { frame ->
        val repeatCount = (frame.delayCentiseconds.coerceAtLeast(1) / tickCentiseconds).coerceAtLeast(1)
        repeat(repeatCount) {
            expandedFrames.add(frame.image)
        }
    }

    return ExpandedGifFrames(
        images = expandedFrames,
        tickCentiseconds = tickCentiseconds,
    )
}

private fun greatestCommonDivisor(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val temp = x % y
        x = y
        y = temp
    }
    return x.coerceAtLeast(1)
}

private fun parseGifFrameDurations(bytes: ByteArray): List<Int> {
    if (bytes.size < 13 || !bytes.hasGifHeader()) return emptyList()

    var index = 6
    if (index + 7 > bytes.size) return emptyList()

    val logicalScreenPacked = bytes[index + 4].unsignedInt()
    index += 7

    if (logicalScreenPacked and 0x80 != 0) {
        val globalColorTableSize = 3 * (1 shl ((logicalScreenPacked and 0x07) + 1))
        index += globalColorTableSize
    }

    val frameDurations = mutableListOf<Int>()
    var pendingDelayCentiseconds: Int? = null

    while (index < bytes.size) {
        when (bytes[index].unsignedInt()) {
            0x21 -> {
                if (index + 1 >= bytes.size) break
                val extensionLabel = bytes[index + 1].unsignedInt()
                if (extensionLabel == 0xF9) {
                    if (index + 7 >= bytes.size) break
                    val delayHundredths = bytes.readUnsignedShort(index + 4)
                    pendingDelayCentiseconds = if (delayHundredths <= 0) {
                        DefaultGifFrameDelayCentiseconds
                    } else {
                        delayHundredths
                    }
                    index += 8
                } else {
                    index += 2
                    index = bytes.skipGifSubBlocks(index)
                }
            }

            0x2C -> {
                if (index + 9 >= bytes.size) break
                val imageDescriptorPacked = bytes[index + 9].unsignedInt()
                index += 10

                if (imageDescriptorPacked and 0x80 != 0) {
                    val localColorTableSize = 3 * (1 shl ((imageDescriptorPacked and 0x07) + 1))
                    index += localColorTableSize
                }

                if (index >= bytes.size) break
                index += 1
                index = bytes.skipGifSubBlocks(index)

                frameDurations += pendingDelayCentiseconds ?: DefaultGifFrameDelayCentiseconds
                pendingDelayCentiseconds = null
            }

            0x3B -> break
            else -> break
        }
    }

    return frameDurations
}

private fun ByteArray.hasGifHeader(): Boolean =
    size >= 6 &&
        this[0] == 'G'.code.toByte() &&
        this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() &&
        this[3] == '8'.code.toByte() &&
        (this[4] == '7'.code.toByte() || this[4] == '9'.code.toByte()) &&
        this[5] == 'a'.code.toByte()

private fun ByteArray.skipGifSubBlocks(startIndex: Int): Int {
    var index = startIndex
    while (index < size) {
        val blockSize = this[index].unsignedInt()
        index += 1
        if (blockSize == 0) {
            return index
        }
        index += blockSize
    }
    return index
}

private fun ByteArray.readUnsignedShort(startIndex: Int): Int {
    if (startIndex + 1 >= size) return 0
    return this[startIndex].unsignedInt() or (this[startIndex + 1].unsignedInt() shl 8)
}

private fun Byte.unsignedInt(): Int = toInt() and 0xFF
