package com.nuvio.app.features.cast

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

internal object DlnaSoap {

    fun buildDidlMetadata(title: String, proxyUrl: String, mimeType: String, duration: String? = null): String {
        val escapedTitle = xmlEscape(title)
        val escapedUrl = xmlEscape(proxyUrl)
        val durationAttr = if (duration != null) """ duration="$duration"""" else ""
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/"><item id="0" parentID="0" restricted="1"><dc:title>$escapedTitle</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01500000000000000000000000000000"$durationAttr>$escapedUrl</res></item></DIDL-Lite>"""
    }

    fun buildSetAvTransportUriBody(instanceId: Int = 0, uri: String, metadata: String): String {
        val escUri = xmlEscape(uri)
        val escMeta = xmlEscape(metadata)
        return """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>$instanceId</InstanceID><CurrentURI>$escUri</CurrentURI><CurrentURIMetaData>$escMeta</CurrentURIMetaData></u:SetAVTransportURI></s:Body></s:Envelope>"""
    }

    fun buildPlayBody(instanceId: Int = 0, speed: String = "1"): String =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>$instanceId</InstanceID><Speed>$speed</Speed></u:Play></s:Body></s:Envelope>"""

    fun buildStopBody(instanceId: Int = 0): String =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>$instanceId</InstanceID></u:Stop></s:Body></s:Envelope>"""

    fun buildSeekBody(instanceId: Int = 0, target: String): String =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>$instanceId</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target></u:Seek></s:Body></s:Envelope>"""

    fun buildPauseBody(instanceId: Int = 0): String =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>$instanceId</InstanceID></u:Pause></s:Body></s:Envelope>"""

    fun buildGetPositionInfoBody(instanceId: Int = 0): String =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:GetPositionInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>$instanceId</InstanceID></u:GetPositionInfo></s:Body></s:Envelope>"""

    fun buildSetVolumeBody(instanceId: Int = 0, channel: String = "Master", desiredVolume: Int): String =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>$instanceId</InstanceID><Channel>$channel</Channel><DesiredVolume>$desiredVolume</DesiredVolume></u:SetVolume></s:Body></s:Envelope>"""

    fun formatDurationMs(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%01d:%02d:%02d".format(h, m, s)
    }

    fun parseDeviceDescription(xmlString: String, locationUrl: String): ParsedDevice? {
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(xmlString.reader())
            var event = parser.eventType
            var friendlyName: String? = null
            var udn: String? = null
            var modelName: String? = null
            var manufacturer: String? = null
            var avTransportControlUrl: String? = null
            var avTransportEventSubUrl: String? = null
            var avTransportServiceType: String? = null
            var renderingControlUrl: String? = null
            var currentServiceType: String? = null
            var currentControlUrl: String? = null
            var currentEventSubUrl: String? = null
            var inService = false
            var baseUrl: String? = null

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "URLBase" -> baseUrl = parser.nextText()?.trim()
                        "friendlyName" -> if (!inService) friendlyName = parser.nextText()?.trim()
                        "UDN" -> if (!inService && udn == null) udn = parser.nextText()?.trim()
                        "modelName" -> if (!inService) modelName = parser.nextText()?.trim()
                        "manufacturer" -> if (!inService) manufacturer = parser.nextText()?.trim()
                        "service" -> {
                            inService = true
                            currentServiceType = null
                            currentControlUrl = null
                            currentEventSubUrl = null
                        }
                        "serviceType" -> if (inService) currentServiceType = parser.nextText()?.trim()
                        "controlURL" -> if (inService) currentControlUrl = parser.nextText()?.trim()
                        "eventSubURL" -> if (inService) currentEventSubUrl = parser.nextText()?.trim()
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "service") {
                        if (currentServiceType?.contains("AVTransport") == true) {
                            avTransportControlUrl = currentControlUrl
                            avTransportEventSubUrl = currentEventSubUrl
                            avTransportServiceType = currentServiceType
                        } else if (currentServiceType?.contains("RenderingControl") == true) {
                            renderingControlUrl = currentControlUrl
                        }
                        inService = false
                    }
                }
                event = parser.next()
            }

            if (avTransportControlUrl == null) return null

            val base = baseUrl ?: locationUrl.substringBefore("/", locationUrl) // fallback
            // Actually use locationUrl's origin
            val origin = try {
                val uri = java.net.URI(locationUrl)
                val portPart = if (uri.port != -1) ":${uri.port}" else ""
                "${uri.scheme}://${uri.host}$portPart"
            } catch (_: Exception) {
                baseUrl ?: ""
            }

            fun absolutize(url: String?): String? {
                if (url == null) return null
                return when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.startsWith("/") -> "$origin$url"
                    else -> "$origin/$url"
                }
            }

            val absoluteControl = absolutize(avTransportControlUrl) ?: return null
            val absoluteEvent = absolutize(avTransportEventSubUrl)
            val absoluteRendering = absolutize(renderingControlUrl)

            ParsedDevice(
                udn = udn ?: locationUrl.hashCode().toString(),
                friendlyName = friendlyName ?: "TV",
                modelName = modelName,
                manufacturer = manufacturer,
                locationUrl = locationUrl,
                controlUrl = absoluteControl,
                eventSubUrl = absoluteEvent,
                serviceType = avTransportServiceType ?: "urn:schemas-upnp-org:service:AVTransport:1",
                renderingControlUrl = absoluteRendering,
            )
        } catch (e: Exception) {
            android.util.Log.w("DlnaSoap", "parseDeviceDescription failed: ${e.message}")
            null
        }
    }

    data class ParsedDevice(
        val udn: String,
        val friendlyName: String,
        val modelName: String?,
        val manufacturer: String?,
        val locationUrl: String,
        val controlUrl: String,
        val eventSubUrl: String?,
        val serviceType: String,
        val renderingControlUrl: String? = null,
    )

    fun parsePositionInfo(xml: String): Long? {
        return try {
            // Look for <RelTime> or <AbsTime> like 0:01:23
            val relTimeRegex = Regex("<RelTime[^>]*>([^<]+)</RelTime>", RegexOption.IGNORE_CASE)
            val relMatch = relTimeRegex.find(xml)?.groupValues?.getOrNull(1)?.trim()
            val timeStr = if (relMatch != null && relMatch != "NOT_IMPLEMENTED") {
                relMatch
            } else {
                val absTimeRegex = Regex("<AbsTime[^>]*>([^<]+)</AbsTime>", RegexOption.IGNORE_CASE)
                absTimeRegex.find(xml)?.groupValues?.getOrNull(1)?.trim()
            }
            if (timeStr == null || timeStr == "NOT_IMPLEMENTED") return null
            if (timeStr == "00:00:00" || timeStr == "0:00:00") return 0L
            parseTimeToMs(timeStr)
        } catch (_: Exception) { null }
    }

    fun parseTimeToMs(time: String): Long? {
        return try {
            val parts = time.split(":")
            if (parts.size != 3) return null
            val h = parts[0].trim().toLongOrNull() ?: return null
            val m = parts[1].trim().toLongOrNull() ?: return null
            val secParts = parts[2].split(".")
            val s = secParts[0].toLongOrNull() ?: return null
            val ms = if (secParts.size > 1) {
                val frac = secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                frac
            } else 0L
            ((h * 3600 + m * 60 + s) * 1000 + ms)
        } catch (_: Exception) { null }
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
