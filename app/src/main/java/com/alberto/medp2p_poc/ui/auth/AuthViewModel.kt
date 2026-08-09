package com.alberto.medp2p_poc.ui.auth

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alberto.medp2p_poc.data.db.AppDatabaseHelper
import com.alberto.medp2p_poc.data.model.AuthProfile
import com.alberto.medp2p_poc.data.model.UserRole
import com.alberto.medp2p_poc.data.model.UserSession
import com.alberto.medp2p_poc.data.security.KeyVaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthUiState {
    object CheckingLocalProfile : AuthUiState()
    object ShowRegistration : AuthUiState()
    data class ShowLogin(val displayName: String) : AuthUiState()
    data class Processing(val message: String) : AuthUiState()
    data class Authenticated(val session: UserSession) : AuthUiState()
    data class Error(val userMessage: String, val previousState: AuthUiState) : AuthUiState()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.CheckingLocalProfile)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    var activeSession: UserSession? = null
        private set

    private val keyVault = KeyVaultManager(application)
    private val dbHelper  = AppDatabaseHelper(application)

    companion object {
        private const val TAG = "P2P_AUTH"
        private const val MIN_PASSWORD_LENGTH = 6
    }

    init { checkExistingProfile() }

    private fun checkExistingProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = dbHelper.getAuthProfile()
                if (profile != null) {
                    _uiState.value = AuthUiState.ShowLogin(profile.displayName)
                } else {
                    _uiState.value = AuthUiState.ShowRegistration
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error comprobando perfil: ${e.message}")
                _uiState.value = AuthUiState.ShowRegistration
            }
        }
    }

    fun register(displayName: String, password: String, role: UserRole) {
        if (_uiState.value is AuthUiState.Processing) return
        val validationError = validateInputs(displayName, password)
        if (validationError != null) {
            _uiState.value = AuthUiState.Error(validationError, AuthUiState.ShowRegistration)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = AuthUiState.Processing("Creando tu identidad segura…")
            try {
                val identity     = keyVault.generateAndStoreIdentity()
                val salt         = keyVault.generateSalt()
                val passwordHash = keyVault.hashPassword(password, salt)
                val now          = System.currentTimeMillis()

                val profile = AuthProfile(
                    peerId       = identity.peerId,
                    displayName  = displayName.trim(),
                    role         = role,
                    passwordHash = passwordHash,
                    passwordSalt = salt,
                    lastLoginAt  = now
                )
                dbHelper.insertAuthProfile(profile)

                val session = UserSession(
                    peerId      = identity.peerId,
                    displayName = displayName.trim(),
                    role        = role,
                    lastLoginAt = now
                )
                activeSession  = session
                Log.d(TAG, "✅ Registro completado. PeerId=${identity.peerId}")
                _uiState.value = AuthUiState.Authenticated(session)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en registro: ${e.stackTraceToString()}")
                keyVault.clearVault()
                _uiState.value = AuthUiState.Error(
                    "No se pudo crear tu perfil. Inténtalo de nuevo.",
                    AuthUiState.ShowRegistration
                )
            }
        }
    }

    fun login(password: String) {
        if (_uiState.value is AuthUiState.Processing) return
        val currentState = _uiState.value
        val loginName    = (currentState as? AuthUiState.ShowLogin)?.displayName ?: ""

        if (password.isBlank()) {
            _uiState.value = AuthUiState.Error("Introduce tu contraseña.", currentState)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = AuthUiState.Processing("Verificando credenciales…")
            try {
                val profile = dbHelper.getAuthProfile()
                if (profile == null) {
                    _uiState.value = AuthUiState.Error(
                        "No se encontró ningún perfil. Registra uno nuevo.",
                        AuthUiState.ShowRegistration
                    )
                    return@launch
                }

                val isValid = keyVault.verifyPassword(
                    inputPassword = password,
                    storedHash    = profile.passwordHash,
                    storedSalt    = profile.passwordSalt
                )
                if (!isValid) {
                    _uiState.value = AuthUiState.Error(
                        "Contraseña incorrecta. Inténtalo de nuevo.",
                        AuthUiState.ShowLogin(profile.displayName)
                    )
                    return@launch
                }

                if (!keyVault.hasStoredIdentity()) {
                    _uiState.value = AuthUiState.Error(
                        "Las claves de seguridad fueron borradas. Necesitas registrarte de nuevo.",
                        AuthUiState.ShowRegistration
                    )
                    return@launch
                }

                // ── FIX FALLO 1: escribir lastLoginAt en cada login ────
                // Este timestamp se muestra en la UI inmediatamente,
                // independientemente de si el relay P2P está disponible.
                val now = System.currentTimeMillis()
                dbHelper.actualizarUltimoLogin(profile.peerId)

                val session = UserSession(
                    peerId      = profile.peerId,
                    displayName = profile.displayName,
                    role        = profile.role,
                    photoUri    = profile.photoUri,
                    lastLoginAt = now
                )
                activeSession  = session
                Log.d(TAG, "✅ Login exitoso: ${profile.displayName}")
                _uiState.value = AuthUiState.Authenticated(session)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en login: ${e.message}")
                _uiState.value = AuthUiState.Error(
                    "Error al verificar las credenciales.",
                    AuthUiState.ShowLogin(loginName)
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // logout(): cierra sesión SIN borrar datos.
    //
    // FIX FALLO 5 (Row-Level Security):
    // Ya NO borramos datos en logout. Cada query filtra por ownerPeerId.
    // Los datos del Médico A son invisibles para el Médico B porque
    // todas las queries usan WHERE ownerPeerId = currentUser.peerId.
    // Borrar datos en logout destruiría los datos propios del usuario
    // si vuelve a iniciar sesión en el mismo dispositivo.
    // ══════════════════════════════════════════════════════════════
    fun logout() {
        activeSession = null
        checkExistingProfile()
        Log.d(TAG, "Sesion cerrada. Datos conservados (filtrados por ownerPeerId).")
    }

    fun cerrarSesionYBorrarDatos() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                keyVault.clearVault()
                val db = dbHelper.writableDatabase
                listOf("medico_vinculado", "paciente_clinico", "historial_clinico",
                    "sync_log", "toma_diaria", "pauta_medica", "auth_profile")
                    .forEach { db.execSQL("DELETE FROM $it") }
                db.close()
                activeSession  = null
                _uiState.value = AuthUiState.ShowRegistration
                Log.d(TAG, "Cuenta eliminada completamente.")
            } catch (e: Exception) {
                Log.e(TAG, "Error eliminando cuenta: ${e.message}")
            }
        }
    }

    fun dismissError() {
        val current = _uiState.value
        if (current is AuthUiState.Error) _uiState.value = current.previousState
    }

    private fun validateInputs(name: String, password: String): String? {
        if (name.isBlank()) return "Introduce tu nombre completo."
        if (name.trim().length < 2) return "El nombre debe tener al menos 2 caracteres."
        if (password.length < MIN_PASSWORD_LENGTH)
            return "La contraseña debe tener al menos $MIN_PASSWORD_LENGTH caracteres."
        return null
    }

    fun resetProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = AuthUiState.Processing("Eliminando perfil anterior...")
                keyVault.clearVault()
                dbHelper.writableDatabase.apply {
                    execSQL("DELETE FROM auth_profile")
                    close()
                }
                activeSession  = null
                _uiState.value = AuthUiState.ShowRegistration
            } catch (e: Exception) {
                Log.e(TAG, "Error reseteando: ${e.message}")
                _uiState.value = AuthUiState.ShowRegistration
            }
        }
    }

    fun getKeyVault(): KeyVaultManager = keyVault
}