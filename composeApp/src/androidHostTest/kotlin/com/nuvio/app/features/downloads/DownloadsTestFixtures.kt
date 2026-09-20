package com.nuvio.app.features.downloads

import android.net.Uri
import android.provider.DocumentsContract
import org.robolectric.Robolectric

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
