package com.beeper.lightos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.ktor.util.decodeBase64Bytes
import net.folivo.trixnity.client.user
import net.folivo.trixnity.client.user.getAccountData
import net.folivo.trixnity.crypto.core.encryptAes256Ctr
import net.folivo.trixnity.utils.encodeUnpaddedBase64
import net.folivo.trixnity.utils.toByteArray
import net.folivo.trixnity.utils.toByteArrayFlow
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.key
import net.folivo.trixnity.client.verification
import net.folivo.trixnity.client.verification.ActiveDeviceVerification
import net.folivo.trixnity.client.verification.ActiveSasVerificationState
import net.folivo.trixnity.client.verification.ActiveVerificationState
import com.thelightphone.sdk.LightViewModel

class BeeperVerificationViewModel : LightViewModel<Unit>() {
    private val client: MatrixClient
        get() = BeeperRepository.getClient()!!

    private val _isVerified = MutableStateFlow(true) // assume true until we check
    val isVerified: StateFlow<Boolean> = _isVerified.asStateFlow()

    private val _activeVerification = MutableStateFlow<ActiveDeviceVerification?>(null)
    val activeVerification: StateFlow<ActiveDeviceVerification?> = _activeVerification.asStateFlow()

    private val _verificationState = MutableStateFlow<ActiveVerificationState?>(null)
    val verificationState: StateFlow<ActiveVerificationState?> = _verificationState.asStateFlow()

    private val _sasVerificationState = MutableStateFlow<ActiveSasVerificationState?>(null)
    val sasVerificationState: StateFlow<ActiveSasVerificationState?> = _sasVerificationState.asStateFlow()

    init {
        viewModelScope.launch {
            client.key.getTrustLevel(client.userId, client.deviceId).collect { level ->
                _isVerified.value = level is net.folivo.trixnity.crypto.key.DeviceTrustLevel.CrossSigned && level.verified
            }
        }
        viewModelScope.launch {
            client.verification.activeDeviceVerification.collectLatest { verification ->
                _activeVerification.value = verification
                if (verification != null) {
                    verification.state.collectLatest { state ->
                        _verificationState.value = state
                        if (state is ActiveVerificationState.Start) {
                            val method = state.method
                            if (method is net.folivo.trixnity.client.verification.ActiveSasVerificationMethod) {
                                method.state.collectLatest { sasState ->
                                    _sasVerificationState.value = sasState
                                }
                            }
                        } else {
                            _sasVerificationState.value = null
                        }
                    }
                } else {
                    _verificationState.value = null
                    _sasVerificationState.value = null
                }
            }
        }
    }

    fun submitSecurityCode(code: String) {
        viewModelScope.launch {
            try {
                val methods = client.verification.getSelfVerificationMethods().first()
                if (methods is net.folivo.trixnity.client.verification.VerificationService.SelfVerificationMethods.CrossSigningEnabled) {
                    val recoveryKeyMethod = methods.methods.filterIsInstance<net.folivo.trixnity.client.verification.SelfVerificationMethod.AesHmacSha2RecoveryKey>().firstOrNull()
                    val passphraseMethod = methods.methods.filterIsInstance<net.folivo.trixnity.client.verification.SelfVerificationMethod.AesHmacSha2RecoveryKeyWithPbkdf2Passphrase>().firstOrNull()
                    
                    if (recoveryKeyMethod != null && code.replace("-", "").length >= 48) {
                        android.util.Log.d("BeeperVerification", "Using AesHmacSha2RecoveryKey verification method")
                        val result = recoveryKeyMethod.verify(code)
                        if (result.isFailure) {
                            android.util.Log.e("BeeperVerification", "Recovery Key verify failed", result.exceptionOrNull())
                            // Trixnity masks bit 63 of the stored IV before computing the
                            // check MAC (only correct when *generating* an IV). If the key
                            // creator stored an IV with that bit set, the standard check can
                            // never succeed. Re-check with the IV used verbatim and finish
                            // verification manually when it matches.
                            tryUnmaskedIvVerification(code)
                        } else {
                            android.util.Log.d("BeeperVerification", "Recovery Key verify succeeded!")
                            
                            // Check secrets
                            android.util.Log.d("BeeperVerification", "Recovery Key verify succeeded! (Skipped logging secrets due to koin get issue)")
                        }
                    } else if (passphraseMethod != null) {
                        android.util.Log.d("BeeperVerification", "Using AesHmacSha2RecoveryKeyWithPbkdf2Passphrase verification method")
                        val result = passphraseMethod.verify(code)
                        if (result.isFailure) {
                            android.util.Log.e("BeeperVerification", "Passphrase verify failed", result.exceptionOrNull())
                        } else {
                            android.util.Log.d("BeeperVerification", "Passphrase verify succeeded!")
                        }
                    } else {
                        android.util.Log.e("BeeperVerification", "No suitable recovery key method found")
                    }
                } else {
                    android.util.Log.e("BeeperVerification", "Cross signing not enabled or not ready: $methods")
                }
            } catch (e: Exception) {
                android.util.Log.e("BeeperVerification", "Failed to verify with recovery key", e)
            }
        }
    }

