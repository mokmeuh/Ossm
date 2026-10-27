package com.ossm.remote.updater

/** Severity of [LocalOtaState.message], set by the producer so the UI never parses text. */
enum class LocalOtaMessageTone { Info, Success, Error }

/** What the receiver shows: the pairing QR code and its reachable addresses (with port) as text. */
data class LocalOtaReceiverInfo(
    val addresses: List<String>,
    val qrUri: String,
)

/** Sender side while the receiver decides: "Nombre 47 · En attente de confirmation sur l’autre téléphone". */
data class LocalOtaPairingPrompt(
    val number: String,
    val peerLabel: String,
    val localVersionName: String,
    val remoteVersionName: String?,
)

data class LocalOtaState(
    val receiverActive: Boolean = false,
    val senderActive: Boolean = false,
    val message: String = "",
    val tone: LocalOtaMessageTone = LocalOtaMessageTone.Info,
    val peerName: String? = null,
    val progressPercent: Int? = null,
    val receiverInfo: LocalOtaReceiverInfo? = null,
    /** Sender: number to compare while waiting for Oui/Non on the receiver. */
    val pairing: LocalOtaPairingPrompt? = null,
    /** Receiver: "Le nombre 47 est-il bien celui affiché sur l’autre téléphone ?" */
    val incoming: LocalOtaIncomingRequest? = null,
    val suggestManual: Boolean = false,
)

internal data class LocalOtaApkMetadata(
    val packageName: String,
    val versionCode: Long,
    val signerIds: Set<String>,
)

internal enum class LocalOtaRejection { WrongPackage, AlreadyUpToDate, WrongSigner }

internal fun validateLocalOtaApk(
    metadata: LocalOtaApkMetadata,
    expectedPackageName: String,
    installedVersionCode: Long,
    installedSignerIds: Set<String>,
): LocalOtaRejection? {
    if (metadata.packageName != expectedPackageName) return LocalOtaRejection.WrongPackage
    if (metadata.versionCode <= installedVersionCode) return LocalOtaRejection.AlreadyUpToDate
    if (installedSignerIds.isNotEmpty() && metadata.signerIds != installedSignerIds) return LocalOtaRejection.WrongSigner
    return null
}
