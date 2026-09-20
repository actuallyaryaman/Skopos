package org.a4real.skopos.runtime

import android.net.Uri
import android.provider.ContactsContract
import org.a4real.skopos.core.ScopeFamily

/**
 * Maps a ContactsProvider query Uri to the [ScopeFamily] whose rows it reads.
 *
 * The ContactScope domain in core is provider-agnostic; this is the android-side translation
 * boundary. Anything that is not a contacts-data query (non-contacts authorities, the profile
 * tree, groups/settings/directories, stream items) resolves to `null`, which the interceptor
 * treats as "leave untouched".
 */
object UriClassifier {

    fun classify(uri: Uri): ScopeFamily? {
        if (uri.authority != ContactsContract.AUTHORITY) return null
        val segments = uri.pathSegments?.filter { it.isNotEmpty() } ?: return null
        return ScopeFamily.fromPathSegments(segments)
    }
}