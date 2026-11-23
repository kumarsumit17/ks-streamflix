package com.ks.streamflix.server

import com.ks.streamflix.api.ItemKind
import com.ks.streamflix.api.LibraryKind
import com.ks.streamflix.server.domain.NameParser
import com.ks.streamflix.server.domain.ParsedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameParserTest {
    private fun movie(vararg path: String) = NameParser.parse(path.toList(), LibraryKind.MOVIES)
    private fun show(vararg path: String) = NameParser.parse(path.toList(), LibraryKind.SHOWS)

    @Test fun filmWithYearInBrackets() =
        assertEquals(ParsedName(ItemKind.MOVIE, "The Long Road", 2019), movie("The Long Road (2019).mkv"))

    @Test fun filmWithDotsAndReleaseTags() =
        assertEquals(ParsedName(ItemKind.MOVIE, "The Long Road", 2019), movie("The.Long.Road.2019.1080p.BluRay.x264.mkv"))

    @Test fun filmNamedByItsFolder() =
        assertEquals(ParsedName(ItemKind.MOVIE, "Quiet Hills", 2008), movie("Quiet Hills (2008)", "qh-1080p.mp4"))

    @Test fun titleThatIsAYearKeepsIt() =
        assertEquals(ParsedName(ItemKind.MOVIE, "2001 A Space Trip", 1968), movie("2001 A Space Trip (1968).mkv"))

    @Test fun titleStartingWithATagWord() =
        assertEquals(ParsedName(ItemKind.MOVIE, "DTS Test", 2025), movie("DTS Test (2025)", "DTS Test (2025).mkv"))

    @Test fun filmWithoutYear() =
        assertEquals(ParsedName(ItemKind.MOVIE, "Home Video"), movie("Home_Video.mp4"))

    @Test fun episodeInSeasonFolder() = assertEquals(
        ParsedName(ItemKind.EPISODE, "The Pilot", null, "Night Shift", 1, 2),
        show("Night Shift", "Season 01", "Night Shift S01E02 The Pilot.mkv"),
    )

    @Test fun episodeWithDotsAndTags() = assertEquals(
        ParsedName(ItemKind.EPISODE, "Night Shift S03E10", null, "Night Shift", 3, 10),
        show("Night.Shift.S03E10.720p.WEB-DL.mkv"),
    )

    @Test fun episodeCrossFormat() = assertEquals(
        ParsedName(ItemKind.EPISODE, "Rain", null, "Garden Tales", 2, 5),
        show("Garden Tales (2015)", "2x05 - Rain.mp4"),
    )

    @Test fun videoAndExtras() {
        assertTrue(NameParser.isVideo("a.MKV"))
        assertFalse(NameParser.isVideo("a.srt"))
        assertFalse(NameParser.isVideo("._a.mkv"))
        assertTrue(NameParser.isExtra(listOf("Film (2019)", "Sample", "f.mkv")))
        assertTrue(NameParser.isExtra(listOf("film-sample.mkv")))
        assertFalse(NameParser.isExtra(listOf("Sampled Lives (2020).mkv")))
    }
}
