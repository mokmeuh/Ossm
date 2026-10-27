package com.ossm.remote.remote

internal interface RemoteSessionCredentialPersistence {
    fun save(value: String)
    fun read(): String?
    fun hasCredential(): Boolean
    fun clear()
}

internal sealed interface StoredRemoteSession {
    data object Missing : StoredRemoteSession
    data object Unreadable : StoredRemoteSession
    data class Available(val session: RemoteBrokerSession) : StoredRemoteSession
}

internal class RemoteSessionLifecycle(
    private val credentials: RemoteSessionCredentialPersistence,
) {
    fun restore(): StoredRemoteSession {
        val value = credentials.read()
        if (value == null) return if (credentials.hasCredential()) StoredRemoteSession.Unreadable else StoredRemoteSession.Missing
        return RemoteBrokerSession.parseStored(value)?.let(StoredRemoteSession::Available) ?: StoredRemoteSession.Unreadable
    }

    fun persist(session: RemoteBrokerSession) = credentials.save(session.toStoredValue())

    fun clear() = credentials.clear()
}
