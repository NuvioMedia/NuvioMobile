package com.nuvio.app.features.usenet

import org.junit.Assert.assertEquals
import org.junit.Test

class UsenetReleaseLabelsTest {
    private fun labels(title: String) = UsenetReleaseLabels.parse(title)

    @Test fun `audio names its codec, channels and Atmos`() {
        assertEquals("DD+ 5.1", labels("Tulsa.King.S01E01.2160p.AMZN.WEB-DL.DDP5.1.H.265-NTb").audio)
        assertEquals("DD+ 5.1 Atmos", labels("Movie.2020.2160p.WEB-DL.DDP5.1.Atmos.DV.H.265-GRP").audio)
        assertEquals("TrueHD 7.1 Atmos", labels("Movie.2020.2160p.BluRay.REMUX.HEVC.TrueHD.Atmos.7.1-GRP").audio)
        assertEquals("DTS-HD MA 7.1", labels("Movie.2020.1080p.BluRay.DTS-HD.MA.7.1.x264-GRP").audio)
        assertEquals("DTS:X", labels("Movie.2020.2160p.UHD.BluRay.REMUX.HDR.HEVC.DTS-X-GRP").audio)
        assertEquals("DD 5.1", labels("Movie.2020.1080p.WEB-DL.DD5.1.H.264-GRP").audio)
        assertEquals("AAC 2.0", labels("Movie.2020.1080p.WEBRip.AAC2.0.x264-GRP").audio)
        assertEquals(null, labels("Movie.2020.1080p.WEB.H264-GRP").audio)
        assertEquals("5.1", labels("Movie.2024.REPACK.720p.10bit.WEBRip.6CH.X265.HEVC-PSA").audio)
    }

    @Test fun `a web release names its service`() {
        assertEquals("Prime", labels("Tulsa.King.S01E01.2160p.AMZN.WEB-DL.DDP5.1.H.265-NTb").service)
        assertEquals("Max", labels("Show.S01E01.2160p.MAX.WEB-DL.DDP5.1.Atmos.DV.HEVC-GRP").service)
        assertEquals("Netflix", labels("Movie.2020.1080p.NF.WEB-DL.DDP5.1.H.264-GRP").service)
        assertEquals("iTunes", labels("Movie.2020.1080p.iT.WEB-DL.DD5.1.H.264-GRP").service)
    }

    @Test fun `words of the title are not taken for tags`() {
        assertEquals(null, labels("Mad.Max.Fury.Road.2015.2160p.UHD.BluRay.REMUX.HDR.HEVC.DTS-X-GRP").service)
        assertEquals(emptyList<String>(), labels("It.Ends.With.Us.2024.1080p.iT.WEB-DL.DD5.1.H.264-GRP").languages)
        assertEquals("2019.2160p.WEB-DL-GRP", UsenetReleaseLabels.tags("1917.2019.2160p.WEB-DL-GRP"))
    }

    @Test fun `the group is read without what reposts add after it`() {
        assertEquals("GRP", labels("Movie.2020.1080p-GRP").group)
        assertEquals("ROEN", labels("Dune.Part.Two.2024.BDRip.x264-ROEN-xpost").group)
        assertEquals("HOA", labels("Movie.2024.JAPANESE.BDRip.x264-HOA-xsp-").group)
        assertEquals("FraMeSToR", labels("Movie.2020.2160p.BluRay.REMUX.HEVC.TrueHD.Atmos.7.1-FraMeSToR.mkv").group)
        assertEquals("GalaxyRG", labels("Movie.2024.720p.WEBRip.900MB.x264-GalaxyRG[TGx]").group)
        assertEquals(null, labels("Movie.2020.1080p.WEB-DL").group)
        assertEquals(null, labels("a1b2c3d4e5f6").group)
    }

    @Test fun `languages are listed as codes`() {
        assertEquals(listOf("DE"), labels("Movie.2020.GERMAN.DL.1080p.BluRay.x264-GRP").languages)
        assertEquals(listOf("MULTI"), labels("Show.S02E05.MULTi.1080p.WEB.H264-GRP").languages)
        // A long list keeps the details on one line: two codes and how many more.
        val many = labels("Movie.2024.Eng.Fre.Ger.Ita.Spa.Cze.Pol.Jpn.2160p.BluRay.Remux.DV.HDR.HEVC.Atmos-GRP")
        assertEquals("EN+JA +5", UsenetReleaseLabels.languages(many.languages))
        assertEquals("Atmos", many.audio)
        assertEquals("DE+EN", UsenetReleaseLabels.languages(listOf("DE", "EN")))
        assertEquals(null, UsenetReleaseLabels.languages(emptyList()))
    }
}
