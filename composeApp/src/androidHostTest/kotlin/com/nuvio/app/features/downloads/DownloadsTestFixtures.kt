package com.nuvio.app.features.downloads

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment

internal val SAF_MOVIES_URI: Uri =
    Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies")

internal val SAF_NESTED_URI: Uri =
    Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FNuvio")

internal fun registerFakeDocumentsProvider(
    treeUri: Uri = SAF_MOVIES_URI,
): FakeDocumentsProvider =
    Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, treeUri.authority)

internal fun safDocumentUri(documentId: String, treeUri: Uri = SAF_MOVIES_URI): Uri =
    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

internal fun setupSafDownloads(
    context: Application = RuntimeEnvironment.getApplication(),
    appendSupported: Boolean = true,
    treeUri: Uri = SAF_MOVIES_URI,
): FakeDocumentsProvider {
    DownloadLocationManager.initialize(context)
    val provider = registerFakeDocumentsProvider(treeUri)
    provider.appendSupported = appendSupported
    DownloadLocationManager.onFolderPicked(treeUri)
    return provider
}
