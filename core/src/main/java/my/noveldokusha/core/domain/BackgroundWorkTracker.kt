package my.noveldokusha.core.domain

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks when the app is doing background network work — library
 * update checks, batch chapter downloads, backup restores — as
 * opposed to loading something the user just opened.
 *
 * The Cloudflare verification interceptor consults [isActive]:
 * while background work is running it must NOT launch the
 * verification WebView, because that would interrupt whatever the
 * user is currently reading with a captcha for a source they are
 * not using. Background requests that hit a Cloudflare wall simply
 * fail; the challenge is shown later, when the user opens that
 * source himself.
 *
 * Usage: call [enter] when a background operation starts and
 * [exit] when it finishes (in a finally block). Calls nest.
 */
object BackgroundWorkTracker {
    private val activeCount = AtomicInteger(0)

    val isActive: Boolean
        get() = activeCount.get() > 0

    fun enter() {
        activeCount.incrementAndGet()
    }

    fun exit() {
        activeCount.decrementAndGet()
    }
}
