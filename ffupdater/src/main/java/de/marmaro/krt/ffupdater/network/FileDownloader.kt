package de.marmaro.krt.ffupdater.network

import android.net.TrafficStats
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import de.marmaro.krt.ffupdater.network.exceptions.ApiRateLimitExceededException
import de.marmaro.krt.ffupdater.network.exceptions.NetworkException
import de.marmaro.krt.ffupdater.settings.NetworkSettingsHelper
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import ru.gildor.coroutines.okhttp.await
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KMutableProperty0


class FileDownloader(private val networkSettingsHelper: NetworkSettingsHelper) {
    private val trafficStatsThreadId = 10001

    var onProgress: (progressInPercent: Int?, totalMB: Long) -> Unit = @WorkerThread { _, _ -> }

    // fallback to wait for the download when the activity was recreated
    var currentDownload: Deferred<Any>? = null

    // It is safe to reuse the OkHttpClient -> Don't recreate it every callUrl() execution
    private val okHttpClient = createClient()

    @MainThread
    @Throws(NetworkException::class)
    suspend fun downloadBigFileAsync(url: String, file: File): Deferred<Any> {
        return withContext(Dispatchers.IO) {
            currentDownload = async {
                try {
                    lastChange = System.currentTimeMillis()
                    numberOfRunningDownloads.incrementAndGet()
                    downloadBigFileInternal(url, file)
                } catch (e: IOException) {
                    throw NetworkException("Download of $url failed.", e)
                } catch (e: IllegalArgumentException) {
                    throw NetworkException("Download of $url failed.", e)
                } catch (e: NetworkException) {
                    throw NetworkException("Download of $url failed.", e)
                } finally {
                    lastChange = System.currentTimeMillis()
                    numberOfRunningDownloads.decrementAndGet()
                }
            }
            currentDownload!!
        }
    }

    @WorkerThread
    private suspend fun downloadBigFileInternal(url: String, file: File) {
        callUrl(url).use { response ->
            val body = validateAndReturnResponseBody(url, response)
            if (file.exists()) {
                file.delete()
            }
            file.outputStream().buffered().use { fileWriter ->
                body.byteStream().buffered().use { responseReader ->
                    // this method blocks until download is finished
                    responseReader.copyTo(fileWriter)
                }
            }
        }
    }

    /**
     * onProgress and currentDownload will stay null because the download is small.
     */
    @MainThread
    @Throws(NetworkException::class)
    suspend fun downloadSmallFileAsync(url: String): Deferred<String> {
        return withContext(Dispatchers.IO) {
            async {
                try {
                    downloadSmallFileInternal(url)
                } catch (e: IOException) {
                    throw NetworkException("Request of HTTP-API $url failed.", e)
                } catch (e: IllegalArgumentException) {
                    throw NetworkException("Request of HTTP-API $url failed.", e)
                } catch (e: NetworkException) {
                    throw NetworkException("Request of HTTP-API $url failed.", e)
                }
            }
        }
    }

    @WorkerThread
    private suspend fun downloadSmallFileInternal(url: String): String {
        callUrl(url).use { response ->
            val body = validateAndReturnResponseBody(url, response)
            return body.string()
        }
    }

    private fun validateAndReturnResponseBody(url: String, response: Response): ResponseBody {
        if (url.startsWith(GITHUB_URL) && response.code == 403) {
            throw ApiRateLimitExceededException(
                "API rate limit for GitHub is exceeded.",
                Exception("response code is ${response.code}")
            )
        }
        if (!response.isSuccessful) {
            throw NetworkException("Response is unsuccessful. HTTP code: '${response.code}'.")
        }
        return response.body ?: throw NetworkException("Response is unsuccessful. Body is null.")
    }

    private suspend fun callUrl(url: String): Response {
        require(url.startsWith("https://"))
        TrafficStats.setThreadStatsTag(trafficStatsThreadId)
        val request = Request.Builder()
            .url(url)
            .build()
        val call = okHttpClient.newCall(request)
        return call.await()
    }

    private fun createClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()

        builder.addNetworkInterceptor { chain: Interceptor.Chain ->
            val original = chain.proceed(chain.request())
            val body = requireNotNull(original.body)
            original.newBuilder()
                .body(ProgressResponseBody(body, this::onProgress))
                .build()
        }

        networkSettingsHelper.createSslSocketFactory()?.let {
            builder.sslSocketFactory(it.first, it.second)
        }
        networkSettingsHelper.createDnsConfiguration()?.let {
            builder.dns(it)
        }
        networkSettingsHelper.createProxyConfiguration()?.let {
            builder.proxy(it)
        }
        networkSettingsHelper.createProxyAuthenticatorConfiguration()?.let {
            builder.proxyAuthenticator(it)
        }

        return builder.build()
    }

    // simple communication between WorkManager and the InstallActivity to prevent duplicated downloads
    // persistence/consistence is not very important -> global available variables are ok
    companion object {
        private var numberOfRunningDownloads = AtomicInteger(0)
        private var lastChange = System.currentTimeMillis()
        fun areDownloadsCurrentlyRunning() = (numberOfRunningDownloads.get() != 0) &&
                ((System.currentTimeMillis() - lastChange) < 3600_000)

        const val GITHUB_URL = "https://api.github.com"
    }
}

internal class ProgressResponseBody(
    private val responseBody: ResponseBody,
    private var onProgress: KMutableProperty0<(progressInPercent: Int?, totalMB: Long) -> Unit>
) : ResponseBody() {
    override fun contentType() = responseBody.contentType()
    override fun contentLength() = responseBody.contentLength()
    override fun source() = trackTransmittedBytes(responseBody.source()).buffer()

    private fun trackTransmittedBytes(source: Source): Source {
        return object : ForwardingSource(source) {
            private val sourceIsExhausted = -1L
            var totalBytesRead = 0L
            var totalProgress = -1
            var totalMB = 0L

            @Throws(IOException::class)
            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                if (bytesRead != sourceIsExhausted) {
                    totalBytesRead += bytesRead
                }
                if (contentLength() > 0L) {
                    val progress = (100 * totalBytesRead / contentLength()).toInt()
                    if (progress != totalProgress) {
                        totalProgress = progress
                        val totalMegabytesRead = totalBytesRead / 1_048_576
                        onProgress.get().invoke(progress, totalMegabytesRead)
                    }
                } else {
                    val totalMegabytesRead = totalBytesRead / 1_048_576
                    if (totalMegabytesRead != totalMB) {
                        totalMB = totalMegabytesRead
                        onProgress.get().invoke(null, totalMegabytesRead)
                    }
                }
                return bytesRead
            }
        }
    }
}
