package com.abang.prayerzones.di

import android.content.Context
import com.abang.prayerzones.api.DonationVerificationApi
import com.abang.prayerzones.PrayerCache
import com.abang.prayerzones.repository.PrayerRepository
import com.abang.prayerzones.util.GeofenceManager
import com.abang.prayerzones.util.InMosqueModeManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.abang.prayerzones.api.PrayerApi
import com.abang.prayerzones.api.RetrofitInstance
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val DONATION_WORKER_BASE_URL = "https://your-worker-url.workers.dev/"

    @Provides
    @Singleton
    fun providePrayerApi(): PrayerApi {
        return RetrofitInstance.api
    }

    @Provides
    @Singleton
    fun providePrayerRepository(api: PrayerApi): PrayerRepository {
        return PrayerRepository(api)
    }

    @Provides
    @Singleton
    fun provideDonationVerificationApi(): DonationVerificationApi {
        return Retrofit.Builder()
            .baseUrl(DONATION_WORKER_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DonationVerificationApi::class.java)
    }


    @Provides
    @Singleton
    fun providePrayerCache(@ApplicationContext context: Context): PrayerCache {
        return PrayerCache(context)
    }

    // Add this:
    @Provides
    @Singleton
    fun provideAppContext(@ApplicationContext context: Context): Context {
        return context
    }

    @Provides
    @Singleton
    fun provideInMosqueModeManager(@ApplicationContext context: Context): InMosqueModeManager =
        InMosqueModeManager(context)

    @Provides
    @Singleton
    fun provideGeofenceManager(
        @ApplicationContext context: Context,
        inMosqueModeManager: InMosqueModeManager
    ): GeofenceManager = GeofenceManager(context, inMosqueModeManager)
}

