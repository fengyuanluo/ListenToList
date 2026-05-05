package com.kutedev.easemusicplayer

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MainActivityIntentTest {
    @Test
    fun extractOAuthRedirectCode_acceptsExpectedDeepLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("easem://oauth2redirect/?code=abc123"))

        assertEquals("abc123", extractOAuthRedirectCode(intent))
    }

    @Test
    fun extractOAuthRedirectCode_rejectsMissingBlankOrUnexpectedCodes() {
        assertNull(extractOAuthRedirectCode(Intent(Intent.ACTION_VIEW, Uri.parse("easem://oauth2redirect/"))))
        assertNull(extractOAuthRedirectCode(Intent(Intent.ACTION_VIEW, Uri.parse("easem://oauth2redirect/?code="))))
        assertNull(extractOAuthRedirectCode(Intent(Intent.ACTION_VIEW, Uri.parse("easem://other/?code=abc123"))))
        assertNull(extractOAuthRedirectCode(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/?code=abc123"))))
        assertNull(extractOAuthRedirectCode(null))
    }
}
