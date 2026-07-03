package com.meshchat.data.di

import android.content.Context
import androidx.room.Room
import com.meshchat.data.database.MeshDatabase
import com.meshchat.data.database.MessageDao
import com.meshchat.data.repository.ChatRepositoryImpl
import com.meshchat.data.repository.CryptoRepositoryImpl
import com.meshchat.data.repository.IdentityRepositoryImpl
import com.meshchat.data.repository.PeerRepositoryImpl
import com.meshchat.data.security.KeyManager
import com.meshchat.data.security.RoomCodeManager
import com.meshchat.domain.repository.ChatRepository
import com.meshchat.domain.repository.CryptoRepository
import com.meshchat.domain.repository.IdentityRepository
import com.meshchat.domain.repository.PeerRepository
import com.meshchat.domain.usecase.identity.JoinRoomUseCase
import com.meshchat.domain.usecase.identity.CompleteSetupUseCase
import com.meshchat.domain.usecase.identity.GetIdentityUseCase
import com.meshchat.domain.usecase.identity.IsSetupCompletedUseCase
import com.meshchat.domain.usecase.identity.ObserveJoinedRoomsUseCase
import com.meshchat.domain.usecase.identity.ObserveIdsEnabledUseCase
import com.meshchat.domain.usecase.identity.ObserveMaxHopsUseCase
import com.meshchat.domain.usecase.identity.ResetIdentityUseCase
import com.meshchat.domain.usecase.identity.SetIdsEnabledUseCase
import com.meshchat.domain.usecase.identity.SetMaxHopsUseCase
import com.meshchat.domain.usecase.identity.SetSetupCompletedUseCase
import com.meshchat.domain.usecase.identity.UpdateDisplayNameUseCase
import com.meshchat.domain.usecase.messaging.ClearAllMessagesUseCase
import com.meshchat.domain.usecase.messaging.ObserveBroadcastsUseCase
import com.meshchat.domain.usecase.messaging.ObserveConversationUseCase
import com.meshchat.domain.usecase.messaging.ObserveConversationsUseCase
import com.meshchat.domain.usecase.messaging.SendBroadcastUseCase
import com.meshchat.domain.usecase.messaging.SendMessageUseCase
import com.meshchat.domain.usecase.peers.ObservePeersUseCase
import com.meshchat.data.repository.UsernameClaimBridgeImpl
import com.meshchat.core.UsernameClaimBridge
import com.meshchat.core.UsernameClaimProtocol
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindUsernameClaimBridge(
        impl: UsernameClaimBridgeImpl
    ): UsernameClaimBridge

    @Binds
    @Singleton
    abstract fun bindChatRepository(
        impl: ChatRepositoryImpl
    ): ChatRepository

    @Binds
    @Singleton
    abstract fun bindIdentityRepository(
        impl: IdentityRepositoryImpl
    ): IdentityRepository

    @Binds
    @Singleton
    abstract fun bindCryptoRepository(
        impl: CryptoRepositoryImpl
    ): CryptoRepository

    @Binds
    @Singleton
    abstract fun bindPeerRepository(
        impl: PeerRepositoryImpl
    ): PeerRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideMeshDatabase(
        @ApplicationContext context: Context
    ): MeshDatabase {
        return Room.databaseBuilder(
            context,
            MeshDatabase::class.java,
            "meshchat_database"
        )
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: MeshDatabase): MessageDao {
        return database.messageDao()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideKeyManager(): KeyManager {
        return KeyManager()
    }

    @Provides
    @Singleton
    fun provideRoomCodeManager(
        @ApplicationContext context: Context,
        keyManager: KeyManager
    ): RoomCodeManager {
        return RoomCodeManager(context, keyManager)
    }

    @Provides
    @Singleton
    fun provideUsernameClaimProtocol(
        bridge: UsernameClaimBridge
    ): UsernameClaimProtocol {
        return UsernameClaimProtocol(bridge)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {

    @Provides
    @Singleton
    fun provideObservePeersUseCase(peerRepo: PeerRepository): ObservePeersUseCase {
        return ObservePeersUseCase(peerRepo)
    }

    @Provides
    @Singleton
    fun provideSendMessageUseCase(chatRepo: ChatRepository, cryptoRepo: CryptoRepository): SendMessageUseCase {
        return SendMessageUseCase(chatRepo, cryptoRepo)
    }

    @Provides
    @Singleton
    fun provideSendBroadcastUseCase(chatRepo: ChatRepository, cryptoRepo: CryptoRepository): SendBroadcastUseCase {
        return SendBroadcastUseCase(chatRepo, cryptoRepo)
    }

    @Provides
    @Singleton
    fun provideObserveConversationUseCase(chatRepo: ChatRepository): ObserveConversationUseCase {
        return ObserveConversationUseCase(chatRepo)
    }

    @Provides
    @Singleton
    fun provideObserveConversationsUseCase(chatRepo: ChatRepository): ObserveConversationsUseCase {
        return ObserveConversationsUseCase(chatRepo)
    }

    @Provides
    @Singleton
    fun provideObserveBroadcastsUseCase(chatRepo: ChatRepository): ObserveBroadcastsUseCase {
        return ObserveBroadcastsUseCase(chatRepo)
    }

    @Provides
    @Singleton
    fun provideUpdateDisplayNameUseCase(identityRepo: IdentityRepository): UpdateDisplayNameUseCase {
        return UpdateDisplayNameUseCase(identityRepo)
    }

    @Provides
    @Singleton
    fun provideGetIdentityUseCase(identityRepo: IdentityRepository): GetIdentityUseCase {
        return GetIdentityUseCase(identityRepo)
    }

    @Provides
    @Singleton
    fun provideObserveJoinedRoomsUseCase(identityRepo: IdentityRepository): ObserveJoinedRoomsUseCase {
        return ObserveJoinedRoomsUseCase(identityRepo)
    }

    @Provides
    @Singleton
    fun provideObserveMaxHopsUseCase(identityRepo: IdentityRepository): ObserveMaxHopsUseCase {
        return ObserveMaxHopsUseCase(identityRepo)
    }

    @Provides
    @Singleton
    fun provideObserveIdsEnabledUseCase(identityRepo: IdentityRepository): ObserveIdsEnabledUseCase {
        return ObserveIdsEnabledUseCase(identityRepo)
    }

    @Provides
    @Singleton
    fun provideIsSetupCompletedUseCase(identityRepo: IdentityRepository): IsSetupCompletedUseCase {
        return IsSetupCompletedUseCase(identityRepo)
    }

    @Provides
    @Singleton
    fun provideSetSetupCompletedUseCase(identityRepo: IdentityRepository): SetSetupCompletedUseCase {
        return SetSetupCompletedUseCase(identityRepo)
    }

    @Provides
    @Singleton
    fun provideSetMaxHopsUseCase(identityRepo: IdentityRepository): SetMaxHopsUseCase {
        return SetMaxHopsUseCase(identityRepo)
    }

    @Provides
    @Singleton
    fun provideSetIdsEnabledUseCase(identityRepo: IdentityRepository): SetIdsEnabledUseCase {
        return SetIdsEnabledUseCase(identityRepo)
    }

    @Provides
    @Singleton
    fun provideClearAllMessagesUseCase(chatRepo: ChatRepository): ClearAllMessagesUseCase {
        return ClearAllMessagesUseCase(chatRepo)
    }

    @Provides
    @Singleton
    fun provideResetIdentityUseCase(
        chatRepo: ChatRepository,
        identityRepo: IdentityRepository
    ): ResetIdentityUseCase {
        return ResetIdentityUseCase(chatRepo, identityRepo)
    }

    @Provides
    @Singleton
    fun provideCompleteSetupUseCase(
        updateDisplayNameUseCase: UpdateDisplayNameUseCase,
        joinRoomUseCase: JoinRoomUseCase,
        setSetupCompletedUseCase: SetSetupCompletedUseCase
    ): CompleteSetupUseCase {
        return CompleteSetupUseCase(
            updateDisplayNameUseCase,
            joinRoomUseCase,
            setSetupCompletedUseCase
        )
    }

    @Provides
    @Singleton
    fun provideJoinRoomUseCase(
        cryptoRepo: CryptoRepository
    ): JoinRoomUseCase {
        return JoinRoomUseCase(cryptoRepo)
    }
}
