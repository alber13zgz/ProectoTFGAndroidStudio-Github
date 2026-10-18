package com.alberto.medp2p_poc.data.db

import android.content.Context
import android.util.Log
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import com.alberto.medp2p_poc.data.security.KeyVaultManager

// ════════════════════════════════════════════════════════════════
// VERSIÓN 5 — Cambios respecto a v4:
//
//   5. Tablas pauta_medica_v2 y registro_suministro.
//      JUSTIFICACIÓN: gestión de pautas médicas distribuidas (Fase 1).
//      pauta_medica_v2 almacena pautas con fechaInicio/fechaFin y
//      frecuenciaDiaria. registro_suministro registra cada administración.
//      La query obtenerAlertasPendientesHoy() cruza ambas tablas con
//      paciente_clinico para mostrar solo alertas pendientes del día.
//
//   También se añade actualizarAlergiasPaciente() que actualiza solo
//   la columna allergies sin tocar notes — necesario para el perfil
//   del paciente en ProfileScreen.
// ════════════════════════════════════════════════════════════════

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "medp2p_offline.db"
        const val DATABASE_VERSION = 12
    }

    // Passphrase aleatoria por instalación, custodiada por KeyVaultManager
    // (EncryptedSharedPreferences + MasterKey del Android Keystore).
    // lazy → se resuelve en la primera apertura, no en el constructor.
    private val keyVault = KeyVaultManager(context.applicationContext)
    private val dbPassphrase: String by lazy { keyVault.getOrCreateDatabasePassphrase() }

    init {
        SQLiteDatabase.loadLibs(context)
    }

    /** Acceso a la BD cifrada para clases externas (p. ej. AuthViewModel). */
    fun writableDb(): SQLiteDatabase = getWritableDatabase(dbPassphrase)

    data class MedicoVinculado(
        val doctorPeerId: String,
        val doctorName: String,
        val linkedAt: Long,
        val doctorPhotoUri: String
    )

    override fun onCreate(db: SQLiteDatabase) {
        Log.i("P2P_TFG", "[DB] Creando base de datos desde cero (v$DATABASE_VERSION)...")

        // --- MÓDULO 1: IDENTIDAD ---
        db.execSQL("""
            CREATE TABLE usuario (
                peerId TEXT PRIMARY KEY,
                nombre TEXT NOT NULL,
                fechaRegistro INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE paciente (
                usuarioPeerId TEXT PRIMARY KEY,
                alergiasConocidas TEXT,
                medicoReferencia TEXT,
                FOREIGN KEY(usuarioPeerId) REFERENCES usuario(peerId) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE cuidador (
                usuarioPeerId TEXT PRIMARY KEY,
                telefonoContacto TEXT,
                nivelPermisos INTEGER NOT NULL,
                FOREIGN KEY(usuarioPeerId) REFERENCES usuario(peerId) ON DELETE CASCADE
            )
        """.trimIndent())

        // --- MÓDULO 2: DOMINIO CLÍNICO ---
        db.execSQL("""
            CREATE TABLE medicamento (
                idMedicamento TEXT PRIMARY KEY,
                nombreComercial TEXT NOT NULL,
                principleActivo TEXT,
                concentracionMg REAL NOT NULL,
                stockActual INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE pauta_medica (
                idPauta TEXT PRIMARY KEY,
                pacienteId TEXT NOT NULL,
                medicamentoId TEXT NOT NULL,
                intervaloHoras INTEGER NOT NULL,
                FOREIGN KEY(medicamentoId) REFERENCES medicamento(idMedicamento) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_pauta_paciente ON pauta_medica(pacienteId)")
        db.execSQL("CREATE INDEX idx_pauta_medicamento ON pauta_medica(medicamentoId)")

        // --- MÓDULO 3: EJECUCIÓN Y SINCRONIZACIÓN ---
        db.execSQL("""
            CREATE TABLE toma_diaria (
                idToma TEXT PRIMARY KEY,
                pautaId TEXT NOT NULL,
                horaProgramada INTEGER NOT NULL,
                horaRealConsumo INTEGER,
                estado INTEGER NOT NULL,
                confirmadoPorPeerId TEXT,
                FOREIGN KEY(pautaId) REFERENCES pauta_medica(idPauta) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_toma_pauta ON toma_diaria(pautaId)")

        crearTablaSyncLog(db)

        // --- MÓDULO 4: HISTORIAL CLÍNICO con ownerPeerId ---
        db.execSQL("""
            CREATE TABLE historial_clinico (
                id TEXT PRIMARY KEY,
                ownerPeerId TEXT NOT NULL DEFAULT '',
                patientId TEXT NOT NULL,
                text TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                isMine INTEGER NOT NULL,
                senderAlias TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_historial_patient ON historial_clinico(patientId)")
        db.execSQL("CREATE INDEX idx_historial_owner ON historial_clinico(ownerPeerId)")

        // --- MÓDULO 5: AUTENTICACIÓN LOCAL ---
        db.execSQL("""
            CREATE TABLE auth_profile (
                peerId TEXT PRIMARY KEY,
                displayName TEXT NOT NULL,
                role TEXT NOT NULL,
                passwordHash BLOB NOT NULL,
                passwordSalt BLOB NOT NULL,
                createdAt INTEGER NOT NULL,
                photoUri TEXT NOT NULL DEFAULT '',
                lastLoginAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // --- MÓDULO 6: DIRECTORIO CLÍNICO con ownerPeerId ---
        db.execSQL("""
            CREATE TABLE paciente_clinico (
                id TEXT PRIMARY KEY,
                ownerPeerId TEXT NOT NULL DEFAULT '',
                fullName TEXT NOT NULL,
                peerId TEXT NOT NULL,
                allergies TEXT DEFAULT '',
                notes TEXT DEFAULT '',
                linkedAt INTEGER NOT NULL,
                lastSyncAt INTEGER,
                isFavorite INTEGER NOT NULL DEFAULT 0,
                avatarColorIndex INTEGER NOT NULL DEFAULT 0,
                photoUri TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())

        db.execSQL("CREATE UNIQUE INDEX idx_paciente_owner_peer ON paciente_clinico(ownerPeerId, peerId)")
        db.execSQL("CREATE INDEX idx_paciente_clinico_nombre ON paciente_clinico(ownerPeerId, fullName)")
        db.execSQL("CREATE INDEX idx_paciente_clinico_fav ON paciente_clinico(ownerPeerId, isFavorite)")

        // --- MÓDULO 7: MÉDICOS VINCULADOS (v4) ---
        db.execSQL("""
            CREATE TABLE medico_vinculado (
                id TEXT PRIMARY KEY,
                ownerPeerId TEXT NOT NULL DEFAULT '',
                doctorPeerId TEXT NOT NULL,
                doctorName TEXT NOT NULL,
                doctorPhotoUri TEXT NOT NULL DEFAULT '',
                linkedAt INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE UNIQUE INDEX idx_medico_owner_peer ON medico_vinculado(ownerPeerId, doctorPeerId)")
        db.execSQL("CREATE INDEX idx_medico_owner ON medico_vinculado(ownerPeerId)")

        // --- MÓDULO 8: PAUTAS MÉDICAS v2 (v5) ---
        // pauta_medica_v2: pautas con fechaInicio/fechaFin y frecuenciaDiaria.
        // Distinta de pauta_medica (legacy) que usa intervaloHoras + medicamentoId.
        db.execSQL("""
            CREATE TABLE pauta_medica_v2 (
                id TEXT PRIMARY KEY,
                patientPeerId TEXT NOT NULL,
                doctorCreatorPeerId TEXT NOT NULL,
                medicacion TEXT NOT NULL,
                dosis TEXT NOT NULL,
                intervaloHoras INTEGER NOT NULL,
                fechaInicio INTEGER NOT NULL,
                fechaFin INTEGER NOT NULL,
                contenidoFirmado TEXT NOT NULL DEFAULT '',
                firma TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_pauta_v2_patient ON pauta_medica_v2(patientPeerId)")
        db.execSQL("CREATE INDEX idx_pauta_v2_doctor ON pauta_medica_v2(doctorCreatorPeerId)")

        db.execSQL("""
            CREATE TABLE registro_suministro (
                id TEXT PRIMARY KEY,
                pautaId TEXT NOT NULL,
                patientPeerId TEXT NOT NULL,
                doctorAdministeredPeerId TEXT NOT NULL,
                timestampSuministro INTEGER NOT NULL,
                temperatura REAL,       
                sintomas TEXT,           
                notas TEXT,
                contenidoFirmado TEXT NOT NULL DEFAULT '',
                firma TEXT NOT NULL DEFAULT '',
                dosisProgramada INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(pautaId) REFERENCES pauta_medica_v2(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_suministro_pauta ON registro_suministro(pautaId)")

        crearTablaMensajesRecibidos(db)
        crearTablaCodigosVinculacion(db)
        crearTablaClavesPares(db)

        Log.i("P2P_TFG", "[DB] Todas las tablas creadas (v$DATABASE_VERSION).")
    }

    // --- MÓDULO 9: ANTI-REPLAY (v8) ---
    // Guarda el msgId de cada sobre P2P ya procesado: si llega otra vez
    // (reenvío o ataque de repetición) no se vuelve a aplicar.
    private fun crearTablaMensajesRecibidos(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS mensaje_recibido (
                msgId TEXT PRIMARY KEY,
                senderPeerId TEXT NOT NULL,
                receivedAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mensaje_recibido_fecha ON mensaje_recibido(receivedAt)")
    }

    // --- MÓDULO 10: CÓDIGOS DE VINCULACIÓN (v9) ---
    // Tokens de un solo uso que el paciente muestra en su QR. El cuidador
    // los devuelve en LINK_DOCTOR y aquí se consumen (se borran al usarse).
    private fun crearTablaCodigosVinculacion(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS codigo_vinculacion (
                token TEXT PRIMARY KEY,
                ownerPeerId TEXT NOT NULL,
                expiresAt INTEGER NOT NULL
            )
        """.trimIndent())
    }

    // --- MÓDULO 11: CLAVES DE CIFRADO DE LOS PARES (v10) ---
    // Una fila por relación: clave X25519 vigente del otro extremo (verificada
    // con su firma Ed25519) y la última época de MI clave que ese par ha
    // confirmado (para saber a quién reenviar KEY_UPDATE y cuándo purgar).
    // Borrar la fila = revocar la relación: ya no se puede cifrar para ese par.
    private fun crearTablaClavesPares(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS clave_par (
                ownerPeerId TEXT NOT NULL,
                peerId TEXT NOT NULL,
                epoch INTEGER NOT NULL,
                encPublicKey TEXT NOT NULL,
                miEpochConfirmado INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (ownerPeerId, peerId)
            )
        """.trimIndent())
    }

    // --- MÓDULO 3b: COLA DE ENVÍO OFFLINE (sync_log, v11) ---
    // Cada mensaje saliente se guarda aquí ANTES de intentar enviarlo, con su
    // contenido en claro (la BD entera está cifrada con SQLCipher). Un bucle
    // en segundo plano reintenta los SEND_PENDING hasta recibir un ACK firmado.
    // El msgId se conserva entre reintentos: si el primer intento llegó pero se
    // perdió el ACK, el receptor lo reconoce como repetido y no lo aplica dos veces.
    private fun crearTablaSyncLog(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_log (
                logId INTEGER PRIMARY KEY AUTOINCREMENT,
                ownerPeerId TEXT NOT NULL,
                msgId TEXT NOT NULL UNIQUE,
                destPeerId TEXT NOT NULL,
                tipoMensaje TEXT NOT NULL,
                contenido TEXT NOT NULL,
                tablaAfectada TEXT NOT NULL DEFAULT '',
                registroAfectadoId TEXT NOT NULL DEFAULT '',
                accion TEXT NOT NULL,
                intentos INTEGER NOT NULL DEFAULT 0,
                timestampCreacion INTEGER NOT NULL,
                timestampModificacion INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_log_pendientes ON sync_log(ownerPeerId, accion)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v7..v10 → v11: tablas de seguridad, cola de envío nueva y columnas de
        // firma del autor. Los datos clínicos se conservan.
        if (oldVersion in 7..11) {
            if (oldVersion <= 10) {
                crearTablaMensajesRecibidos(db)
                crearTablaCodigosVinculacion(db)
                crearTablaClavesPares(db)
                // La sync_log antigua solo era un registro sin contenido: no se puede reenviar
                db.execSQL("DROP TABLE IF EXISTS sync_log")
                crearTablaSyncLog(db)
                listOf("pauta_medica_v2", "registro_suministro").forEach { tabla ->
                    db.execSQL("ALTER TABLE $tabla ADD COLUMN contenidoFirmado TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE $tabla ADD COLUMN firma TEXT NOT NULL DEFAULT ''")
                }
            }
            // v12: identificador común de la toma programada
            db.execSQL("ALTER TABLE registro_suministro ADD COLUMN dosisProgramada INTEGER NOT NULL DEFAULT 0")
            Log.i("P2P_TFG", "[DB] Upgrade $oldVersion -> $newVersion: seguridad, cola de envío y dosis programada.")
            return
        }
        Log.i("P2P_TFG", "[DB] Upgrade $oldVersion -> $newVersion. Recreando tablas...")
        listOf(
            "clave_par", "codigo_vinculacion", "mensaje_recibido", "registro_suministro", "pauta_medica_v2",
            "medico_vinculado", "paciente_clinico", "auth_profile",
            "historial_clinico", "sync_log", "toma_diaria",
            "pauta_medica", "medicamento", "cuidador", "paciente", "usuario"
        ).forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

    // ══════════════════════════════════════════════════════════════
    // ══ AUTENTICACIÓN (auth_profile) ════════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun getAuthProfile(): com.alberto.medp2p_poc.data.model.AuthProfile? {
        val db = this.getReadableDatabase(dbPassphrase)
        val cursor = db.rawQuery("SELECT * FROM auth_profile LIMIT 1", null)
        return try {
            if (cursor.moveToFirst()) {
                com.alberto.medp2p_poc.data.model.AuthProfile(
                    peerId       = cursor.getString(0),
                    displayName  = cursor.getString(1),
                    role         = com.alberto.medp2p_poc.data.model.UserRole.valueOf(cursor.getString(2)),
                    passwordHash = cursor.getBlob(3),
                    passwordSalt = cursor.getBlob(4),
                    photoUri     = cursor.getString(6),
                    lastLoginAt  = cursor.getLong(7)
                )
            } else null
        } finally {
            cursor.close()
            // db.close()
        }
    }

    fun insertAuthProfile(profile: com.alberto.medp2p_poc.data.model.AuthProfile) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                """INSERT INTO auth_profile
                   (peerId, displayName, role, passwordHash, passwordSalt, createdAt, photoUri, lastLoginAt)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                arrayOf(
                    profile.peerId, profile.displayName, profile.role.name,
                    profile.passwordHash, profile.passwordSalt,
                    System.currentTimeMillis(), profile.photoUri, profile.lastLoginAt
                )
            )
            Log.i("P2P_TFG", "[DB] Perfil auth guardado: ${profile.displayName}")
        } finally {
            // db.close()
        }
    }

    fun actualizarPerfil(peerId: String, nuevoNombre: String, photoUri: String) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                "UPDATE auth_profile SET displayName = ?, photoUri = ? WHERE peerId = ?",
                arrayOf(nuevoNombre, photoUri, peerId)
            )
            Log.d("P2P_TFG", "[DB] Perfil actualizado: $nuevoNombre | foto=$photoUri")
        } finally {
            // db.close()
        }
    }

    fun actualizarUltimoLogin(peerId: String) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                "UPDATE auth_profile SET lastLoginAt = ? WHERE peerId = ?",
                arrayOf(System.currentTimeMillis(), peerId)
            )
        } finally {
            // db.close()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ HISTORIAL CLÍNICO (con ownerPeerId) ═════════════════════
    // ══════════════════════════════════════════════════════════════

    fun guardarRegistroMedico(
        record: com.alberto.medp2p_poc.data.model.MedicalRecord,
        ownerPeerId: String = ""
    ) {
        val db = this.getWritableDatabase(dbPassphrase)
        val isMineInt = if (record.isMine) 1 else 0
        try {
            db.execSQL(
                """INSERT OR IGNORE INTO historial_clinico
                   (id, ownerPeerId, patientId, text, timestamp, isMine, senderAlias)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                arrayOf(record.id, ownerPeerId, record.patientId,
                    record.text, record.timestamp, isMineInt, record.senderAlias)
            )
        } finally {
            // db.close()
        }
    }

    fun obtenerHistorial(
        patientId: String,
        ownerPeerId: String
    ): List<com.alberto.medp2p_poc.data.model.MedicalRecord> {
        val lista = mutableListOf<com.alberto.medp2p_poc.data.model.MedicalRecord>()
        val db = this.getReadableDatabase(dbPassphrase)
        val cursor = db.rawQuery(
            """SELECT id, patientId, text, timestamp, isMine, senderAlias
               FROM historial_clinico
               WHERE patientId = ? AND ownerPeerId = ?
               ORDER BY timestamp ASC""",
            arrayOf(patientId, ownerPeerId)
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    lista.add(com.alberto.medp2p_poc.data.model.MedicalRecord(
                        id          = cursor.getString(0),
                        patientId   = cursor.getString(1),
                        text        = cursor.getString(2),
                        timestamp   = cursor.getLong(3),
                        isMine      = cursor.getInt(4) == 1,
                        senderAlias = cursor.getString(5)
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
            // db.close()
        }
        return lista
    }

    // ══════════════════════════════════════════════════════════════
    // ══ DIRECTORIO CLÍNICO (paciente_clinico con ownerPeerId) ═══
    // ══════════════════════════════════════════════════════════════

    fun insertarPacienteClinico(
        patient: com.alberto.medp2p_poc.data.model.Patient,
        ownerPeerId: String
    ) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                """INSERT OR REPLACE INTO paciente_clinico
                   (id, ownerPeerId, fullName, peerId, allergies, notes,
                    linkedAt, lastSyncAt, isFavorite, avatarColorIndex,photoUri)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                arrayOf(
                    patient.id, ownerPeerId, patient.fullName, patient.peerId,
                    patient.allergies, patient.notes, patient.linkedAt,
                    patient.lastSyncAt, if (patient.isFavorite) 1 else 0,
                    patient.avatarColorIndex, patient.photoUri
                )
            )
        } finally {
            // db.close()
        }
    }

    fun obtenerPacientesClinico(ownerPeerId: String): List<com.alberto.medp2p_poc.data.model.Patient> {
        val lista = mutableListOf<com.alberto.medp2p_poc.data.model.Patient>()
        val db = this.getReadableDatabase(dbPassphrase)
        // ── AÑADIDO 'photoUri' al final del SELECT para que existan las 10 columnas (0 a 9) ──
        val cursor = db.rawQuery(
            """SELECT id, fullName, peerId, allergies, notes, linkedAt,
                      lastSyncAt, isFavorite, avatarColorIndex, photoUri
               FROM paciente_clinico
               WHERE ownerPeerId = ?
               ORDER BY isFavorite DESC, fullName ASC""",
            arrayOf(ownerPeerId)
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    lista.add(com.alberto.medp2p_poc.data.model.Patient(
                        id               = cursor.getString(0),
                        fullName         = cursor.getString(1),
                        peerId           = cursor.getString(2),
                        allergies        = cursor.getString(3) ?: "",
                        notes            = cursor.getString(4) ?: "",
                        linkedAt         = cursor.getLong(5),
                        lastSyncAt       = if (cursor.isNull(6)) null else cursor.getLong(6),
                        isFavorite       = cursor.getInt(7) == 1,
                        avatarColorIndex = cursor.getInt(8),
                        photoUri         = cursor.getString(9) ?: "" // <--- Lee perfectamente la columna 9
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return lista
    }
    fun obtenerPacienteClinicoPorPeerId(
        peerId: String,
        ownerPeerId: String
    ): com.alberto.medp2p_poc.data.model.Patient? {
        val db = this.getReadableDatabase(dbPassphrase)
        val cursor = db.rawQuery(
            """SELECT id, fullName, peerId, allergies, notes, linkedAt,
                  lastSyncAt, isFavorite, avatarColorIndex, photoUri
           FROM paciente_clinico
           WHERE peerId = ? AND ownerPeerId = ? LIMIT 1""",
            arrayOf(peerId, ownerPeerId)
        )
        return try {
            if (cursor.moveToFirst()) {
                com.alberto.medp2p_poc.data.model.Patient(
                    id               = cursor.getString(0),
                    fullName         = cursor.getString(1),
                    peerId           = cursor.getString(2),
                    allergies        = cursor.getString(3) ?: "",
                    notes            = cursor.getString(4) ?: "",
                    linkedAt         = cursor.getLong(5),
                    lastSyncAt       = if (cursor.isNull(6)) null else cursor.getLong(6),
                    isFavorite       = cursor.getInt(7) == 1,
                    avatarColorIndex = cursor.getInt(8),
                    photoUri         = cursor.getString(9) ?: ""
                )
            } else null
        } finally {
            cursor.close()
            // db.close()
        }
    }

    fun toggleFavoritoPaciente(patientId: String) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                "UPDATE paciente_clinico SET isFavorite = CASE WHEN isFavorite=1 THEN 0 ELSE 1 END WHERE id=?",
                arrayOf(patientId)
            )
        } finally {
            // db.close()
        }
    }

    /**
     * Borra un paciente del directorio. Con [revocarClave] borra también la clave de
     * cifrado de esa relación; al desvincular se conserva hasta que el paciente
     * confirma el aviso UNLINK_DOCTOR, porque hace falta para cifrarlo.
     */
    fun eliminarPacienteClinico(patientId: String, revocarClave: Boolean = true) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            if (revocarClave) db.execSQL(
                """DELETE FROM clave_par WHERE EXISTS (SELECT 1 FROM paciente_clinico p
                   WHERE p.id = ? AND p.ownerPeerId = clave_par.ownerPeerId AND p.peerId = clave_par.peerId)""",
                arrayOf(patientId)
            )
            db.execSQL("DELETE FROM paciente_clinico WHERE id = ?", arrayOf(patientId))
        } finally {
            // db.close()
        }
    }

    fun actualizarDatosPaciente(peerId: String, ownerPeerId: String, allergies: String, notes: String) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                "UPDATE paciente_clinico SET allergies=?, notes=? WHERE peerId=? AND ownerPeerId=?",
                arrayOf(allergies, notes, peerId, ownerPeerId)
            )
        } finally {
            // db.close()
        }
    }

    // Actualiza solo allergies sin tocar notes — usado desde ProfileScreen del paciente
    fun actualizarAlergiasPaciente(peerId: String, ownerPeerId: String, allergies: String) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                "UPDATE paciente_clinico SET allergies = ? WHERE peerId = ? AND ownerPeerId = ?",
                arrayOf(allergies, peerId, ownerPeerId)
            )
        } finally {
            // db.close()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ MÉDICOS VINCULADOS (medico_vinculado) ════════════════════
    // ══════════════════════════════════════════════════════════════

    fun guardarMedicoVinculado(
        doctorPeerId: String,
        doctorName: String,
        ownerPeerId: String,
        doctorPhotoUri: String = ""
    ) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                """INSERT OR REPLACE INTO medico_vinculado
               (id, ownerPeerId, doctorPeerId, doctorName, doctorPhotoUri, linkedAt)
               VALUES (?, ?, ?, ?, ?, ?)""",
                arrayOf(java.util.UUID.randomUUID().toString(),
                    ownerPeerId, doctorPeerId, doctorName, doctorPhotoUri,
                    System.currentTimeMillis())
            )
            Log.i("P2P_TFG", "[DB] Médico vinculado: $doctorName ($doctorPeerId)")
        } finally {
            // db.close()
        }
    }

    fun obtenerMedicosVinculados(ownerPeerId: String): List<MedicoVinculado> {
        val lista = mutableListOf<MedicoVinculado>()
        val db = this.getReadableDatabase(dbPassphrase)
        val cursor = db.rawQuery(
            "SELECT doctorPeerId, doctorName, linkedAt, doctorPhotoUri FROM medico_vinculado WHERE ownerPeerId = ? ORDER BY linkedAt DESC",
            arrayOf(ownerPeerId)
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    lista.add(MedicoVinculado(
                        doctorPeerId   = cursor.getString(0),
                        doctorName     = cursor.getString(1),
                        linkedAt       = cursor.getLong(2),
                        doctorPhotoUri = cursor.getString(3)
                    ))
                } while (cursor.moveToNext())
            }
        } finally { cursor.close();
            //db.close()
        }
        return lista
    }

    // ══════════════════════════════════════════════════════════════
    // ══ AUTORIZACIÓN Y ANTI-REPLAY DE MENSAJES P2P ══════════════
    // ══════════════════════════════════════════════════════════════

    /** ¿El cuidador [doctorPeerId] está vinculado a este paciente ([ownerPeerId])? */
    fun esCuidadorVinculado(ownerPeerId: String, doctorPeerId: String): Boolean =
        existeFila(
            "SELECT 1 FROM medico_vinculado WHERE ownerPeerId = ? AND doctorPeerId = ? LIMIT 1",
            arrayOf(ownerPeerId, doctorPeerId)
        )

    /** ¿El paciente [patientPeerId] está en el directorio de este cuidador ([ownerPeerId])? */
    fun esPacienteVinculado(ownerPeerId: String, patientPeerId: String): Boolean =
        existeFila(
            "SELECT 1 FROM paciente_clinico WHERE ownerPeerId = ? AND peerId = ? LIMIT 1",
            arrayOf(ownerPeerId, patientPeerId)
        )

    private fun existeFila(sql: String, args: Array<String>): Boolean {
        val cursor = this.getReadableDatabase(dbPassphrase).rawQuery(sql, args)
        return try { cursor.moveToFirst() } finally { cursor.close() }
    }

    /**
     * Registra el msgId de forma atómica (PRIMARY KEY + CONFLICT_IGNORE).
     * Devuelve false si ya existía → el mensaje es una repetición.
     */
    fun registrarMensajeRecibido(msgId: String, senderPeerId: String, receivedAt: Long): Boolean {
        val values = android.content.ContentValues().apply {
            put("msgId", msgId)
            put("senderPeerId", senderPeerId)
            put("receivedAt", receivedAt)
        }
        val rowId = this.getWritableDatabase(dbPassphrase).insertWithOnConflict(
            "mensaje_recibido", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        return rowId != -1L
    }

    /** Libera un msgId cuyo procesamiento falló, para que un reenvío legítimo se reevalúe. */
    fun olvidarMensajeRecibido(msgId: String) {
        this.getWritableDatabase(dbPassphrase)
            .execSQL("DELETE FROM mensaje_recibido WHERE msgId = ?", arrayOf(msgId))
    }

    /** Clave de cifrado vigente de un par: (época, clave pública Base64url), o null si no hay relación. */
    fun obtenerClavePar(ownerPeerId: String, peerId: String): Pair<Int, String>? {
        val cursor = this.getReadableDatabase(dbPassphrase).rawQuery(
            "SELECT epoch, encPublicKey FROM clave_par WHERE ownerPeerId = ? AND peerId = ?",
            arrayOf(ownerPeerId, peerId)
        )
        return try {
            if (cursor.moveToFirst()) cursor.getInt(0) to cursor.getString(1) else null
        } finally { cursor.close() }
    }

    /**
     * Guarda la clave de un par solo si su época es MAYOR que la conocida
     * (impide volver a una clave antigua). Devuelve true si se guardó.
     */
    fun guardarClavePar(ownerPeerId: String, peerId: String, epoch: Int, encPublicKey: String): Boolean {
        val actual = obtenerClavePar(ownerPeerId, peerId)
        if (actual != null && actual.first >= epoch) return false
        this.getWritableDatabase(dbPassphrase).execSQL(
            """INSERT INTO clave_par (ownerPeerId, peerId, epoch, encPublicKey) VALUES (?, ?, ?, ?)
               ON CONFLICT(ownerPeerId, peerId) DO UPDATE SET epoch = excluded.epoch,
               encPublicKey = excluded.encPublicKey""",
            arrayOf(ownerPeerId, peerId, epoch, encPublicKey)
        )
        return true
    }

    /** Registra que [peerId] ya conoce mi clave de la época [epoch]. */
    fun marcarEpochConfirmado(ownerPeerId: String, peerId: String, epoch: Int) {
        this.getWritableDatabase(dbPassphrase).execSQL(
            "UPDATE clave_par SET miEpochConfirmado = MAX(miEpochConfirmado, ?) WHERE ownerPeerId = ? AND peerId = ?",
            arrayOf(epoch, ownerPeerId, peerId)
        )
    }

    /** Pares que aún no conocen mi clave de la época [epoch] (hay que enviarles KEY_UPDATE). */
    fun obtenerParesSinConfirmar(ownerPeerId: String, epoch: Int): List<String> {
        val cursor = this.getReadableDatabase(dbPassphrase).rawQuery(
            "SELECT peerId FROM clave_par WHERE ownerPeerId = ? AND miEpochConfirmado < ?",
            arrayOf(ownerPeerId, epoch.toString())
        )
        return try {
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        } finally { cursor.close() }
    }

    /** Época más antigua de mi clave que algún par aún puede usar (null si no hay pares). */
    fun minEpochConfirmado(ownerPeerId: String): Int? {
        val cursor = this.getReadableDatabase(dbPassphrase).rawQuery(
            "SELECT MIN(miEpochConfirmado) FROM clave_par WHERE ownerPeerId = ?",
            arrayOf(ownerPeerId)
        )
        return try {
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
        } finally { cursor.close() }
    }

    /** Revoca la relación criptográfica con un par (ya no se puede cifrar para él). */
    fun borrarClavePar(ownerPeerId: String, peerId: String) {
        this.getWritableDatabase(dbPassphrase).execSQL(
            "DELETE FROM clave_par WHERE ownerPeerId = ? AND peerId = ?",
            arrayOf(ownerPeerId, peerId)
        )
    }

    /** PACIENTE: el cuidador le desvincula → se borra del directorio y se revoca su clave. */
    fun desvincularCuidador(doctorPeerId: String, ownerPeerId: String) {
        val db = this.getWritableDatabase(dbPassphrase)
        db.execSQL(
            "DELETE FROM medico_vinculado WHERE doctorPeerId = ? AND ownerPeerId = ?",
            arrayOf(doctorPeerId, ownerPeerId)
        )
        borrarClavePar(ownerPeerId, doctorPeerId)
        Log.i("P2P_TFG", "[DB] Cuidador desvinculado: $doctorPeerId de owner=$ownerPeerId")
    }

    /** Guarda un código de vinculación nuevo y limpia los caducados. */
    fun guardarCodigoVinculacion(ownerPeerId: String, token: String, expiresAt: Long) {
        val db = this.getWritableDatabase(dbPassphrase)
        db.execSQL("DELETE FROM codigo_vinculacion WHERE expiresAt < ?", arrayOf(System.currentTimeMillis()))
        db.execSQL(
            "INSERT INTO codigo_vinculacion (token, ownerPeerId, expiresAt) VALUES (?, ?, ?)",
            arrayOf(token, ownerPeerId, expiresAt)
        )
    }

    /**
     * Consume el código de forma atómica: el DELETE solo afecta a una fila si
     * existe, es de este usuario y no ha caducado. Un segundo uso devuelve false.
     */
    fun consumirCodigoVinculacion(ownerPeerId: String, token: String, ahora: Long): Boolean =
        this.getWritableDatabase(dbPassphrase).delete(
            "codigo_vinculacion",
            "token = ? AND ownerPeerId = ? AND expiresAt >= ?",
            arrayOf(token, ownerPeerId, ahora.toString())
        ) == 1

    /** Purga msgIds más antiguos que la ventana de validez (esos sobres ya se rechazan por caducados). */
    fun purgarMensajesRecibidos(anterioresA: Long) {
        this.getWritableDatabase(dbPassphrase)
            .execSQL("DELETE FROM mensaje_recibido WHERE receivedAt < ?", arrayOf(anterioresA))
    }

    // ══════════════════════════════════════════════════════════════
    // ══ MEDICACIONES Y PAUTAS (legacy) ══════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun insertarMedicamento(med: com.alberto.medp2p_poc.data.model.Medicamento) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                "INSERT INTO medicamento (idMedicamento, nombreComercial, principleActivo, concentracionMg, stockActual) VALUES (?, ?, ?, ?, ?)",
                arrayOf(med.idMedicamento, med.nombreComercial, med.principleActivo, med.concentracionMg, med.stockActual)
            )
        } finally {
            // db.close()
        }
    }

    fun obtenerVademecum(): List<com.alberto.medp2p_poc.data.model.Medicamento> {
        val lista = mutableListOf<com.alberto.medp2p_poc.data.model.Medicamento>()
        val db = this.getReadableDatabase(dbPassphrase)
        val cursor = db.rawQuery("SELECT * FROM medicamento ORDER BY nombreComercial ASC", null)
        try {
            if (cursor.moveToFirst()) {
                do {
                    lista.add(com.alberto.medp2p_poc.data.model.Medicamento(
                        idMedicamento   = cursor.getString(0),
                        nombreComercial = cursor.getString(1),
                        principleActivo = cursor.getString(2) ?: "",
                        concentracionMg = cursor.getFloat(3),
                        stockActual     = cursor.getInt(4)
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
            // db.close()
        }
        return lista
    }

    fun obtenerMedicacionesActivas(
        pacientePeerId: String
    ): List<com.alberto.medp2p_poc.ui.patients.detail.ActiveMedication> {
        val lista = mutableListOf<com.alberto.medp2p_poc.ui.patients.detail.ActiveMedication>()
        val db = this.getReadableDatabase(dbPassphrase)
        val cursor = db.rawQuery(
            """SELECT m.idMedicamento, m.nombreComercial, m.principleActivo,
                      m.concentracionMg, m.stockActual,
                      p.idPauta, p.intervaloHoras
               FROM pauta_medica p
               INNER JOIN medicamento m ON p.medicamentoId = m.idMedicamento
               WHERE p.pacienteId = ?
               ORDER BY m.nombreComercial ASC""".trimIndent(),
            arrayOf(pacientePeerId)
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    val med = com.alberto.medp2p_poc.data.model.Medicamento(
                        idMedicamento   = cursor.getString(0),
                        nombreComercial = cursor.getString(1),
                        principleActivo = cursor.getString(2) ?: "",
                        concentracionMg = cursor.getFloat(3),
                        stockActual     = cursor.getInt(4)
                    )
                    val pauta = com.alberto.medp2p_poc.data.model.PautaMedica(
                        idPauta        = cursor.getString(5),
                        pacienteId     = pacientePeerId,
                        medicamentoId  = med.idMedicamento,
                        intervaloHoras = cursor.getInt(6)
                    )
                    lista.add(com.alberto.medp2p_poc.ui.patients.detail.ActiveMedication(
                        medication    = med,
                        prescription  = pauta,
                        nextDoseLabel = "Cada ${pauta.intervaloHoras}h"
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
            // db.close()
        }
        return lista
    }

    fun insertarPautaMedica(pauta: com.alberto.medp2p_poc.data.model.PautaMedica) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                "INSERT INTO pauta_medica (idPauta, pacienteId, medicamentoId, intervaloHoras) VALUES (?, ?, ?, ?)",
                arrayOf(pauta.idPauta, pauta.pacienteId, pauta.medicamentoId, pauta.intervaloHoras)
            )
        } finally {
            // db.close()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ SYNC LOG ════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    data class MensajeCola(
        val msgId: String,
        val destPeerId: String,
        val tipoMensaje: String,
        val contenido: String,
        val timestampCreacion: Long
    )

    /** Guarda un mensaje saliente en la cola como SEND_PENDING. */
    fun encolarMensaje(
        ownerPeerId: String, msgId: String, destPeerId: String, tipoMensaje: String,
        contenido: String, tabla: String = "", registroId: String = ""
    ) {
        val ahora = System.currentTimeMillis()
        this.getWritableDatabase(dbPassphrase).execSQL(
            """INSERT INTO sync_log (ownerPeerId, msgId, destPeerId, tipoMensaje, contenido,
                   tablaAfectada, registroAfectadoId, accion, timestampCreacion, timestampModificacion)
               VALUES (?, ?, ?, ?, ?, ?, ?, 'SEND_PENDING', ?, ?)""",
            arrayOf(ownerPeerId, msgId, destPeerId, tipoMensaje, contenido, tabla, registroId, ahora, ahora)
        )
    }

    /** Mensajes pendientes de envío, del más antiguo al más reciente. */
    fun obtenerMensajesPendientes(ownerPeerId: String): List<MensajeCola> {
        val cursor = this.getReadableDatabase(dbPassphrase).rawQuery(
            """SELECT msgId, destPeerId, tipoMensaje, contenido, timestampCreacion FROM sync_log
               WHERE ownerPeerId = ? AND accion = 'SEND_PENDING' ORDER BY timestampCreacion, logId""",
            arrayOf(ownerPeerId)
        )
        return try {
            buildList {
                while (cursor.moveToNext()) add(MensajeCola(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4)
                ))
            }
        } finally { cursor.close() }
    }

    /** Cambia el estado de un mensaje (DELIVERED, REJECTED...) o, con el mismo estado, cuenta un reintento. */
    fun actualizarEstadoMensaje(msgId: String, accion: String) {
        this.getWritableDatabase(dbPassphrase).execSQL(
            """UPDATE sync_log SET accion = ?, intentos = intentos + 1, timestampModificacion = ?
               WHERE msgId = ?""",
            arrayOf(accion, System.currentTimeMillis(), msgId)
        )
    }

    /** Anula los mensajes de un tipo aún pendientes para un destinatario (→ CANCELLED). */
    fun cancelarMensajesPendientes(ownerPeerId: String, destPeerId: String, tipoMensaje: String) {
        this.getWritableDatabase(dbPassphrase).execSQL(
            """UPDATE sync_log SET accion = 'CANCELLED', timestampModificacion = ?
               WHERE ownerPeerId = ? AND destPeerId = ? AND tipoMensaje = ? AND accion = 'SEND_PENDING'""",
            arrayOf(System.currentTimeMillis(), ownerPeerId, destPeerId, tipoMensaje)
        )
    }

    /** Pendientes demasiado antiguos (el receptor ya los rechazaría por caducados) → EXPIRED. */
    fun expirarMensajesPendientes(ownerPeerId: String, creadosAntesDe: Long): Int =
        this.getWritableDatabase(dbPassphrase).let { db ->
            val values = android.content.ContentValues().apply {
                put("accion", "EXPIRED")
                put("timestampModificacion", System.currentTimeMillis())
            }
            db.update("sync_log", values,
                "ownerPeerId = ? AND accion = 'SEND_PENDING' AND timestampCreacion < ?",
                arrayOf(ownerPeerId, creadosAntesDe.toString()))
        }

    /** Número de mensajes aún pendientes (para mostrarlo en la interfaz). */
    fun contarMensajesPendientes(ownerPeerId: String): Int {
        val cursor = this.getReadableDatabase(dbPassphrase).rawQuery(
            "SELECT COUNT(*) FROM sync_log WHERE ownerPeerId = ? AND accion = 'SEND_PENDING'",
            arrayOf(ownerPeerId)
        )
        return try { if (cursor.moveToFirst()) cursor.getInt(0) else 0 } finally { cursor.close() }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ REGISTROS FIRMADOS POR SU AUTOR ═════════════════════════
    // ══════════════════════════════════════════════════════════════

    /** Fila del historial de tomas: suministro + datos de su pauta + contenido firmado por su autor. */
    data class FilaToma(
        val id: String, val pautaId: String, val medicacion: String, val dosis: String,
        val intervaloHoras: Int, val timestamp: Long, val administradoPorPeerId: String,
        val temperatura: Float?, val sintomas: String?, val notas: String?,
        val contenidoFirmado: String, val firma: String, val dosisProgramada: Long
    )

    /** Todas las tomas de un paciente, de la más reciente a la más antigua (RF-08). */
    fun obtenerHistorialTomas(patientPeerId: String): List<FilaToma> {
        val cursor = this.getReadableDatabase(dbPassphrase).rawQuery(
            """SELECT rs.id, rs.pautaId, COALESCE(p.medicacion, 'Pauta desconocida'), COALESCE(p.dosis, ''),
                      COALESCE(p.intervaloHoras, 24), rs.timestampSuministro, rs.doctorAdministeredPeerId,
                      rs.temperatura, rs.sintomas, rs.notas, rs.contenidoFirmado, rs.firma, rs.dosisProgramada
               FROM registro_suministro rs
               LEFT JOIN pauta_medica_v2 p ON p.id = rs.pautaId
               WHERE rs.patientPeerId = ?
               ORDER BY rs.timestampSuministro DESC""",
            arrayOf(patientPeerId)
        )
        return try {
            buildList {
                while (cursor.moveToNext()) add(FilaToma(
                    id = cursor.getString(0), pautaId = cursor.getString(1),
                    medicacion = cursor.getString(2), dosis = cursor.getString(3),
                    intervaloHoras = cursor.getInt(4), timestamp = cursor.getLong(5),
                    administradoPorPeerId = cursor.getString(6),
                    temperatura = if (cursor.isNull(7)) null else cursor.getFloat(7),
                    sintomas = cursor.getString(8), notas = cursor.getString(9),
                    contenidoFirmado = cursor.getString(10), firma = cursor.getString(11),
                    dosisProgramada = cursor.getLong(12)
                ))
            }
        } finally { cursor.close() }
    }

    /** Pauta o suministro tal y como lo firmó su autor (para reenviarlo a otros cuidadores). */
    data class RegistroFirmadoDb(val tipo: String, val contenido: String, val firma: String, val autorPeerId: String)

    fun guardarFirmaPauta(pautaId: String, contenidoFirmado: String, firma: String) {
        this.getWritableDatabase(dbPassphrase).execSQL(
            "UPDATE pauta_medica_v2 SET contenidoFirmado = ?, firma = ? WHERE id = ? AND firma = ''",
            arrayOf(contenidoFirmado, firma, pautaId)
        )
    }

    fun guardarFirmaSuministro(suministroId: String, contenidoFirmado: String, firma: String) {
        this.getWritableDatabase(dbPassphrase).execSQL(
            "UPDATE registro_suministro SET contenidoFirmado = ?, firma = ? WHERE id = ? AND firma = ''",
            arrayOf(contenidoFirmado, firma, suministroId)
        )
    }

    /**
     * Todas las pautas y suministros firmados de un paciente, pautas primero.
     * Se usa para la sincronización inicial de un cuidador recién vinculado.
     */
    fun obtenerRegistrosFirmadosDePaciente(patientPeerId: String, tipoPauta: String, tipoSuministro: String): List<RegistroFirmadoDb> {
        val db = this.getReadableDatabase(dbPassphrase)
        val lista = mutableListOf<RegistroFirmadoDb>()
        listOf(
            Triple("pauta_medica_v2", "doctorCreatorPeerId", tipoPauta),
            Triple("registro_suministro", "doctorAdministeredPeerId", tipoSuministro)
        ).forEach { (tabla, columnaAutor, tipo) ->
            val cursor = db.rawQuery(
                "SELECT contenidoFirmado, firma, $columnaAutor FROM $tabla WHERE patientPeerId = ? AND firma != ''",
                arrayOf(patientPeerId)
            )
            try {
                while (cursor.moveToNext()) lista.add(
                    RegistroFirmadoDb(tipo, cursor.getString(0), cursor.getString(1), cursor.getString(2))
                )
            } finally { cursor.close() }
        }
        return lista
    }

    // ══════════════════════════════════════════════════════════════
    // ══ PAUTAS MÉDICAS v2 (MÓDULO 8 — v5) ══════════════════════
    // ══════════════════════════════════════════════════════════════

    fun insertarPautaMedicaV2(pauta: com.alberto.medp2p_poc.data.model.PautaMedicaV2) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                // Append-only: una pauta ya registrada (mismo id) nunca se sobrescribe
                """INSERT OR IGNORE INTO pauta_medica_v2
                   (id, patientPeerId, doctorCreatorPeerId, medicacion, dosis, intervaloHoras, fechaInicio, fechaFin)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf(
                    pauta.id, pauta.patientPeerId, pauta.doctorCreatorPeerId,
                    pauta.medicacion, pauta.dosis, pauta.intervaloHoras, // <-- CORREGIDO
                    pauta.fechaInicio, pauta.fechaFin
                )
            )
            Log.i("P2P_TFG", "[DB] PautaMedicaV2 insertada: ${pauta.medicacion} → ${pauta.patientPeerId.take(12)}")
        } finally {
            // db.close()
        }
    }

    fun insertarRegistroSuministro(registro: com.alberto.medp2p_poc.data.model.RegistroSuministro) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                // Append-only e idempotente: un suministro repetido (mismo id) se ignora
                """INSERT OR IGNORE INTO registro_suministro
                    (id, pautaId, patientPeerId, doctorAdministeredPeerId, timestampSuministro, temperatura, sintomas, notas, dosisProgramada)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf(
                    registro.id, registro.pautaId, registro.patientPeerId,
                    registro.doctorAdministeredPeerId, registro.timestampSuministro,
                    registro.temperatura, registro.sintomas, registro.notas, registro.dosisProgramada
                )
            )
            Log.i("P2P_TFG", "[DB] Suministro registrado: pauta=${registro.pautaId.take(8)}")
        } finally {
            // db.close()
        }
    }

    // QUERY COMPLEJA: pautas activas HOY de pacientes del doctor,
    // excluyendo las que ya tienen suministro registrado hoy.
    /**
     * Alertas por dosis programada: para cada pauta vigente de mis pacientes, la
     * dosis prevista que toca ahora (DosisProgramada), si todavía no hay ninguna
     * toma registrada para ella. Antes se comprobaba si había alguna toma "hoy",
     * lo que con pautas de menos de 24 h ocultaba el resto de dosis del día.
     */
    fun obtenerAlertasPendientes(
        ownerPeerId: String,
        ahora: Long = System.currentTimeMillis()
    ): List<com.alberto.medp2p_poc.data.model.AlertaDosis> {
        val pautas = mutableListOf<com.alberto.medp2p_poc.data.model.PautaMedicaV2>()
        val cursor = this.getReadableDatabase(dbPassphrase).rawQuery("""
            SELECT p.id, p.patientPeerId, p.doctorCreatorPeerId,
                   p.medicacion, p.dosis, p.intervaloHoras, p.fechaInicio, p.fechaFin
            FROM pauta_medica_v2 p
            INNER JOIN paciente_clinico pc
                ON pc.peerId = p.patientPeerId AND pc.ownerPeerId = ?
            WHERE p.fechaFin >= ?
            ORDER BY p.medicacion ASC
        """.trimIndent(), arrayOf(ownerPeerId, ahora.toString()))
        try {
            while (cursor.moveToNext()) {
                pautas.add(com.alberto.medp2p_poc.data.model.PautaMedicaV2(
                    id = cursor.getString(0), patientPeerId = cursor.getString(1),
                    doctorCreatorPeerId = cursor.getString(2), medicacion = cursor.getString(3),
                    dosis = cursor.getString(4), intervaloHoras = cursor.getInt(5),
                    fechaInicio = cursor.getLong(6), fechaFin = cursor.getLong(7)
                ))
            }
        } finally { cursor.close() }
        return pautas.mapNotNull { p ->
            val dosis = com.alberto.medp2p_poc.data.model.DosisProgramada.actual(p, ahora) ?: return@mapNotNull null
            if (obtenerTomaDeDosis(p.id, dosis) != null) null
            else com.alberto.medp2p_poc.data.model.AlertaDosis(p, dosis)
        }
    }

    /** Primera toma ya registrada para una dosis prevista: (quién, cuándo, contenido firmado). */
    data class FilaTomaDosis(val administradoPorPeerId: String, val timestamp: Long, val contenidoFirmado: String)

    fun obtenerTomaDeDosis(pautaId: String, dosisProgramada: Long): FilaTomaDosis? {
        val cursor = this.getReadableDatabase(dbPassphrase).rawQuery(
            """SELECT doctorAdministeredPeerId, timestampSuministro, contenidoFirmado FROM registro_suministro
               WHERE pautaId = ? AND dosisProgramada = ? ORDER BY timestampSuministro LIMIT 1""",
            arrayOf(pautaId, dosisProgramada.toString())
        )
        return try {
            if (cursor.moveToFirst()) FilaTomaDosis(cursor.getString(0), cursor.getLong(1), cursor.getString(2)) else null
        } finally { cursor.close() }
    }

    /**
     * Dosis previstas registradas más de una vez (posible sobredosificación) para
     * los pacientes de este cuidador, desde [desde].
     */
    fun obtenerConflictosDosis(ownerPeerId: String, desde: Long): List<com.alberto.medp2p_poc.data.model.ConflictoDosis> {
        val cursor = this.getReadableDatabase(dbPassphrase).rawQuery("""
            SELECT rs.pautaId, rs.patientPeerId, p.medicacion, rs.dosisProgramada, COUNT(*)
            FROM registro_suministro rs
            INNER JOIN pauta_medica_v2 p ON p.id = rs.pautaId
            INNER JOIN paciente_clinico pc ON pc.peerId = rs.patientPeerId AND pc.ownerPeerId = ?
            WHERE rs.dosisProgramada > 0 AND rs.dosisProgramada >= ?
            GROUP BY rs.pautaId, rs.dosisProgramada
            HAVING COUNT(*) > 1
            ORDER BY rs.dosisProgramada DESC
        """.trimIndent(), arrayOf(ownerPeerId, desde.toString()))
        return try {
            buildList {
                while (cursor.moveToNext()) add(com.alberto.medp2p_poc.data.model.ConflictoDosis(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getInt(4)))
            }
        } finally { cursor.close() }
    }

    /**
     * Último contacto confirmado con el dispositivo de un paciente (un acuse
     * firmado suyo o un mensaje suyo). Por ese dispositivo llegan las tomas de
     * los demás cuidadores, así que indica lo actualizada que está la información.
     */
    fun actualizarUltimoContacto(ownerPeerId: String, peerId: String, timestamp: Long) {
        this.getWritableDatabase(dbPassphrase).execSQL(
            "UPDATE paciente_clinico SET lastSyncAt = ? WHERE ownerPeerId = ? AND peerId = ?",
            arrayOf(timestamp, ownerPeerId, peerId)
        )
    }

    // ══════════════════════════════════════════════════════════════
    // ══ LEGACY ══════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun registrarMiPerfilLocal(peerId: String, nombre: String, esCuidador: Boolean) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                "INSERT INTO usuario (peerId, nombre, fechaRegistro) VALUES (?, ?, ?)",
                arrayOf(peerId, nombre, System.currentTimeMillis())
            )
            if (esCuidador)
                db.execSQL("INSERT INTO cuidador (usuarioPeerId, nivelPermisos) VALUES (?, ?)", arrayOf(peerId, 1))
            else
                db.execSQL("INSERT INTO paciente (usuarioPeerId) VALUES (?)", arrayOf(peerId))
        } finally {
            // db.close()
        }
    }

    fun obtenerTodosLosPacientes(): List<com.alberto.medp2p_poc.Paciente> {
        val lista = mutableListOf<com.alberto.medp2p_poc.Paciente>()
        val db = this.getReadableDatabase(dbPassphrase)
        val cursor = db.rawQuery(
            "SELECT u.peerId, u.nombre FROM paciente p INNER JOIN usuario u ON p.usuarioPeerId = u.peerId",
            null
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    lista.add(com.alberto.medp2p_poc.Paciente(
                        alias        = cursor.getString(1),
                        peerIdGlobal = cursor.getString(0)
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
            // db.close()
        }
        return lista
    }

    fun vincularPacienteExterno(peerId: String, alias: String) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.beginTransaction()
            db.execSQL(
                "INSERT OR IGNORE INTO usuario (peerId, nombre, fechaRegistro) VALUES (?, ?, ?)",
                arrayOf(peerId, alias, System.currentTimeMillis())
            )
            db.execSQL("INSERT OR IGNORE INTO paciente (usuarioPeerId) VALUES (?)", arrayOf(peerId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            // db.close()
        }
    }

    fun obtenerPautasActivasPorPaciente(
        patientPeerId: String,
        ownerPeerId: String
    ): List<com.alberto.medp2p_poc.data.model.PautaMedicaV2> {
        val lista = mutableListOf<com.alberto.medp2p_poc.data.model.PautaMedicaV2>()
        val db    = this.getReadableDatabase(dbPassphrase)
        val ahora = System.currentTimeMillis()

        android.util.Log.d("P2P_DEBUG", "[DB] Buscando pautas directas para patientPeerId=$patientPeerId")

        // Sustituido frecuenciaDiaria por intervaloHoras
        val cursor = db.rawQuery("""
        SELECT id, patientPeerId, doctorCreatorPeerId,
               medicacion, dosis, intervaloHoras, fechaInicio, fechaFin
        FROM pauta_medica_v2
        WHERE patientPeerId = ?
          AND fechaFin >= ?
        ORDER BY fechaFin ASC
    """.trimIndent(),
            arrayOf(patientPeerId, ahora.toString())
        )

        try {
            if (cursor.moveToFirst()) {
                do {
                    val pauta = com.alberto.medp2p_poc.data.model.PautaMedicaV2(
                        id                  = cursor.getString(0),
                        patientPeerId       = cursor.getString(1),
                        doctorCreatorPeerId = cursor.getString(2),
                        medicacion          = cursor.getString(3),
                        dosis               = cursor.getString(4),
                        intervaloHoras      = cursor.getInt(5), // <-- CAMBIADO: Antes frecuenciaDiaria
                        fechaInicio         = cursor.getLong(6),
                        fechaFin            = cursor.getLong(7)
                    )
                    lista.add(pauta)
                    android.util.Log.d("P2P_DEBUG", "[DB] ✅ Pauta encontrada: ${pauta.medicacion}")
                } while (cursor.moveToNext())
            } else {
                android.util.Log.w("P2P_DEBUG", "[DB] ⚠️ No hay pautas en la tabla pauta_medica_v2 para este paciente.")
            }
        } finally {
            cursor.close()
        }
        return lista
    }

    fun actualizarFotoPaciente(peerId: String, ownerPeerId: String, photoUri: String) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                "UPDATE paciente_clinico SET photoUri = ? WHERE peerId = ? AND ownerPeerId = ?",
                arrayOf(photoUri, peerId, ownerPeerId)
            )
            Log.d("P2P_TFG", "[DB] Foto actualizada para el paciente $peerId")
        } finally {
            // db.close()
        }
    }
    fun eliminarCuentaCompleta(peerId: String) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.beginTransaction()
            db.execSQL("DELETE FROM auth_profile WHERE peerId = ?", arrayOf(peerId))
            db.execSQL("DELETE FROM medico_vinculado WHERE ownerPeerId = ?", arrayOf(peerId))
            db.execSQL("DELETE FROM historial_clinico WHERE ownerPeerId = ?", arrayOf(peerId))
            db.execSQL("DELETE FROM paciente_clinico WHERE ownerPeerId = ?", arrayOf(peerId))
            db.execSQL("DELETE FROM clave_par WHERE ownerPeerId = ?", arrayOf(peerId))
            db.setTransactionSuccessful()
            Log.i("P2P_TFG", "[DB] Cuenta eliminada: $peerId")
        } finally {
            db.endTransaction()
        }
    }

    fun desvincularPaciente(patientPeerId: String, ownerPeerId: String) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                "DELETE FROM paciente_clinico WHERE peerId = ? AND ownerPeerId = ?",
                arrayOf(patientPeerId, ownerPeerId)
            )
            borrarClavePar(ownerPeerId, patientPeerId)
            Log.i("P2P_TFG", "[DB] Paciente desvinculado: $patientPeerId de owner=$ownerPeerId")
        } finally {
            // db.close()
        }
    }
    // ══════════════════════════════════════════════════════════════
    // ══ DICCIONARIO LOCAL DE FÁRMACOS (Offline-First) ═══════════
    // ══════════════════════════════════════════════════════════════

    /**
     * Busca si un código de barras ya fue registrado previamente en la base de datos local.
     * Esto permite que la app funcione sin conexión si el fármaco ya es conocido.
     */
    fun obtenerNombreMedicamentoLocal(codigoBarras: String): String? {
        val db = this.getReadableDatabase(dbPassphrase)
        val cursor = db.rawQuery(
            "SELECT nombreComercial FROM medicamento WHERE idMedicamento = ? LIMIT 1",
            arrayOf(codigoBarras)
        )
        return try {
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("P2P_TFG", "[DB] Error buscando medicamento: ${e.message}")
            null
        } finally {
            cursor.close()
        }
    }

    /**
     * Guarda un nuevo fármaco (obtenido de la API de CIMA o insertado a mano) en la base de datos local.
     * Utiliza INSERT OR REPLACE por si el usuario actualiza el nombre de un código existente.
     */
    fun guardarMedicamentoLocal(codigoBarras: String, nombre: String) {
        val db = this.getWritableDatabase(dbPassphrase)
        try {
            db.execSQL(
                """INSERT OR REPLACE INTO medicamento 
                   (idMedicamento, nombreComercial, principleActivo, concentracionMg, stockActual) 
                   VALUES (?, ?, ?, ?, ?)""",
                // Pasamos valores por defecto ("", 0.0f y 0) a los campos que no necesitamos para este flujo
                arrayOf(codigoBarras, nombre, "", 0.0f, 0)
            )
            Log.i("P2P_TFG", "[DB] Fármaco registrado en caché local: $nombre ($codigoBarras)")
        } catch (e: Exception) {
            Log.e("P2P_TFG", "[DB] Error guardando medicamento: ${e.message}")
        }
    }
}