package com.vidora.app

import com.vidora.app.account.GoogleSignInLinks as Links
import org.junit.Assert.*
import org.junit.Test

class GoogleSignInLinksTest {
    @Test fun accountHintIsEncodedNotAQueryInjection() {
        val url = Links.signIn(" user+test@example.com&continue=https://bad.invalid ")
        assertTrue(url.startsWith("https://accounts.google.com/ServiceLogin?"))
        assertTrue(url.contains("Email=user%2Btest%40example.com%26continue%3Dhttps%3A%2F%2Fbad.invalid"))
        assertEquals(1, Regex("&continue=").findAll(url).count())
    }

    @Test fun absentAccountStillOpensGoogle() {
        assertEquals(Links.signIn(null), Links.signIn(" "))
        assertFalse(Links.signIn(null).contains("login_hint="))
    }

    @Test fun recoveryIsOfficialAndSupportsChosenEmail() {
        assertEquals("https://accounts.google.com/signin/recovery", Links.recovery(null))
        assertEquals("https://accounts.google.com/signin/recovery?Email=a%2Bb%40gmail.com", Links.recovery("a+b@gmail.com"))
        assertEquals("accounts.google.com", Links.trustedHost(Links.FIND_EMAIL))
    }

    @Test fun trustedGoogleAndYoutubePagesAllowed() {
        assertEquals("accounts.google.com", Links.trustedHost(Links.signIn("me@gmail.com")))
        assertEquals("www.youtube.com", Links.trustedHost("https://www.youtube.com/signin"))
        assertEquals("accounts.youtube.com", Links.trustedHost("https://accounts.youtube.com/accounts/SetSID"))
        assertEquals("consent.google.com", Links.trustedHost("https://consent.google.com/"))
    }

    @Test fun phishingAndUnsafeSchemesRejected() {
        for (url in listOf(
            "https://accounts.google.com.bad.invalid/", "https://accounts.google.com@bad.invalid/",
            "https://bad.invalid@accounts.google.com/", "http://accounts.google.com/",
            "file:///sdcard/login.html", "intent://accounts.google.com/", "javascript:alert(1)",
            "https://accounts.google.com:444/", "not a url",
        )) assertNull(url, Links.trustedHost(url))
        assertNull(Links.trustedHost(null))
    }
}
