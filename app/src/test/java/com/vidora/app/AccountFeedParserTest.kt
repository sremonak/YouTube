package com.vidora.app

import com.vidora.app.account.AccountApi
import com.vidora.app.data.Entry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Signed-in feeds can't be fetched in CI, so the parser is checked against trimmed real-world shapes. */
class AccountFeedParserTest {
    private val api = AccountApi()
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private val home = """
    {"responseContext":{"mainAppWebResponseContext":{"loggedOut":false,"datasyncId":"123||"}},
     "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{
       "contents":[
         {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"dQw4w9WgXcQ","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
            "contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg"}]},
              "overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"3:33"}}]}}]}},
            "metadata":{"lockupMetadataViewModel":{"title":{"content":"Never Gonna Give You Up"},
              "metadata":{"contentMetadataViewModel":{"metadataRows":[
                {"metadataParts":[{"text":{"content":"Rick Astley"}}]},
                {"metadataParts":[{"text":{"content":"1.7B views"}},{"text":{"content":"15 years ago"}}]}]}}}}}}}},
         {"richItemRenderer":{"content":{"adSlotRenderer":{"fulfillmentContent":{"videoRenderer":{"videoId":"AAAAAAAAAAA","title":{"simpleText":"Ad"}}}}}}},
         {"richSectionRenderer":{"content":{"richShelfRenderer":{"contents":[
           {"richItemRenderer":{"content":{"shortsLockupViewModel":{"entityId":"shorts-shelf-item-abcdefghijk",
              "onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"abcdefghijk"}}},
              "thumbnail":{"sources":[{"url":"https://i.ytimg.com/vi/abcdefghijk/frame0.jpg"}]},
              "overlayMetadata":{"primaryText":{"content":"A short"},"secondaryText":{"content":"2.1M views"}}}}}}]}}}},
         {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"RDdQw4w9WgXcQ","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
            "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"dQw4w9WgXcQ","playlistId":"RDdQw4w9WgXcQ"}}}}},
            "metadata":{"lockupMetadataViewModel":{"title":{"content":"Mix – Rick Astley"}}}}}}},
         {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT_PAGE"}}}}
       ],
       "header":{"feedFilterChipBarRenderer":{"contents":[
         {"chipCloudChipRenderer":{"text":{"runs":[{"text":"All"}]},"isSelected":true}},
         {"chipCloudChipRenderer":{"text":{"runs":[{"text":"Music"}]},"navigationEndpoint":{"continuationCommand":{"token":"CHIP_MUSIC"}}}},
         {"chipCloudChipRenderer":{"text":{"runs":[{"text":"Mixes"}]},"navigationEndpoint":{"continuationCommand":{"token":"CHIP_MIXES"}}}}
       ]}}
     }}}}]}}}
    """.trimIndent()

    @Test fun homeFeedParsesVideosShortsMixesAndSkipsAds() {
        val page = api.toPage(obj(home))
        val videos = page.items.filterIsInstance<Entry.Video>().map { it.video }
        assertEquals(listOf("Never Gonna Give You Up", "A short"), videos.map { it.title })
        assertEquals(213L, videos[0].duration)
        assertEquals("Rick Astley", videos[0].uploader)
        assertTrue(videos[1].isShort)
        val mix = page.items.filterIsInstance<Entry.Playlist>().single().playlist
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ", mix.url)
        assertNotNull("next page token must come from the feed, not a chip", page.next)
    }

    @Test fun homeChipsAreYouTubesOwn() {
        val chips = api.collectChips(obj(home))
        assertEquals(listOf("All", "Music", "Mixes"), chips.map { it.label })
        assertEquals(null, chips[0].token)
        assertEquals("CHIP_MUSIC", chips[1].token)
    }

    @Test fun emptyFeedReportsYouTubesReason() {
        val res = obj("""{"contents":{"feedNudgeRenderer":{"title":{"runs":[{"text":"Your watch history is off"}]},
            "subtitle":{"runs":[{"text":"You can change this setting at any time"}]}}}}""")
        try {
            api.toPage(res); fail("expected the YouTube message")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("Your watch history is off"))
        }
    }

    @Test fun musicHomeParses() {
        val res = obj("""{"contents":{"sectionListRenderer":{"contents":[{"musicCarouselShelfRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"dQw4w9WgXcQ"},
               "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Never Gonna Give You Up"}]}}},
                              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Rick Astley"},{"text":" • "},{"text":"Whenever You Need Somebody"}]}}}]}},
            {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"My Supermix"}]},"subtitle":{"runs":[{"text":"Playlist"}]},
               "navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDTMAK5uy_kset8DisdE7LSD4TNjEVvrKRTmG7a56sY"}}}}
          ]}}],"continuations":[{"nextContinuationData":{"continuation":"MUSIC_NEXT"}}]}}}""")
        val page = api.toPage(res, music = true)
        val v = page.items.filterIsInstance<Entry.Video>().single().video
        assertEquals("Never Gonna Give You Up", v.title)
        assertEquals("Rick Astley", v.uploader)
        assertEquals("https://www.youtube.com/playlist?list=RDTMAK5uy_kset8DisdE7LSD4TNjEVvrKRTmG7a56sY",
            page.items.filterIsInstance<Entry.Playlist>().single().playlist.url)
        assertNotNull(page.next)
    }

    @Test fun historyItemHasProgressAndYouTubeMenuActions() {
        val res = obj("""{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
          {"videoRenderer":{"videoId":"dQw4w9WgXcQ","title":{"runs":[{"text":"Never Gonna Give You Up"}]},
            "lengthText":{"simpleText":"3:33"},
            "thumbnailOverlays":[{"thumbnailOverlayResumePlaybackRenderer":{"percentDurationWatched":42}}],
            "menu":{"menuRenderer":{"items":[
              {"menuServiceItemRenderer":{"text":{"runs":[{"text":"Remove from watch history"}]},"icon":{"iconType":"DELETE"},
                "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"TOKEN_REMOVE"}}}},
              {"menuServiceItemRenderer":{"text":{"runs":[{"text":"Share"}]},"serviceEndpoint":{"shareEntityServiceEndpoint":{}}}}
            ]}}}}]}}]}}}""")
        val v = api.toPage(res).items.filterIsInstance<Entry.Video>().single().video
        assertEquals(42, v.watchedPercent)
        assertEquals(listOf("Remove from watch history"), v.feedback.map { it.label })
        assertEquals("TOKEN_REMOVE", v.feedback[0].token)
        assertEquals("DELETE", v.feedback[0].icon)
    }
}
