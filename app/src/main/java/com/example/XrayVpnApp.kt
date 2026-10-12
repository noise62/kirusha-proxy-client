package com.example

import android.app.Application
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class XrayVpnApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()

        settingsRepository = SettingsRepository(this)
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "xray_servers.db"
        ).build()

        // Clean out any dummy/sample servers so the user starts with empty list
        CoroutineScope(Dispatchers.IO).launch {
            database.serverDao().deleteNonCustom()
        }
    }
}
