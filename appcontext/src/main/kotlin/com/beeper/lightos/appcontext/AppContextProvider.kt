package com.beeper.lightos.appcontext

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/** The application Context, set before any other component of the process runs. */
object AppContext {
    @Volatile
    var instance: Context? = null
        internal set
}

/**
 * Does nothing but exist. Android instantiates every declared provider while the
 * process starts — before a service, a receiver or an Application callback gets
 * to run — so this is the earliest and most reliable place to keep the Context.
 */
class AppContextProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        AppContext.instance = context?.applicationContext
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0
}
