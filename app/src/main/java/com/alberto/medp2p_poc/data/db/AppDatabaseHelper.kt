package com.alberto.medp2p_poc.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

// ══════════════════════════════════════════════════════════════════════
// VERSIÓN 4 — Cambios estructurales:
//
//   1. Columna ownerPeerId en paciente_clinico, historial_clinico,
//      sync_log y medico_vinculado.
//      JUSTIFICACIÓN: Row-Level Security a nivel de aplicación.
//      La BD es única en el dispositivo. Sin ownerPeerId, cualquier
//      usuario que inicie sesión en el mismo dispositivo ve los datos
//      de todos los usuarios anteriores — fallo crítico de privacidad
//      en una app médica. Con ownerPeerId, cada query filtra por el
//      usuario activo. Los datos NUNCA se borran en logout; simplemente
//      dejan de ser visibles al cambiar de usuario.
//
//   2. Nueva tabla medico_vinculado para el flujo bidireccional P2P.
//      JUSTIFICACIÓN: cuando el Médico vincula al Paciente escaneando
//      su QR, envía un mensaje P2P tipo LINK_DOCTOR. El dispositivo
//      del Paciente recibe ese mensaje y guarda al Médico aquí.
//      PatientDashboardScreen.Mis Médicos lee de esta tabla.
//
//   3. Columna lastLoginAt en auth_profile.
//      JUSTIFICACIÓN: desacoplar "sesión activa" de "conexión P2P".
//      lastLoginAt se escribe en cada login exitoso, mostrando al
//      usuario feedback inmediato sin depender de la latencia del relay.
//
//   4. Columna photoUri en auth_profile.
//      JUSTIFICACIÓN: persistir la ruta local de la foto de perfil
//      entre sesiones.
// ══════════════════════════════════════════════════════════════════════

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "medp2p_offline.db"
        const val DATABASE_VERSION = 4
    }

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

        // sync_log con ownerPeerId para Row-Level Security
        db.execSQL("""
            CREATE TABLE sync_log (
                logId INTEGER PRIMARY KEY AUTOINCREMENT,
                ownerPeerId TEXT NOT NULL DEFAULT '',
                tablaAfectada TEXT NOT NULL,
                registroAfectadoId TEXT NOT NULL,
                accion TEXT NOT NULL,
                timestampModificacion INTEGER NOT NULL
            )
        """.trimIndent())

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
        // photoUri: ruta al archivo de foto en filesDir (vacío si no hay foto)
        // lastLoginAt: timestamp del último login exitoso (independiente del P2P)
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
                avatarColorIndex INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // NOTA: UNIQUE(peerId) se elimina. Ahora el UNIQUE es (ownerPeerId, peerId)
        // para permitir que dos médicos distintos tengan al mismo paciente.
        db.execSQL("CREATE UNIQUE INDEX idx_paciente_owner_peer ON paciente_clinico(ownerPeerId, peerId)")
        db.execSQL("CREATE INDEX idx_paciente_clinico_nombre ON paciente_clinico(ownerPeerId, fullName)")
        db.execSQL("CREATE INDEX idx_paciente_clinico_fav ON paciente_clinico(ownerPeerId, isFavorite)")

        // --- MÓDULO 7: MÉDICOS VINCULADOS (NUEVO v4) ---
        // Tabla en el dispositivo del PACIENTE que guarda los médicos
        // que le han vinculado vía mensaje P2P tipo LINK_DOCTOR.
        db.execSQL("""
            CREATE TABLE medico_vinculado (
                id TEXT PRIMARY KEY,
                ownerPeerId TEXT NOT NULL DEFAULT '',
                doctorPeerId TEXT NOT NULL,
                doctorName TEXT NOT NULL,
                linkedAt INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE UNIQUE INDEX idx_medico_owner_peer ON medico_vinculado(ownerPeerId, doctorPeerId)")
        db.execSQL("CREATE INDEX idx_medico_owner ON medico_vinculado(ownerPeerId)")

        Log.i("P2P_TFG", "[DB] Todas las tablas creadas (v$DATABASE_VERSION).")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i("P2P_TFG", "[DB] Upgrade $oldVersion -> $newVersion. Recreando tablas...")
        listOf(
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
        val db = this.readableDatabase
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
            db.close()
        }
    }

    fun insertAuthProfile(profile: com.alberto.medp2p_poc.data.model.AuthProfile) {
        val db = this.writableDatabase
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
            db.close()
        }
    }

    fun actualizarPerfil(peerId: String, nuevoNombre: String, photoUri: String) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                "UPDATE auth_profile SET displayName = ?, photoUri = ? WHERE peerId = ?",
                arrayOf(nuevoNombre, photoUri, peerId)
            )
            Log.d("P2P_TFG", "[DB] Perfil actualizado: $nuevoNombre | foto=$photoUri")
        } finally {
            db.close()
        }
    }

    fun actualizarUltimoLogin(peerId: String) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                "UPDATE auth_profile SET lastLoginAt = ? WHERE peerId = ?",
                arrayOf(System.currentTimeMillis(), peerId)
            )
        } finally {
            db.close()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ HISTORIAL CLÍNICO (con ownerPeerId) ═════════════════════
    // ══════════════════════════════════════════════════════════════

    fun guardarRegistroMedico(
        record: com.alberto.medp2p_poc.data.model.MedicalRecord,
        ownerPeerId: String = ""
    ) {
        val db = this.writableDatabase
        val isMineInt = if (record.isMine) 1 else 0
        try {
            db.execSQL(
                """INSERT OR REPLACE INTO historial_clinico
                   (id, ownerPeerId, patientId, text, timestamp, isMine, senderAlias)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                arrayOf(record.id, ownerPeerId, record.patientId,
                    record.text, record.timestamp, isMineInt, record.senderAlias)
            )
        } finally {
            db.close()
        }
    }

    fun obtenerHistorial(
        patientId: String,
        ownerPeerId: String
    ): List<com.alberto.medp2p_poc.data.model.MedicalRecord> {
        val lista = mutableListOf<com.alberto.medp2p_poc.data.model.MedicalRecord>()
        val db = this.readableDatabase
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
            db.close()
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
        val db = this.writableDatabase
        try {
            db.execSQL(
                """INSERT OR REPLACE INTO paciente_clinico
                   (id, ownerPeerId, fullName, peerId, allergies, notes,
                    linkedAt, lastSyncAt, isFavorite, avatarColorIndex)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                arrayOf(
                    patient.id, ownerPeerId, patient.fullName, patient.peerId,
                    patient.allergies, patient.notes, patient.linkedAt,
                    patient.lastSyncAt, if (patient.isFavorite) 1 else 0,
                    patient.avatarColorIndex
                )
            )
        } finally {
            db.close()
        }
    }

    fun obtenerPacientesClinico(ownerPeerId: String): List<com.alberto.medp2p_poc.data.model.Patient> {
        val lista = mutableListOf<com.alberto.medp2p_poc.data.model.Patient>()
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            """SELECT id, fullName, peerId, allergies, notes, linkedAt,
                      lastSyncAt, isFavorite, avatarColorIndex
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
                        avatarColorIndex = cursor.getInt(8)
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
            db.close()
        }
        return lista
    }

    fun obtenerPacienteClinicoPorPeerId(
        peerId: String,
        ownerPeerId: String
    ): com.alberto.medp2p_poc.data.model.Patient? {
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            """SELECT id, fullName, peerId, allergies, notes, linkedAt,
                      lastSyncAt, isFavorite, avatarColorIndex
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
                    avatarColorIndex = cursor.getInt(8)
                )
            } else null
        } finally {
            cursor.close()
            db.close()
        }
    }

    fun toggleFavoritoPaciente(patientId: String) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                "UPDATE paciente_clinico SET isFavorite = CASE WHEN isFavorite=1 THEN 0 ELSE 1 END WHERE id=?",
                arrayOf(patientId)
            )
        } finally { db.close() }
    }

    fun eliminarPacienteClinico(patientId: String) {
        val db = this.writableDatabase
        try {
            db.execSQL("DELETE FROM paciente_clinico WHERE id = ?", arrayOf(patientId))
        } finally { db.close() }
    }

    fun actualizarDatosPaciente(peerId: String, ownerPeerId: String, allergies: String, notes: String) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                "UPDATE paciente_clinico SET allergies=?, notes=? WHERE peerId=? AND ownerPeerId=?",
                arrayOf(allergies, notes, peerId, ownerPeerId)
            )
        } finally { db.close() }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ MÉDICOS VINCULADOS (medico_vinculado) ════════════════════
    // ══════════════════════════════════════════════════════════════

    fun guardarMedicoVinculado(
        doctorPeerId: String,
        doctorName: String,
        ownerPeerId: String
    ) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                """INSERT OR REPLACE INTO medico_vinculado
                   (id, ownerPeerId, doctorPeerId, doctorName, linkedAt)
                   VALUES (?, ?, ?, ?, ?)""",
                arrayOf(
                    java.util.UUID.randomUUID().toString(),
                    ownerPeerId, doctorPeerId, doctorName,
                    System.currentTimeMillis()
                )
            )
            Log.i("P2P_TFG", "[DB] Médico vinculado: $doctorName ($doctorPeerId)")
        } finally {
            db.close()
        }
    }

    fun obtenerMedicosVinculados(ownerPeerId: String): List<Triple<String, String, Long>> {
        val lista = mutableListOf<Triple<String, String, Long>>()
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            "SELECT doctorPeerId, doctorName, linkedAt FROM medico_vinculado WHERE ownerPeerId = ? ORDER BY linkedAt DESC",
            arrayOf(ownerPeerId)
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    lista.add(Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
            db.close()
        }
        return lista
    }

    // ══════════════════════════════════════════════════════════════
    // ══ MEDICACIONES Y PAUTAS ════════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun insertarMedicamento(med: com.alberto.medp2p_poc.data.model.Medicamento) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                "INSERT INTO medicamento (idMedicamento, nombreComercial, principleActivo, concentracionMg, stockActual) VALUES (?, ?, ?, ?, ?)",
                arrayOf(med.idMedicamento, med.nombreComercial, med.principleActivo, med.concentracionMg, med.stockActual)
            )
        } finally { db.close() }
    }

    fun obtenerVademecum(): List<com.alberto.medp2p_poc.data.model.Medicamento> {
        val lista = mutableListOf<com.alberto.medp2p_poc.data.model.Medicamento>()
        val db = this.readableDatabase
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
            db.close()
        }
        return lista
    }

    fun obtenerMedicacionesActivas(
        pacientePeerId: String
    ): List<com.alberto.medp2p_poc.ui.patients.detail.ActiveMedication> {
        val lista = mutableListOf<com.alberto.medp2p_poc.ui.patients.detail.ActiveMedication>()
        val db = this.readableDatabase
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
                        medication   = med,
                        prescription = pauta,
                        nextDoseLabel = "Cada ${pauta.intervaloHoras}h"
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
            db.close()
        }
        return lista
    }

    fun insertarPautaMedica(pauta: com.alberto.medp2p_poc.data.model.PautaMedica) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                "INSERT INTO pauta_medica (idPauta, pacienteId, medicamentoId, intervaloHoras) VALUES (?, ?, ?, ?)",
                arrayOf(pauta.idPauta, pauta.pacienteId, pauta.medicamentoId, pauta.intervaloHoras)
            )
        } finally { db.close() }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ SYNC LOG ════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun enqueueSyncLog(recordId: String, tabla: String, accion: String, ownerPeerId: String = "") {
        val db = this.writableDatabase
        try {
            db.execSQL(
                """INSERT INTO sync_log
                   (ownerPeerId, tablaAfectada, registroAfectadoId, accion, timestampModificacion)
                   VALUES (?, ?, ?, ?, ?)""",
                arrayOf(ownerPeerId, tabla, recordId, accion, System.currentTimeMillis())
            )
        } finally { db.close() }
    }

    fun updateSyncLogStatus(recordId: String, nuevaAccion: String) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                """UPDATE sync_log SET accion=?, timestampModificacion=?
                   WHERE registroAfectadoId=? AND accion='SEND_PENDING'""",
                arrayOf(nuevaAccion, System.currentTimeMillis(), recordId)
            )
        } finally { db.close() }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ LEGACY ══════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun registrarMiPerfilLocal(peerId: String, nombre: String, esCuidador: Boolean) {
        val db = this.writableDatabase
        try {
            db.execSQL("INSERT INTO usuario (peerId, nombre, fechaRegistro) VALUES (?, ?, ?)",
                arrayOf(peerId, nombre, System.currentTimeMillis()))
            if (esCuidador)
                db.execSQL("INSERT INTO cuidador (usuarioPeerId, nivelPermisos) VALUES (?, ?)", arrayOf(peerId, 1))
            else
                db.execSQL("INSERT INTO paciente (usuarioPeerId) VALUES (?)", arrayOf(peerId))
        } finally { db.close() }
    }

    fun obtenerTodosLosPacientes(): List<com.alberto.medp2p_poc.Paciente> {
        val lista = mutableListOf<com.alberto.medp2p_poc.Paciente>()
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            "SELECT u.peerId, u.nombre FROM paciente p INNER JOIN usuario u ON p.usuarioPeerId = u.peerId", null
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    lista.add(com.alberto.medp2p_poc.Paciente(
                        alias = cursor.getString(1),
                        peerIdGlobal = cursor.getString(0)
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
            db.close()
        }
        return lista
    }

    fun vincularPacienteExterno(peerId: String, alias: String) {
        val db = this.writableDatabase
        try {
            db.beginTransaction()
            db.execSQL("INSERT OR IGNORE INTO usuario (peerId, nombre, fechaRegistro) VALUES (?, ?, ?)",
                arrayOf(peerId, alias, System.currentTimeMillis()))
            db.execSQL("INSERT OR IGNORE INTO paciente (usuarioPeerId) VALUES (?)", arrayOf(peerId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }
}