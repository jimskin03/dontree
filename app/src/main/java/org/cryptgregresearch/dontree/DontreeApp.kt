package org.cryptgregresearch.dontree

import android.app.Application
import androidx.room.Room
import org.cryptgregresearch.dontree.data.*

class DontreeApp : Application() {
    val database by lazy { Room.databaseBuilder(this, MusicDatabase::class.java, "dontree.db").build() }
    val music by lazy { MusicRepository(this, database.music()) }
    val settings by lazy { Settings(this) }
}
