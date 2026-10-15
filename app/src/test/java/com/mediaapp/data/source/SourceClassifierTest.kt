package com.mediaapp.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceClassifierTest {
    private val classifier = SourceClassifier()

    @Test
    fun classifiesKnownSourcesFromPathAndBucket() {
        val cases = listOf(
            input("DCIM/Camera/", "Camera", "IMG_001.jpg") to "camera",
            input("DCIM/100MEDIA/", "100MEDIA", "DSC_1.jpg") to "camera",
            input(null, "Camera", "IMG_9.jpg") to "camera",
            input("DCIM/Screenshots/", "Screenshots", "Screenshot_1.png") to "screenshots",
            input("Pictures/Screenshots/", "Screenshots", "shot.png") to "screenshots",
            input("DCIM/ScreenRecorder/", "ScreenRecorder", "Screenrecorder-1.mp4") to "screen_recordings",
            input("Movies/Screen recordings/", "Screen recordings", "rec.mp4") to "screen_recordings",
            input("Download/", "Download", "browser.mp4") to "downloads",
            input("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "WhatsApp Images", "a.jpg") to "whatsapp",
            input("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/", "WhatsApp Video", "a.mp4") to "whatsapp",
            input("Android/media/com.whatsapp/WhatsApp/Media/.Statuses/", ".Statuses", "status.jpg") to "whatsapp",
            input("Android/media/com.whatsapp.w4b/WhatsApp Business/", "WhatsApp", "a.jpg") to "whatsapp",
            input("Android/media/com.facebook.katana/Facebook/", "Facebook", "a.jpg") to "facebook",
            input("Pictures/Facebook/", "Facebook", "a.jpg") to "facebook",
            input("Android/media/com.facebook.orca/Messenger/", "Messenger", "a.jpg") to "messenger",
            input("Pictures/Messenger/", "Messenger", "a.jpg") to "messenger",
            input("Android/media/com.instagram.android/Instagram/", "Instagram", "a.jpg") to "instagram",
            input("Android/media/org.telegram.messenger/Telegram/Telegram Images/", "Telegram", "a.jpg") to "telegram",
            input("Android/media/com.snapchat.android/Snapchat/", "Snapchat", "a.jpg") to "snapchat",
            input("Android/media/com.zhiliaoapp.musically/", "TikTok", "clip.mp4") to "tiktok",
            input("Pictures/Twitter/", "Twitter", "a.jpg") to "twitter",
            input("Pictures/X/", "X", "a.jpg") to "twitter",
            input("Download/Bluetooth/", "Bluetooth", "file.jpg") to "bluetooth",
            input("Bluetooth/", "Bluetooth", "file.jpg") to "bluetooth",
            input("Pictures/Edited/", "Edited", "out.jpg") to "edited",
        )
        cases.forEach { (sample, expected) ->
            val actual = classifier.classify(sample)
            assertEquals(sample.toString(), expected, actual.id)
            assertTrue(sample.toString(), actual.known)
        }
    }

    @Test
    fun filenameRulesBeatCameraAndDownloads() {
        val screenshot = classifier.classify(input("DCIM/Camera/", "Camera", "Screenshot_20241001.png"))
        assertEquals("screenshots", screenshot.id)

        val edited = classifier.classify(input("DCIM/Camera/", "Camera", "IMG_001_edited.jpg"))
        assertEquals("edited", edited.id)

        val recording = classifier.classify(input("Download/", "Download", "Screenrecorder-2024.mp4"))
        assertEquals("screen_recordings", recording.id)
    }

    @Test
    fun messengerIsNotFacebook() {
        val messenger = classifier.classify(
            input("Android/media/com.facebook.orca/Messenger/", "Messenger", "a.jpg"),
        )
        assertEquals("messenger", messenger.id)
        assertEquals("Messenger", messenger.label)
    }

    @Test
    fun unknownBucketsBecomeTheirOwnFolders() {
        val cats = classifier.classify(input("Pictures/Cats/", "Cats", "a.jpg"))
        val dogs = classifier.classify(input("Pictures/Dogs/", "Dogs", "b.jpg"))
        assertNotEquals(cats.id, dogs.id)
        assertEquals("Cats", cats.label)
        assertEquals("Dogs", dogs.label)
        assertFalse(cats.known)
        assertFalse(dogs.known)
        assertTrue(cats.id.startsWith("bucket:"))
    }

    @Test
    fun unknownNamesAreHumanized() {
        val trip = classifier.classify(input(null, "summer_trip", "a.jpg"))
        assertEquals("Summer Trip", trip.label)
        assertEquals("bucket:summer_trip", trip.id)

        val album = classifier.classify(input("Pictures/My Album/", "My Album", "a.jpg"))
        assertEquals("My Album", album.label)
    }

    @Test
    fun blankInputFallsBackToOther() {
        val other = classifier.classify(input(null, null, ""))
        assertEquals("bucket:other", other.id)
        assertEquals("Other", other.label)
        assertFalse(other.known)
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val camera = classifier.classify(input("dcim\\camera\\", "CAMERA", "img.JPG"))
        assertEquals("camera", camera.id)
    }

    private fun input(path: String?, bucket: String?, name: String) =
        ClassificationInput(relativePath = path, bucketName = bucket, displayName = name)
}
