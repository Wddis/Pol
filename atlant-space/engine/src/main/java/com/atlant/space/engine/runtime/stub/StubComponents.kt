package com.atlant.space.engine.runtime.stub

import android.app.Activity
import android.app.Service
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import com.atlant.space.engine.AtlantEngine
import com.atlant.space.engine.runtime.VClient
import com.atlant.space.engine.runtime.VirtualRuntime

open class StubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { VClient.ensure(this, intent) }
        val pkg = intent?.getStringExtra(AtlantEngine.EXTRA_PACKAGE) ?: ""
        if (VirtualRuntime.guest(pkg) == null) finish()
    }
    class P0 {
        class A0 : StubActivity()
        class A1 : StubActivity()
        class A2 : StubActivity()
        class A3 : StubActivity()
        class A4 : StubActivity()
        class A5 : StubActivity()
        class A6 : StubActivity()
        class A7 : StubActivity()
    }
    class P1 {
        class A0 : StubActivity()
        class A1 : StubActivity()
        class A2 : StubActivity()
        class A3 : StubActivity()
        class A4 : StubActivity()
        class A5 : StubActivity()
        class A6 : StubActivity()
        class A7 : StubActivity()
    }
    class P2 {
        class A0 : StubActivity()
        class A1 : StubActivity()
        class A2 : StubActivity()
        class A3 : StubActivity()
        class A4 : StubActivity()
        class A5 : StubActivity()
        class A6 : StubActivity()
        class A7 : StubActivity()
    }
    class P3 {
        class A0 : StubActivity()
        class A1 : StubActivity()
        class A2 : StubActivity()
        class A3 : StubActivity()
        class A4 : StubActivity()
        class A5 : StubActivity()
        class A6 : StubActivity()
        class A7 : StubActivity()
    }
    class P4 {
        class A0 : StubActivity()
        class A1 : StubActivity()
        class A2 : StubActivity()
        class A3 : StubActivity()
        class A4 : StubActivity()
        class A5 : StubActivity()
        class A6 : StubActivity()
        class A7 : StubActivity()
    }
    class P5 {
        class A0 : StubActivity()
        class A1 : StubActivity()
        class A2 : StubActivity()
        class A3 : StubActivity()
        class A4 : StubActivity()
        class A5 : StubActivity()
        class A6 : StubActivity()
        class A7 : StubActivity()
    }
}

open class StubService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    class P0 : StubService()
    class P1 : StubService()
    class P2 : StubService()
    class P3 : StubService()
    class P4 : StubService()
    class P5 : StubService()
}

open class StubProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    class P0 : StubProvider()
    class P1 : StubProvider()
    class P2 : StubProvider()
    class P3 : StubProvider()
    class P4 : StubProvider()
    class P5 : StubProvider()
}
