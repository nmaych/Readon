package com.readon.reader

import android.app.Application
import android.content.Context
import com.readon.reader.data.BookStore
import com.readon.reader.data.Prefs
import com.readon.reader.net.GithubClient
import com.readon.reader.update.UpdateManager

/**
 * Process-wide singletons.
 *
 * A hand-rolled container rather than a DI framework: the object graph is five
 * objects deep and entirely without cycles, so a framework would only add a
 * build step and an annotation processor to the offline dependency budget.
 */
class ReadonApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var store: BookStore
        private set
    lateinit var updates: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        store = BookStore(this)
        updates = UpdateManager(this, prefs, github)
    }

    /**
     * The GitHub client.
     *
     * One instance for the process: it reads the token, the pinned route and the
     * remembered host out of [prefs] on every request, so there is no
     * configuration baked into it that could go stale when the user edits
     * Settings.
     */
    val github: GithubClient by lazy { GithubClient(prefs) }

    companion object {
        lateinit var instance: ReadonApp
            private set

        fun of(context: Context): ReadonApp =
            context.applicationContext as ReadonApp
    }
}
