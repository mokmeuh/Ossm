package com.ossm.remote.remote

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class RemoteSessionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: RemoteSessionClient,
) : ViewModel() {
    val state: StateFlow<RemoteSessionState> = client.state
    fun start() = RemoteSessionService.start(context)
    fun stop() = RemoteSessionService.stop(context)
    fun regenerate() = RemoteSessionService.regenerate(context)
}
