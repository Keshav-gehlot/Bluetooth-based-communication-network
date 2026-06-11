package com.meshchat.ui.features.splash

import androidx.lifecycle.ViewModel
import com.meshchat.domain.usecase.identity.IsSetupCompletedUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val isSetupCompletedUseCase: IsSetupCompletedUseCase
) : ViewModel() {

    fun isFirstLaunch(): Boolean {
        return !isSetupCompletedUseCase()
    }
}
