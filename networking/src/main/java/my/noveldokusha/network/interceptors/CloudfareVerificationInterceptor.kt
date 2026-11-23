package my.noveldokusha.network.interceptors

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.noveldokusha.core.domain.BackgroundWorkTracker
import my.noveldokusha.core.domain.CloudfareVerificationBypassFailedException
import my.noveldokusha.core.domain.WebViewCookieManagerInitializationFailedException
import android.content.Intent
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds

private val ERROR_CODES = listOf(
    HttpsURLConnection.HTTP_FORBIDDEN /*403*/,
    HttpsURLConnection.HTTP_UNAVAILABLE /*503*/
)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")

private const val TAG = "CloudflareInterceptor"

/**
 * If a CloudFare security verification redirection is detected, execute a
 * webView and retrieve the necessary headers.
 */
internal class CloudFareVerificationInterceptor(
    @ApplicationContext private val appContext: Context
) : Interceptor {

    private val lock = ReentrantLock()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (isNotCloudFare(response)) {
            return response
        }

        // While background work (library updates, batch downloads,
        // restores) is running, never launch the verification
        // WebView: it would interrupt the user's reading with a
        // captcha for a source they are not using. Fail the request
        // instead — the challenge is shown when the user opens that
        // source himself.
        if (BackgroundWorkTracker.isActive) {
            Log.d(TAG, "Cloudflare wall hit during background work; failing without WebView: ${request.url}")
            response.close()
            throw CloudfareVerificationBypassFailedException()
        }

        return lock.withLock {
            try {
                val cookieManager = CookieManager.getInstance()
                    ?: throw WebViewCookieManagerInitializationFailedException()

                response.close()
                // Remove old cf_clearance from the cookie
                val cookie = cookieManager
                    .getCookie(request.url.toString())
                    ?.splitToSequence(";")
                    ?.map { it.split("=").map(String::trim) }
                    ?.filter { it[0] != "cf_clearance" }
                    ?.joinToString(";") { it.joinToString("=") }

                cookieManager.setCookie(request.url.toString(), cookie)

                runBlocking(Dispatchers.IO) {
                    resolveWithWebView(request, cookieManager, forceChallenge = false)
                }

                // Get the cookies and add them to the request
                val cookies = cookieManager.getCookie(request.url.toString()) ?: ""
                Log.d(TAG, "Full cookies for retry: $cookies")
                Log.d(TAG, "cf_clearance present: ${cookies.contains("cf_clearance")}")
                
                // Get the User-Agent that WebView used (must match for Cloudflare)
                val webViewUserAgent = WebSettings.getDefaultUserAgent(appContext)
                Log.d(TAG, "Using WebView User-Agent: $webViewUserAgent")
                
                val newRequest = request.newBuilder()
                    .header("Cookie", cookies)
                    .header("User-Agent", webViewUserAgent)
                    .build()

                val responseCloudfare = chain.proceed(newRequest)
                Log.d(TAG, "Retry response: code=${responseCloudfare.code}, server=${responseCloudfare.header("Server")}")

                if (!isNotCloudFare(responseCloudfare)) {
                    Log.w(TAG, "Retry still blocked by Cloudflare after cookie set - forcing fresh challenge")
                    responseCloudfare.close()
                    
                    // Clear ALL cookies for this domain to ensure fresh start
                    val domain = request.url.host
                    Log.d(TAG, "Clearing ALL cookies for domain: $domain")
                    
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                    
                    // Additional aggressive clear - remove session cookies
                    cookieManager.removeSessionCookies(null)
                    cookieManager.flush()
                    
                    // Wait a bit for cookies to be fully cleared
                    Thread.sleep(500)
                    
                    Log.d(TAG, "Cookies cleared, launching WebView for fresh challenge")
                    runBlocking(Dispatchers.IO) {
                        resolveWithWebView(request, cookieManager, forceChallenge = true)
                    }
                    
                    // Retry one more time with fresh cookie
                    val freshCookies = cookieManager.getCookie(request.url.toString()) ?: ""
                    Log.d(TAG, "Fresh cookies after challenge: cf_clearance present=${freshCookies.contains("cf_clearance")}")
                    
                    val finalRequest = request.newBuilder()
                        .header("Cookie", freshCookies)
                        .header("User-Agent", webViewUserAgent)
                        .build()
                    
                    val finalResponse = chain.proceed(finalRequest)
                    Log.d(TAG, "Final retry response: code=${finalResponse.code}")
                    
                    if (!isNotCloudFare(finalResponse)) {
                        Log.e(TAG, "Still blocked after fresh challenge - giving up")
                        throw CloudfareVerificationBypassFailedException()
                    }
                    
                    return@withLock finalResponse
                } else {
                    Log.d(TAG, "Successfully bypassed Cloudflare!")
                }

                responseCloudfare
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e.message, e.cause)
            }
        }
    }

    private fun isNotCloudFare(response: Response): Boolean {
        return response.code !in ERROR_CODES ||
                response.header("Server") !in SERVER_CHECK
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(
        request: Request,
        cookieManager: CookieManager,
        forceChallenge: Boolean = false
    ): Unit = withContext(Dispatchers.Default) {
        val url = request.url.toString()
        val domain = request.url.host

        Log.d(TAG, "Starting Cloudflare challenge resolution for: $url")
        Log.d(TAG, "Domain: $domain")

        // Check if we already have a valid cf_clearance cookie (unless forced)
        if (!forceChallenge) {
            cookieManager.flush()
            val existingCookies = cookieManager.getCookie(url) ?: ""
            
            if (existingCookies.contains("cf_clearance")) {
                Log.d(TAG, "cf_clearance cookie already exists, no WebView needed")
                return@withContext
            }
        } else {
            Log.d(TAG, "Forcing fresh challenge (existing cookie was invalid)")
        }

        // Launch WebView for manual challenge
        Log.d(TAG, "Launching WebView for manual challenge")
        withContext(Dispatchers.Main) {
            val intent = Intent().apply {
                setClassName(appContext, "my.noveldokusha.webview.WebViewActivity")
                putExtra("url", url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        }

        // Wait for the user to solve the challenge
        val maxAttempts = 120 // 2 minutes timeout
        var attempts = 0
        var challengeSolved = false
        
        // Store the initial cookie state to detect NEW cookies
        cookieManager.flush()
        val initialCookies = cookieManager.getCookie(url) ?: ""
        val hadClearanceInitially = initialCookies.contains("cf_clearance")
        
        // If forcing challenge, we expect the cookie to change
        val minWaitTime = if (forceChallenge && hadClearanceInitially) 3 else 1
        
        Log.d(TAG, "Waiting for challenge resolution (minWait=${minWaitTime}s, hadClearance=$hadClearanceInitially)")
        
        while (!challengeSolved && attempts < maxAttempts) {
            delay(1.seconds)
            attempts++
            
            // Flush cookies to ensure they're written
            cookieManager.flush()
            
            // Get ALL cookies from the page
            val allCookies = cookieManager.getCookie(url) ?: ""
            
            // Log cookies every 10 seconds for debugging
            if (attempts % 10 == 0) {
                Log.d(TAG, "Attempt $attempts/$maxAttempts - Waiting for cf_clearance...")
            }
            
            // Only accept cookie after minimum wait time
            // This prevents accepting old cookies that weren't properly cleared
            if (attempts >= minWaitTime && allCookies.contains("cf_clearance")) {
                // If we're forcing a challenge and had a cookie initially,
                // verify the cookie value changed
                if (forceChallenge && hadClearanceInitially) {
                    if (allCookies != initialCookies) {
                        Log.d(TAG, "cf_clearance cookie changed! Challenge solved after $attempts seconds.")
                        challengeSolved = true
                    } else {
                        Log.d(TAG, "cf_clearance found but unchanged, continuing to wait...")
                    }
                } else {
                    Log.d(TAG, "cf_clearance cookie found! Challenge solved after $attempts seconds.")
                    challengeSolved = true
                }
            }
        }
        
        if (!challengeSolved) {
            Log.w(TAG, "Challenge NOT solved after $attempts attempts")
        }
        
        // Give extra time for cookies to fully sync
        if (challengeSolved) {
            cookieManager.flush()
            delay(2.seconds)
        }
    }
}