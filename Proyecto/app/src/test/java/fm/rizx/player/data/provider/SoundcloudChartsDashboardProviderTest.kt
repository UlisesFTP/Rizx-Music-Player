package fm.rizx.player.data.provider

import fm.rizx.player.data.remote.soundcloud.SoundcloudExtractorClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class SoundcloudChartsDashboardProviderTest {
    private fun item(index: Int) = StreamInfoItem(
        ServiceList.SoundCloud.serviceId,
        "https://soundcloud.com/rizx/track-$index",
        "Track $index",
        StreamType.AUDIO_STREAM,
    ).apply { duration = 180; uploaderName = "Artist $index" }

    private class FakeClient(
        private val rows: List<StreamInfoItem>,
        private val failure: Throwable? = null,
    ) : SoundcloudExtractorClient {
        var calls = 0
        var requested = 0
        override fun searchTracks(query: String, limit: Int) = emptyList<StreamInfoItem>()
        override fun streamInfo(trackUrl: String): StreamInfo = error("not used")
        override fun charts(kind: String, limit: Int): List<StreamInfoItem> {
            calls++
            requested = limit
            failure?.let { throw it }
            return rows.take(limit)
        }
    }

    @Test
    fun `valid New and hot response stays non empty limited and cached`() = runBlocking {
        val client = FakeClient((1..55).map(::item))
        val provider = SoundcloudChartsDashboardProvider(client, io = Dispatchers.Unconfined)

        val first = provider.topTracks(40)
        val second = provider.topTracks(40)

        assertTrue(first.isNotEmpty())
        assertEquals(40, first.size)
        assertEquals(50, client.requested)
        assertEquals(1, client.calls)
        assertEquals(first, second)
    }

    @Test(expected = IllegalStateException::class)
    fun `extractor failure propagates for repository isolation`() = runBlocking {
        SoundcloudChartsDashboardProvider(
            FakeClient(emptyList(), IllegalStateException("down")),
            io = Dispatchers.Unconfined,
        ).topTracks(40)
        Unit
    }
}
