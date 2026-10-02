package lib.networks

import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * Singleton provider for [OkHttpClient].
 *
 * Ensures all network calls in the app share a single client instance.
 * Advantages of using a singleton [OkHttpClient]:
 * 1. **Connection reuse**: Keeps TCP connections alive, reducing handshake overhead.
 * 2. **Reduced latency**: Reuses connections instead of creating new ones for each request.
 * 3. **Resource management**: Limits concurrent requests and connections per host.
 *
 * Configuration details:
 * - Automatically follows HTTP and HTTPS redirects.
 * - Supports HTTP/2 and HTTP/1.1 protocols.
 * - Connection and read timeouts tuned for responsiveness.
 * - ConnectionPool keeps idle connections alive.
 * - Dispatcher controls max concurrent requests globally and per host.
 */
object HttpClientProvider {

	private val logger = LogHelperUtils.from(javaClass)

	/** Lazily initialized OkHttpClient singleton */
	val okHttpClient: OkHttpClient by lazy {
		logger.d("Initializing OkHttpClient singleton")
		OkHttpClient.Builder()
			// Follow redirects for HTTP and HTTPS
			.followRedirects(true)
			.followSslRedirects(true)

			// Enable HTTP/2 and HTTP/1.1 protocols
			.protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

			// Max time to establish TCP connection
			.connectTimeout(5, TimeUnit.SECONDS)

			// Max time waiting for server response
			.readTimeout(10, TimeUnit.SECONDS)

			// Connection pool: 20 idle connections, kept for 5 minutes
			.connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))

			// Dispatcher to control concurrency
			.dispatcher(Dispatcher().apply {
				maxRequests = 64             // Max concurrent requests globally
				maxRequestsPerHost = 16      // Max concurrent requests per host
				logger.d(
					"Dispatcher configured: maxRequests=$maxRequests," +
							" maxRequestsPerHost=$maxRequestsPerHost"
				)
			})
			.build()
			.also { logger.d("OkHttpClient built successfully") }
	}

	/**
	 * Pre-initializes the OkHttpClient in a background thread.
	 *
	 * This can reduce latency for the first network request.
	 */
	@JvmStatic
	fun initialize() {
		logger.d("Initializing OkHttpClient in background thread")
		ThreadsUtility.executeInBackground(codeBlock = {
			okHttpClient
			logger.d("OkHttpClient pre-initialization completed")
		})
	}
}
