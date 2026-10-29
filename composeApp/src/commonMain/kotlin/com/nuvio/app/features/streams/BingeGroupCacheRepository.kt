package com.nuvio.app.features.streams

object BingeGroupCacheRepository {

    fun save(contentId: String, bingeGroup: String, filename: String? = null, infoHash: String? = null) {
        BingeGroupCacheStorage.save(hashedKey(contentId), bingeGroup)
        val fn = filename?.takeIf { it.isNotBlank() }
        if (fn != null) {
            BingeGroupCacheStorage.save(filenameHashedKey(contentId), fn)
        } else {
            BingeGroupCacheStorage.remove(filenameHashedKey(contentId))
        }
        val ih = infoHash?.takeIf { it.isNotBlank() }
        if (ih != null) {
            BingeGroupCacheStorage.save(infoHashHashedKey(contentId), ih)
        } else {
            BingeGroupCacheStorage.remove(infoHashHashedKey(contentId))
        }
    }

    fun get(contentId: String): String? {
        return BingeGroupCacheStorage.load(hashedKey(contentId))
    }

    fun getFilename(contentId: String): String? {
        return BingeGroupCacheStorage.load(filenameHashedKey(contentId))
    }

    fun getInfoHash(contentId: String): String? {
        return BingeGroupCacheStorage.load(infoHashHashedKey(contentId))
    }

    fun remove(contentId: String) {
        BingeGroupCacheStorage.remove(hashedKey(contentId))
        BingeGroupCacheStorage.remove(filenameHashedKey(contentId))
        BingeGroupCacheStorage.remove(infoHashHashedKey(contentId))
    }

    private fun hashedKey(contentId: String): String {
        val hash = contentId.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()
        return "binge_group_$hash"
    }

    private fun filenameHashedKey(contentId: String): String {
        val hash = contentId.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()
        return "binge_group_fn_$hash"
    }

    private fun infoHashHashedKey(contentId: String): String {
        val hash = contentId.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()
        return "binge_group_ih_$hash"
    }
}
