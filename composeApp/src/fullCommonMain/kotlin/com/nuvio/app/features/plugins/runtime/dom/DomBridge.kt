package com.nuvio.app.features.plugins.runtime.dom

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.select.Elements
import com.nuvio.app.features.plugins.runtime.host.HostModule
import kotlin.random.Random

internal class DomBridge : HostModule {
    private val documentCache = mutableMapOf<String, Document>()
    private val elementCache = mutableMapOf<String, Element>()
    private val loadedDocIds = mutableListOf<String>()
    private var idCounter = 0
    private val containsRegex = Regex(""":contains\([\"']([^\"']+)[\"']\)""")

    override fun register(runtime: QuickJs) {
        runtime.function("__cheerio_load") { args ->
            val html = args.getOrNull(0)?.toString() ?: ""
            val docId = "doc_${idCounter++}_${Random.nextInt(0, Int.MAX_VALUE)}"
            documentCache[docId] = Ksoup.parse(html)
            loadedDocIds.add(docId)
            evictOldestDocumentsIfNeeded()
            docId
        }

        runtime.function("__cheerio_select") { args ->
            val docId = args.getOrNull(0)?.toString() ?: ""
            var selector = args.getOrNull(1)?.toString() ?: ""
            val doc = documentCache[docId] ?: return@function "[]"
            try {
                selector = selector.replace(containsRegex, ":contains($1)")
                val elements = if (selector.isEmpty()) Elements() else doc.select(selector)
                val ids = elements.mapIndexed { index, el ->
                    val id = "$docId:$index:${el.hashCode()}"
                    elementCache[id] = el
                    id
                }
                "[" + ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
            } catch (_: Exception) {
                "[]"
            }
        }

        runtime.function("__cheerio_find") { args ->
            val docId = args.getOrNull(0)?.toString() ?: ""
            val elementId = args.getOrNull(1)?.toString() ?: ""
            var selector = args.getOrNull(2)?.toString() ?: ""
            val element = elementCache[elementId] ?: return@function "[]"
            try {
                selector = selector.replace(containsRegex, ":contains($1)")
                val elements = element.select(selector)
                val ids = elements.mapIndexed { index, el ->
                    val id = "$docId:find:$index:${el.hashCode()}"
                    elementCache[id] = el
                    id
                }
                "[" + ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
            } catch (_: Exception) {
                "[]"
            }
        }

        runtime.function("__cheerio_text") { args ->
            val elementIds = args.getOrNull(1)?.toString() ?: ""
            elementIds.split(",")
                .filter { it.isNotEmpty() }
                .mapNotNull { elementCache[it]?.text() }
                .joinToString(" ")
        }

        runtime.function("__cheerio_html") { args ->
            val docId = args.getOrNull(0)?.toString() ?: ""
            val elementId = args.getOrNull(1)?.toString() ?: ""
            if (elementId.isEmpty()) {
                documentCache[docId]?.html() ?: ""
            } else {
                elementCache[elementId]?.html() ?: ""
            }
        }

        runtime.function("__cheerio_inner_html") { args ->
            val elementId = args.getOrNull(1)?.toString() ?: ""
            elementCache[elementId]?.html() ?: ""
        }

        runtime.function("__cheerio_attr") { args ->
            val elementId = args.getOrNull(1)?.toString() ?: ""
            val attrName = args.getOrNull(2)?.toString() ?: ""
            val value = elementCache[elementId]?.attr(attrName)
            if (value.isNullOrEmpty()) "__UNDEFINED__" else value
        }

        runtime.function("__cheerio_next") { args ->
            val docId = args.getOrNull(0)?.toString() ?: ""
            val elementId = args.getOrNull(1)?.toString() ?: ""
            val element = elementCache[elementId] ?: return@function "__NONE__"
            val next = element.nextElementSibling() ?: return@function "__NONE__"
            val nextId = "$docId:next:${next.hashCode()}"
            elementCache[nextId] = next
            nextId
        }

        runtime.function("__cheerio_prev") { args ->
            val docId = args.getOrNull(0)?.toString() ?: ""
            val elementId = args.getOrNull(1)?.toString() ?: ""
            val element = elementCache[elementId] ?: return@function "__NONE__"
            val prev = element.previousElementSibling() ?: return@function "__NONE__"
            val prevId = "$docId:prev:${prev.hashCode()}"
            elementCache[prevId] = prev
            prevId
        }
    }

    fun clear() {
        documentCache.clear()
        elementCache.clear()
        loadedDocIds.clear()
    }

    private fun evictOldestDocumentsIfNeeded() {
        while (loadedDocIds.size > MAX_CACHED_DOCUMENTS) {
            val evictedId = loadedDocIds.removeAt(0)
            documentCache.remove(evictedId)
            val staleKeys = elementCache.keys.filter { it.startsWith("$evictedId:") }
            staleKeys.forEach { elementCache.remove(it) }
        }
    }

    private companion object {
        const val MAX_CACHED_DOCUMENTS = 8
    }
}
