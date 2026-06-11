package com.meshchat.transports.di

import com.meshchat.core.PresenceManager
import com.meshchat.core.TransportAdapter
import com.meshchat.transports.NearbyConnectionsTransport
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TransportsModule {

    @Binds
    @Singleton
    abstract fun bindTransportAdapter(
        impl: NearbyConnectionsTransport
    ): TransportAdapter
}

@Module
@InstallIn(SingletonComponent::class)
object PresenceModule {

    @Provides
    @Singleton
    fun providePresenceManager(): PresenceManager {
        return PresenceManager()
    }
}
