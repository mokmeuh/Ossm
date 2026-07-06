package com.ossm.remote.di

import android.content.Context
import androidx.room.Room
import com.ossm.remote.data.db.AppDatabase
import com.ossm.remote.data.db.PresetDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "ossm_db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun providePresetDao(db: AppDatabase): PresetDao = db.presetDao()
}
