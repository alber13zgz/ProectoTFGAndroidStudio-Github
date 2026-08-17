package com.alberto.medp2p_poc.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

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
        const val DATABASE_VERSION = 5
    }

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
                frecuenciaDiaria INTEGER NOT NULL,
                fechaInicio INTEGER NOT NULL,
                fechaFin INTEGER NOT NULL
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
                FOREIGN KEY(pautaId) REFERENCES pauta_medica_v2(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_suministro_pauta ON registro_suministro(pautaId)")

        Log.i("P2P_TFG", "[DB] Todas las tablas creadas (v$DATABASE_VERSION).")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i("P2P_TFG", "[DB] Upgrade $oldVersion -> $newVersion. Recreando tablas...")
        listOf(
            "registro_suministro", "pauta_medica_v2",
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
            // db.close()
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
            // db.close()
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
            // db.close()
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
            // db.close()
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
        val db = this.writableDatabase
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
                        avatarColorIndex = cursor.getInt(8),
                        photoUri         = cursor.getString(9) ?: ""
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
            // db.close()
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
        val db = this.writableDatabase
        try {
            db.execSQL(
                "UPDATE paciente_clinico SET isFavorite = CASE WHEN isFavorite=1 THEN 0 ELSE 1 END WHERE id=?",
                arrayOf(patientId)
            )
        } finally {
            // db.close()
        }
    }

    fun eliminarPacienteClinico(patientId: String) {
        val db = this.writableDatabase
        try {
            db.execSQL("DELETE FROM paciente_clinico WHERE id = ?", arrayOf(patientId))
        } finally {
            // db.close()
        }
    }

    fun actualizarDatosPaciente(peerId: String, ownerPeerId: String, allergies: String, notes: String) {
        val db = this.writableDatabase
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
        val db = this.writableDatabase
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
        val db = this.writableDatabase
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
        val db = this.readableDatabase
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
        } finally { cursor.close(); db.close() }
        return lista
    }

    // ══════════════════════════════════════════════════════════════
    // ══ MEDICACIONES Y PAUTAS (legacy) ══════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun insertarMedicamento(med: com.alberto.medp2p_poc.data.model.Medicamento) {
        val db = this.writableDatabase
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
            // db.close()
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
        val db = this.writableDatabase
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

    fun enqueueSyncLog(recordId: String, tabla: String, accion: String, ownerPeerId: String = "") {
        val db = this.writableDatabase
        try {
            db.execSQL(
                """INSERT INTO sync_log
                   (ownerPeerId, tablaAfectada, registroAfectadoId, accion, timestampModificacion)
                   VALUES (?, ?, ?, ?, ?)""",
                arrayOf(ownerPeerId, tabla, recordId, accion, System.currentTimeMillis())
            )
        } finally {
            // db.close()
        }
    }

    fun updateSyncLogStatus(recordId: String, nuevaAccion: String) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                """UPDATE sync_log SET accion=?, timestampModificacion=?
                   WHERE registroAfectadoId=? AND accion='SEND_PENDING'""",
                arrayOf(nuevaAccion, System.currentTimeMillis(), recordId)
            )
        } finally {
            // db.close()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ PAUTAS MÉDICAS v2 (MÓDULO 8 — v5) ══════════════════════
    // ══════════════════════════════════════════════════════════════

    fun insertarPautaMedicaV2(pauta: com.alberto.medp2p_poc.data.model.PautaMedicaV2) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                """INSERT OR REPLACE INTO pauta_medica_v2
                   (id, patientPeerId, doctorCreatorPeerId, medicacion, dosis, frecuenciaDiaria, fechaInicio, fechaFin)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf(
                    pauta.id, pauta.patientPeerId, pauta.doctorCreatorPeerId,
                    pauta.medicacion, pauta.dosis, pauta.frecuenciaDiaria,
                    pauta.fechaInicio, pauta.fechaFin
                )
            )
            Log.i("P2P_TFG", "[DB] PautaMedicaV2 insertada: ${pauta.medicacion} → ${pauta.patientPeerId.take(12)}")
        } finally {
            // db.close()
        }
    }

    fun insertarRegistroSuministro(registro: com.alberto.medp2p_poc.data.model.RegistroSuministro) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                """INSERT INTO registro_suministro
                   (id, pautaId, patientPeerId, doctorAdministeredPeerId, timestampSuministro)
                   VALUES (?, ?, ?, ?, ?)""",
                arrayOf(
                    registro.id, registro.pautaId, registro.patientPeerId,
                    registro.doctorAdministeredPeerId, registro.timestampSuministro
                )
            )
            Log.i("P2P_TFG", "[DB] Suministro registrado: pauta=${registro.pautaId.take(8)}")
        } finally {
            // db.close()
        }
    }

    // QUERY COMPLEJA: pautas activas HOY de pacientes del doctor,
    // excluyendo las que ya tienen suministro registrado hoy.
    fun obtenerAlertasPendientesHoy(doctorPeerId: String): List<com.alberto.medp2p_poc.data.model.PautaMedicaV2> {
        val lista = mutableListOf<com.alberto.medp2p_poc.data.model.PautaMedicaV2>()
        val db = this.readableDatabase

        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val inicioDia = cal.timeInMillis
        val finDia    = inicioDia + 86_400_000L

        val cursor = db.rawQuery("""
            SELECT p.id, p.patientPeerId, p.doctorCreatorPeerId,
                   p.medicacion, p.dosis, p.frecuenciaDiaria, p.fechaInicio, p.fechaFin
            FROM pauta_medica_v2 p
            INNER JOIN paciente_clinico pc
                ON pc.peerId = p.patientPeerId
                AND pc.ownerPeerId = ?
            WHERE p.fechaInicio <= ?
              AND p.fechaFin    >= ?
              AND p.id NOT IN (
                  SELECT DISTINCT rs.pautaId
                  FROM registro_suministro rs
                  WHERE rs.pautaId = p.id
                    AND rs.timestampSuministro >= ?
                    AND rs.timestampSuministro <  ?
              )
                ORDER BY p.medicacion ASC
        """.trimIndent(),
            arrayOf(doctorPeerId, finDia.toString(), inicioDia.toString(), inicioDia.toString(), finDia.toString())
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    lista.add(com.alberto.medp2p_poc.data.model.PautaMedicaV2(
                        id                  = cursor.getString(0),
                        patientPeerId       = cursor.getString(1),
                        doctorCreatorPeerId = cursor.getString(2),
                        medicacion          = cursor.getString(3),
                        dosis               = cursor.getString(4),
                        frecuenciaDiaria    = cursor.getInt(5),
                        fechaInicio         = cursor.getLong(6),
                        fechaFin            = cursor.getLong(7)
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
    // ══ LEGACY ══════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun registrarMiPerfilLocal(peerId: String, nombre: String, esCuidador: Boolean) {
        val db = this.writableDatabase
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
        val db = this.readableDatabase
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
        val db = this.writableDatabase
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
        val db    = this.readableDatabase
        val ahora = System.currentTimeMillis()

        val cursor = db.rawQuery("""
        SELECT p.id, p.patientPeerId, p.doctorCreatorPeerId,
               p.medicacion, p.dosis, p.frecuenciaDiaria, p.fechaInicio, p.fechaFin
        FROM pauta_medica_v2 p
        INNER JOIN paciente_clinico pc
            ON pc.peerId = p.patientPeerId
            AND pc.ownerPeerId = ?
        WHERE p.patientPeerId = ?
          AND p.fechaFin >= ?
        ORDER BY p.fechaFin ASC
    """.trimIndent(),
            arrayOf(ownerPeerId, patientPeerId, ahora.toString())
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    lista.add(com.alberto.medp2p_poc.data.model.PautaMedicaV2(
                        id                  = cursor.getString(0),
                        patientPeerId       = cursor.getString(1),
                        doctorCreatorPeerId = cursor.getString(2),
                        medicacion          = cursor.getString(3),
                        dosis               = cursor.getString(4),
                        frecuenciaDiaria    = cursor.getInt(5),
                        fechaInicio         = cursor.getLong(6),
                        fechaFin            = cursor.getLong(7)
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
            // db.close()
        }
        return lista
    }
    fun actualizarFotoPaciente(peerId: String, ownerPeerId: String, photoUri: String) {
        val db = this.writableDatabase
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
}