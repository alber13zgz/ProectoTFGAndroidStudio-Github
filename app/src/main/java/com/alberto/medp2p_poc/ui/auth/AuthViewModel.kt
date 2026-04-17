package com.alberto.medp2p_poc.ui.auth

import android.app.Application
import android.util.Base64
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
    private val dbHelper = AppDatabaseHelper(application)

    companion object {
        private const val TAG = "P2P_AUTH"
        private const val MIN_PASSWORD_LENGTH = 6
    }

    init {
        checkExistingProfile()
    }

    private fun checkExistingProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Comprobando perfil local existente...")
                val profile = dbHelper.getAuthProfile()

                if (profile != null) {
                    Log.d(TAG, "Perfil encontrado: ${profile.displayName}")
                    _uiState.value = AuthUiState.ShowLogin(profile.displayName)
                } else {
                    Log.d(TAG, "No hay perfil. Mostrando registro.")
                    _uiState.value = AuthUiState.ShowRegistration
                }
            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error comprobando perfil: ${e.message}")
                _uiState.value = AuthUiState.ShowRegistration
            }
        }
    }

    fun register(displayName: String, password: String, role: UserRole) {
        if (_uiState.value is AuthUiState.Processing) return

        val validationError = validateInputs(displayName, password)
        if (validationError != null) {
            _uiState.value = AuthUiState.Error(
                userMessage = validationError,
                previousState = AuthUiState.ShowRegistration
            )
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = AuthUiState.Processing("Creando tu identidad segura…")

            try {
                val identity = keyVault.generateAndStoreIdentity()
                val salt = keyVault.generateSalt()
                val passwordHash = keyVault.hashPassword(password, salt)

                val profile = AuthProfile(
                    peerId = identity.peerId,
                    displayName = displayName.trim(),
                    role = role,
                    passwordHash = passwordHash,
                    passwordSalt = salt
                )
                dbHelper.insertAuthProfile(profile)

                val session = UserSession(
                    peerId = identity.peerId,
                    displayName = displayName.trim(),
                    role = role
                )
                activeSession = session

                Log.d(TAG, "✅ Registro completado. PeerId=${identity.peerId}")
                _uiState.value = AuthUiState.Authenticated(session)

            } catch (e: Exception) {
                Log.e("P2P_ERROR", "❌ Error en registro: ${e.stackTraceToString()}")
                keyVault.clearVault()

                _uiState.value = AuthUiState.Error(
                    userMessage = "No se pudo crear tu perfil. Inténtalo de nuevo.",
                    previousState = AuthUiState.ShowRegistration
                )
            }
        }
    }

    fun login(password: String) {
        if (_uiState.value is AuthUiState.Processing) return

        val currentState = _uiState.value
        val loginName = (currentState as? AuthUiState.ShowLogin)?.displayName ?: ""

        if (password.isBlank()) {
            _uiState.value = AuthUiState.Error(
                userMessage = "Introduce tu contraseña.",
                previousState = currentState
            )
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = AuthUiState.Processing("Verificando credenciales…")

            try {
                val profile = dbHelper.getAuthProfile()
                if (profile == null) {
                    _uiState.value = AuthUiState.Error(
                        userMessage = "No se encontró ningún perfil. Registra uno nuevo.",
                        previousState = AuthUiState.ShowRegistration
                    )
                    return@launch
                }

                val isValid = keyVault.verifyPassword(
                    inputPassword = password,
                    storedHash = profile.passwordHash,
                    storedSalt = profile.passwordSalt
                )

                if (!isValid) {
                    Log.w(TAG, "⚠️ Contraseña incorrecta.")
                    _uiState.value = AuthUiState.Error(
                        userMessage = "Contraseña incorrecta. Inténtalo de nuevo.",
                        previousState = AuthUiState.ShowLogin(profile.displayName)
                    )
                    return@launch
                }

                if (!keyVault.hasStoredIdentity()) {
                    _uiState.value = AuthUiState.Error(
                        userMessage = "Las claves de seguridad fueron borradas. Necesitas registrarte de nuevo.",
                        previousState = AuthUiState.ShowRegistration
                    )
                    return@launch
                }

                val session = UserSession(
                    peerId = profile.peerId,
                    displayName = profile.displayName,
                    role = profile.role
                )
                activeSession = session

                Log.d(TAG, "✅ Login exitoso: ${profile.displayName}")
                _uiState.value = AuthUiState.Authenticated(session)

            } catch (e: Exception) {
                Log.e("P2P_ERROR", "❌ Error en login: ${e.message}")
                _uiState.value = AuthUiState.Error(
                    userMessage = "Error al verificar las credenciales.",
                    previousState = AuthUiState.ShowLogin(loginName)
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ NUEVO: CIERRE DE SESIÓN MULTI-CUENTA (TESTING PoC) ═══════
    // ══════════════════════════════════════════════════════════════

    fun cerrarSesionYBorrarDatos() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Borrar bóveda de claves
                keyVault.clearVault()

                // 2. Borrar perfil de SQLite (asumiendo que la tabla se llama auth_profile)
                dbHelper.writableDatabase.execSQL("DELETE FROM auth_profile")

                // 3. Limpiar estado en memoria
                activeSession = null
                _uiState.value = AuthUiState.ShowRegistration
                Log.d(TAG, "Sesión cerrada y datos borrados. Listo para cuenta nueva.")
            } catch (e: Exception) {
                Log.e(TAG, "Error al cerrar sesión: ${e.message}")
            }
        }
    }

    fun dismissError() {
        val current = _uiState.value
        if (current is AuthUiState.Error) {
            _uiState.value = current.previousState
        }
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

                val db = dbHelper.writableDatabase
                db.execSQL("DELETE FROM auth_profile")
                db.close()

                keyVault.clearVault()
                activeSession = null

                Log.d(TAG, "Perfil reseteado.")
                _uiState.value = AuthUiState.ShowRegistration

            } catch (e: Exception) {
                Log.e("P2P_ERROR", "Error reseteando: ${e.message}")
                _uiState.value = AuthUiState.ShowRegistration
            }
        }
    }
    /**
     * Cierra la sesion SIN borrar el perfil.
     * El usuario vuelve a la pantalla de login y puede re-entrar
     * con su contrasena. No se pierde nada.
     */
    fun logout() {
        activeSession = null
        // Recargamos el perfil existente → mostrara ShowLogin
        checkLocalProfile()
        Log.d(TAG, "Sesion cerrada (perfil conservado).")
    }

    fun getKeyVault(): KeyVaultManager = keyVault
}