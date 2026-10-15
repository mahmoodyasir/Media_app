package com.mediaapp.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MediaPolicyTest {
    @Test
    fun readPermissionsFollowSdk() {
        assertEquals(
            listOf(MediaPermissions.READ_EXTERNAL_STORAGE),
            MediaPermissions.readPermissions(26).toList(),
        )
        assertEquals(
            listOf(MediaPermissions.READ_MEDIA_IMAGES, MediaPermissions.READ_MEDIA_VIDEO),
            MediaPermissions.readPermissions(33).toList(),
        )
        assertTrue(
            MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED in MediaPermissions.readPermissions(34).toList(),
        )
        assertTrue(MediaPermissions.writePermissions(28).isNotEmpty())
        assertTrue(MediaPermissions.writePermissions(29).isEmpty())
    }

    @Test
    fun accessLevelTreatsSelectedPhotosAsPartial() {
        assertEquals(
            AccessLevel.Partial,
            MediaPermissions.accessLevel(34, setOf(MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED)),
        )
        assertEquals(
            AccessLevel.Full,
            MediaPermissions.accessLevel(
                34,
                setOf(MediaPermissions.READ_MEDIA_IMAGES, MediaPermissions.READ_MEDIA_VIDEO),
            ),
        )
        assertEquals(AccessLevel.None, MediaPermissions.accessLevel(33, emptySet()))
        assertEquals(
            AccessLevel.Full,
            MediaPermissions.accessLevel(26, setOf(MediaPermissions.READ_EXTERNAL_STORAGE)),
        )
    }

    @Test
    fun dateTakenFallsBackToAddedThenModified() {
        assertEquals(1_700_000_000_000L, MediaDates.resolve(1_700_000_000_000L, 10, 20))
        assertEquals(10_000L, MediaDates.resolve(0, 10, 20))
        assertEquals(20_000L, MediaDates.resolve(0, 0, 20))
        assertEquals(0L, MediaDates.resolve(0, 0, 0))
    }

    @Test
    fun dayLabelsUseTodayAndYesterday() {
        val today = LocalDate.of(2026, 10, 15)
        assertEquals("Today", MediaDates.dayLabel(today, today))
        assertEquals("Yesterday", MediaDates.dayLabel(today.minusDays(1), today))
        val zone = ZoneId.of("UTC")
        val noon = LocalDate.of(2026, 10, 15).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 15), MediaDates.day(noon, zone))
    }

    @Test
    fun trashDaysRemainingUsesExpiryOrThirtyDayFallback() {
        val now = 40L * TrashPolicy.DAY_MS
        assertEquals(1, TrashPolicy.daysRemaining(now + TrashPolicy.DAY_MS, null, now))
        assertEquals(0, TrashPolicy.daysRemaining(now - 1, null, now))
        assertEquals(30, TrashPolicy.daysRemaining(null, now, now))
        assertEquals(29, TrashPolicy.daysRemaining(null, now - TrashPolicy.DAY_MS, now))
    }
}
