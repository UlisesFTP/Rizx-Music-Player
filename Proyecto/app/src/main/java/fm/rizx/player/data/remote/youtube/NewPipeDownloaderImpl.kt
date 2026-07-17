package fm.rizx.player.data.remote.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * NewPipe [Downloader] backed by the shared [OkHttpClient] (ADR 0014). NewPipe invokes [execute]
 * synchronously on whatever thread runs the extractor; [fm.rizx.player.data.provider.YoutubeStreamingProvider]
 * always calls the extractor inside `withContext(io)`, so this never touches the main thread.
 *
 * A YouTube-like User-Agent is forced (the shared client's default `RizxPlayer/…` UA gets throttled).
 * HTTP 429 is translated to [ReCaptchaException], NewPipe's convention for "YouTube wants a challenge".
 */
class NewPipeDownloaderImpl(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val url = request.url()
        val dataToSend = request.dataToSend()
        val body = dataToSend?.toRequestBody(null, 0, dataToSend.size)

        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(url)
            .header("User-Agent", USER_AGENT)

        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (value in values) builder.addHeader(name, value)
        }

        val response = client.newCall(builder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }
        val responseBody = response.body?.string()
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBody,
            response.request.url.toString(),
        )
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +


                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