    private suspend fun tryUnmaskedIvVerification(code: String) {
        val tag = "BeeperVerification"
        try {
            val defaultKeyContent = client.user
                .getAccountData<net.folivo.trixnity.core.model.events.m.secretstorage.DefaultSecretKeyEventContent>()
                .first()
            if (defaultKeyContent == null) {
                android.util.Log.e(tag, "Fallback: account has no default secret storage key")
                return
            }
            val keyId = defaultKeyContent.key
            val keyInfo = client.user
                .getAccountData<net.folivo.trixnity.core.model.events.m.secretstorage.SecretKeyEventContent>(key = keyId)
                .first()
            val aesInfo = keyInfo as? net.folivo.trixnity.core.model.events.m.secretstorage.SecretKeyEventContent.AesHmacSha2Key
            if (aesInfo == null) {
                android.util.Log.e(tag, "Fallback: default key is not AesHmacSha2 but $keyInfo")
                return
            }
            android.util.Log.d(
                tag,
                "Fallback: keyId=$keyId hasPassphrase=${aesInfo.passphrase != null} iv=${aesInfo.iv} mac=${aesInfo.mac}"
            )
            val storedIv = aesInfo.iv?.decodeBase64Bytes()
            val storedMac = aesInfo.mac
            if (storedIv == null || storedMac == null) {
                android.util.Log.e(tag, "Fallback: stored iv or mac missing")
                return
            }
            val key = net.folivo.trixnity.crypto.key.decodeRecoveryKey(code)
            val keys = net.folivo.trixnity.crypto.core.deriveKeys(key, "")

            val rawIvCiphertext = ByteArray(32).toByteArrayFlow()
                .encryptAes256Ctr(key = keys.aesKey, initialisationVector = storedIv)
                .toByteArray()
            val rawIvMac = net.folivo.trixnity.crypto.core.hmacSha256(keys.hmacKey, rawIvCiphertext)
                .encodeUnpaddedBase64()

            val highBitSet = (storedIv[8].toInt() and 0x80) != 0
            android.util.Log.d(
                tag,
                "Fallback: ivByte8HighBitSet=$highBitSet storedMac=$storedMac rawIvMac=$rawIvMac"
            )

            fun norm(s: String) = s.replace("=", "")
            if (norm(rawIvMac) == norm(storedMac)) {
                android.util.Log.d(tag, "Fallback: MAC matches with unmasked IV — completing verification manually")
                client.di.get<net.folivo.trixnity.client.key.KeySecretService>()
                    .decryptOrCreateMissingSecrets(key, keyId, aesInfo)
                val trustResult = client.di.get<net.folivo.trixnity.client.key.KeyTrustService>()
                    .checkOwnAdvertisedMasterKeyAndVerifySelf(key, keyId, aesInfo)
                if (trustResult.isFailure) {
                    android.util.Log.e(tag, "Fallback: trust step failed", trustResult.exceptionOrNull())
                } else {
                    android.util.Log.d(tag, "Fallback verification succeeded!")
                }
            } else {
                android.util.Log.e(
                    tag,
                    "Fallback: MAC does not match with unmasked IV either — the code does not belong to this account's key"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e(tag, "Fallback verification threw", e)
        }
    }

    fun requestVerification() {
        viewModelScope.launch {
            try {
                android.util.Log.d("BeeperVerification", "Calling createDeviceVerificationRequest...")
                val devices = client.api.device.getDevices().getOrNull() ?: emptyList()
                val otherDeviceIds = devices.map { it.deviceId }.filter { it != client.deviceId }.toSet()
                android.util.Log.d("BeeperVerification", "Other devices to target: $otherDeviceIds")
                val result = client.verification.createDeviceVerificationRequest(client.userId, otherDeviceIds)
                if (result.isFailure) {
                    android.util.Log.e("BeeperVerification", "Request failed", result.exceptionOrNull())
                } else {
                    android.util.Log.d("BeeperVerification", "Request succeeded!")
                }
            } catch (e: Exception) {
                android.util.Log.e("BeeperVerification", "Request threw exception", e)
            }
        }
    }

    fun acceptRequest(state: ActiveVerificationState.TheirRequest) {
        viewModelScope.launch {
            state.ready()
        }
    }
    
    fun startSas(state: ActiveVerificationState.Ready) {
        viewModelScope.launch {
            state.start(net.folivo.trixnity.core.model.events.m.key.verification.VerificationMethod.Sas)
        }
    }
    
    fun acceptSas(state: ActiveSasVerificationState.TheirSasStart) {
        viewModelScope.launch {
            state.accept()
        }
    }
    
    fun match(state: ActiveSasVerificationState.ComparisonByUser) {
        viewModelScope.launch {
            state.match()
        }
    }
    
    fun noMatch(state: ActiveSasVerificationState.ComparisonByUser) {
        viewModelScope.launch {
            state.noMatch()
        }
    }

    fun cancelVerification() {
        viewModelScope.launch {
            _activeVerification.value?.cancel()
        }
    }

    fun resetVerification() {
        _activeVerification.value = null
        _verificationState.value = null
        _sasVerificationState.value = null
    }
}
