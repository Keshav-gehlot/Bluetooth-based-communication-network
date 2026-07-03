package com.meshchat.voice

import com.meshchat.core.VoiceBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {

    @Binds
    @Singleton
    abstract fun bindVoiceBridge(impl: VoiceEngine): VoiceBridge
}
