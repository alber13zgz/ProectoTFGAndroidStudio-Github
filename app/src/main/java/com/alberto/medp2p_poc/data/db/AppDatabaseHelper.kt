package com.alberto.medp2p_poc.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "medp2p_offline.db"
        // ── SUBIDO A 3: Añade auth_profile + paciente_clinico ──
        const val DATABASE_VERSION = 3
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i("P2P_TFG", "[DB] Creando base de datos desde cero (v$DATABASE_VERSION)...")

        // --- MODULO 1: IDENTIDAD (CTI) ---
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

        // --- MODULO 2: DOMINIO CLINICO ---
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
                FOREIGN KEY(pacienteId) REFERENCES paciente(usuarioPeerId) ON DELETE CASCADE,
                FOREIGN KEY(medicamentoId) REFERENCES medicamento(idMedicamento) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_pauta_paciente ON pauta_medica(pacienteId)")
        db.execSQL("CREATE INDEX idx_pauta_medicamento ON pauta_medica(medicamentoId)")

        // --- MODULO 3: EJECUCION Y SINCRONIZACION ---
        db.execSQL("""
            CREATE TABLE toma_diaria (
                idToma TEXT PRIMARY KEY,
                pautaId TEXT NOT NULL,
                horaProgramada INTEGER NOT NULL,
                horaRealConsumo INTEGER,
                estado INTEGER NOT NULL,
                confirmadoPorPeerId TEXT,
                FOREIGN KEY(pautaId) REFERENCES pauta_medica(idPauta) ON DELETE CASCADE,
                FOREIGN KEY(confirmadoPorPeerId) REFERENCES usuario(peerId) ON DELETE SET NULL
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_toma_pauta ON toma_diaria(pautaId)")

        db.execSQL("""
            CREATE TABLE sync_log (
                logId INTEGER PRIMARY KEY AUTOINCREMENT,
                tablaAfectada TEXT NOT NULL,
                registroAfectadoId TEXT NOT NULL,
                accion TEXT NOT NULL,
                timestampModificacion INTEGER NOT NULL
            )
        """.trimIndent())

        // --- MODULO 4: HISTORIAL CLINICO ---
        db.execSQL("""
            CREATE TABLE historial_clinico (
                id TEXT PRIMARY KEY,
                patientId TEXT NOT NULL,
                text TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                isMine INTEGER NOT NULL,
                senderAlias TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_historial_patient ON historial_clinico(patientId)")

        // --- MODULO 5: AUTENTICACION LOCAL (NUEVO v3) ---
        db.execSQL("""
            CREATE TABLE auth_profile (
                peerId TEXT PRIMARY KEY,
                displayName TEXT NOT NULL,
                role TEXT NOT NULL,
                passwordHash BLOB NOT NULL,
                passwordSalt BLOB NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())

        // --- MODULO 6: DIRECTORIO CLINICO DE PACIENTES (NUEVO v3) ---
        db.execSQL("""
            CREATE TABLE paciente_clinico (
                id TEXT PRIMARY KEY,
                fullName TEXT NOT NULL,
                peerId TEXT NOT NULL UNIQUE,
                allergies TEXT DEFAULT '',
                notes TEXT DEFAULT '',
                linkedAt INTEGER NOT NULL,
                lastSyncAt INTEGER,
                isFavorite INTEGER NOT NULL DEFAULT 0,
                avatarColorIndex INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_paciente_clinico_nombre ON paciente_clinico(fullName)")
        db.execSQL("CREATE INDEX idx_paciente_clinico_fav ON paciente_clinico(isFavorite)")

        Log.i("P2P_TFG", "[DB] Todas las tablas creadas (v$DATABASE_VERSION).")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i("P2P_TFG", "[DB] Upgrade $oldVersion -> $newVersion. Recreando tablas...")
        db.execSQL("DROP TABLE IF EXISTS paciente_clinico")
        db.execSQL("DROP TABLE IF EXISTS auth_profile")
        db.execSQL("DROP TABLE IF EXISTS historial_clinico")
        db.execSQL("DROP TABLE IF EXISTS sync_log")
        db.execSQL("DROP TABLE IF EXISTS toma_diaria")
        db.execSQL("DROP TABLE IF EXISTS pauta_medica")
        db.execSQL("DROP TABLE IF EXISTS medicamento")
        db.execSQL("DROP TABLE IF EXISTS cuidador")
        db.execSQL("DROP TABLE IF EXISTS paciente")
        db.execSQL("DROP TABLE IF EXISTS usuario")
        onCreate(db)
    }

    // ══════════════════════════════════════════════════════════════
    // ══ AUTENTICACION (auth_profile) ════════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun getAuthProfile(): com.alberto.medp2p_poc.data.model.AuthProfile? {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM auth_profile LIMIT 1", null)
        return try {
            if (cursor.moveToFirst()) {
                com.alberto.medp2p_poc.data.model.AuthProfile(
                    peerId = cursor.getString(0),
                    displayName = cursor.getString(1),
                    role = com.alberto.medp2p_poc.data.model.UserRole.valueOf(cursor.getString(2)),
                    passwordHash = cursor.getBlob(3),
                    passwordSalt = cursor.getBlob(4)
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
                   (peerId, displayName, role, passwordHash, passwordSalt, createdAt)
                   VALUES (?, ?, ?, ?, ?, ?)""".trimIndent(),
                arrayOf(
                    profile.peerId,
                    profile.displayName,
                    profile.role.name,
                    profile.passwordHash,
                    profile.passwordSalt,
                    System.currentTimeMillis()
                )
            )
            Log.i("P2P_TFG", "[DB] Perfil auth guardado: ${profile.displayName}")
        } finally {
            db.close()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ PERFIL LEGACY (usuario) ═════════════════════════��═══════
    // ══════════════════════════════════════════════════════════════

    fun registrarMiPerfilLocal(peerId: String, nombre: String, esCuidador: Boolean) {
        val db = this.writableDatabase
        val queryUsuario = "INSERT INTO usuario (peerId, nombre, fechaRegistro) VALUES (?, ?, ?)"
        db.execSQL(queryUsuario, arrayOf(peerId, nombre, System.currentTimeMillis()))

        if (esCuidador) {
            db.execSQL("INSERT INTO cuidador (usuarioPeerId, nivelPermisos) VALUES (?, ?)", arrayOf(peerId, 1))
        } else {
            db.execSQL("INSERT INTO paciente (usuarioPeerId) VALUES (?)", arrayOf(peerId))
        }
        db.close()
    }

    // ══════════════════════════════════════════════════════════════
    // ══ HISTORIAL CLINICO ═══════════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun guardarRegistroMedico(record: com.alberto.medp2p_poc.data.model.MedicalRecord) {
        val db = this.writableDatabase
        val isMineInt = if (record.isMine) 1 else 0
        db.execSQL(
            "INSERT OR REPLACE INTO historial_clinico (id, patientId, text, timestamp, isMine, senderAlias) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf(record.id, record.patientId, record.text, record.timestamp, isMineInt, record.senderAlias)
        )
        db.close()
    }

    fun obtenerHistorial(patientId: String): List<com.alberto.medp2p_poc.data.model.MedicalRecord> {
        val lista = mutableListOf<com.alberto.medp2p_poc.data.model.MedicalRecord>()
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM historial_clinico WHERE patientId = ? ORDER BY timestamp ASC",
            arrayOf(patientId)
        )
        if (cursor.moveToFirst()) {
            do {
                lista.add(
                    com.alberto.medp2p_poc.data.model.MedicalRecord(
                        id = cursor.getString(0),
                        patientId = cursor.getString(1),
                        text = cursor.getString(2),
                        timestamp = cursor.getLong(3),
                        isMine = cursor.getInt(4) == 1,
                        senderAlias = cursor.getString(5)
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return lista
    }

    // ══════════════════════════════════════════════════════════════
    // ══ VADEMECUM (medicamento) ═════════════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun insertarMedicamento(med: com.alberto.medp2p_poc.data.model.Medicamento) {
        val db = this.writableDatabase
        db.execSQL(
            "INSERT INTO medicamento (idMedicamento, nombreComercial, principleActivo, concentracionMg, stockActual) VALUES (?, ?, ?, ?, ?)",
            arrayOf(med.idMedicamento, med.nombreComercial, med.principleActivo, med.concentracionMg, med.stockActual)
        )
        db.close()
    }

    fun obtenerVademecum(): List<com.alberto.medp2p_poc.data.model.Medicamento> {
        val lista = mutableListOf<com.alberto.medp2p_poc.data.model.Medicamento>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM medicamento ORDER BY nombreComercial ASC", null)
        if (cursor.moveToFirst()) {
            do {
                lista.add(com.alberto.medp2p_poc.data.model.Medicamento(
                    idMedicamento = cursor.getString(0),
                    nombreComercial = cursor.getString(1),
                    principleActivo = cursor.getString(2),
                    concentracionMg = cursor.getFloat(3),
                    stockActual = cursor.getInt(4)
                ))
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return lista
    }

    // ══════════════════════════════════════════════════════════════
    // ══ DIRECTORIO CLINICO (paciente_clinico) ═══════════════════
    // ══════════════════════════════════════════════════════════════

    fun insertarPacienteClinico(patient: com.alberto.medp2p_poc.data.model.Patient) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                """INSERT OR REPLACE INTO paciente_clinico 
                   (id, fullName, peerId, allergies, notes, linkedAt, lastSyncAt, isFavorite, avatarColorIndex)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                arrayOf(
                    patient.id, patient.fullName, patient.peerId,
                    patient.allergies, patient.notes, patient.linkedAt,
                    patient.lastSyncAt, if (patient.isFavorite) 1 else 0,
                    patient.avatarColorIndex
                )
            )
        } finally {
            db.close()
        }
    }

    fun obtenerPacientesClinico(): List<com.alberto.medp2p_poc.data.model.Patient> {
        val lista = mutableListOf<com.alberto.medp2p_poc.data.model.Patient>()
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM paciente_clinico ORDER BY isFavorite DESC, fullName ASC", null
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    lista.add(com.alberto.medp2p_poc.data.model.Patient(
                        id = cursor.getString(0),
                        fullName = cursor.getString(1),
                        peerId = cursor.getString(2),
                        allergies = cursor.getString(3) ?: "",
                        notes = cursor.getString(4) ?: "",
                        linkedAt = cursor.getLong(5),
                        lastSyncAt = if (cursor.isNull(6)) null else cursor.getLong(6),
                        isFavorite = cursor.getInt(7) == 1,
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

    fun toggleFavoritoPaciente(patientId: String) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                "UPDATE paciente_clinico SET isFavorite = CASE WHEN isFavorite = 1 THEN 0 ELSE 1 END WHERE id = ?",
                arrayOf(patientId)
            )
        } finally {
            db.close()
        }
    }

    fun eliminarPacienteClinico(patientId: String) {
        val db = this.writableDatabase
        try {
            db.execSQL("DELETE FROM paciente_clinico WHERE id = ?", arrayOf(patientId))
        } finally {
            db.close()
        }
    }

    fun obtenerPacienteClinicoPorPeerId(peerId: String): com.alberto.medp2p_poc.data.model.Patient? {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM paciente_clinico WHERE peerId = ? LIMIT 1", arrayOf(peerId))
        return try {
            if (cursor.moveToFirst()) {
                com.alberto.medp2p_poc.data.model.Patient(
                    id = cursor.getString(0),
                    fullName = cursor.getString(1),
                    peerId = cursor.getString(2),
                    allergies = cursor.getString(3) ?: "",
                    notes = cursor.getString(4) ?: "",
                    linkedAt = cursor.getLong(5),
                    lastSyncAt = if (cursor.isNull(6)) null else cursor.getLong(6),
                    isFavorite = cursor.getInt(7) == 1,
                    avatarColorIndex = cursor.getInt(8)
                )
            } else null
        } finally {
            cursor.close()
            db.close()
        }
    }

    fun obtenerMedicacionesActivas(
        pacientePeerId: String
    ): List<com.alberto.medp2p_poc.ui.patients.detail.ActiveMedication> {
        val lista = mutableListOf<com.alberto.medp2p_poc.ui.patients.detail.ActiveMedication>()
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT m.idMedicamento, m.nombreComercial, m.principleActivo,
                   m.concentracionMg, m.stockActual,
                   p.idPauta, p.intervaloHoras
            FROM pauta_medica p
            INNER JOIN medicamento m ON p.medicamentoId = m.idMedicamento
            WHERE p.pacienteId = ?
            ORDER BY m.nombreComercial ASC
            """.trimIndent(),
            arrayOf(pacientePeerId)
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    val med = com.alberto.medp2p_poc.data.model.Medicamento(
                        idMedicamento = cursor.getString(0),
                        nombreComercial = cursor.getString(1),
                        principleActivo = cursor.getString(2) ?: "",
                        concentracionMg = cursor.getFloat(3),
                        stockActual = cursor.getInt(4)
                    )
                    val pauta = com.alberto.medp2p_poc.data.model.PautaMedica(
                        idPauta = cursor.getString(5),
                        pacienteId = pacientePeerId,
                        medicamentoId = med.idMedicamento,
                        intervaloHoras = cursor.getInt(6)
                    )
                    lista.add(
                        com.alberto.medp2p_poc.ui.patients.detail.ActiveMedication(
                            medication = med,
                            prescription = pauta,
                            nextDoseLabel = "Cada ${pauta.intervaloHoras}h"
                        )
                    )
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
        } finally {
            db.close()
        }
    }

    fun actualizarDatosPaciente(peerId: String, allergies: String, notes: String) {
        val db = this.writableDatabase
        try {
            db.execSQL(
                "UPDATE paciente_clinico SET allergies = ?, notes = ? WHERE peerId = ?",
                arrayOf(allergies, notes, peerId)
            )
        } finally {
            db.close()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ══ LEGACY: PACIENTES BASICOS ═══════════════════════════════
    // ══════════════════════════════════════════════════════════════

    fun obtenerTodosLosPacientes(): List<com.alberto.medp2p_poc.Paciente> {
        val listaPacientes = mutableListOf<com.alberto.medp2p_poc.Paciente>()
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            "SELECT u.peerId, u.nombre FROM paciente p INNER JOIN usuario u ON p.usuarioPeerId = u.peerId",
            null
        )
        if (cursor.moveToFirst()) {
            do {
                listaPacientes.add(
                    com.alberto.medp2p_poc.Paciente(
                        alias = cursor.getString(1),
                        peerIdGlobal = cursor.getString(0)
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return listaPacientes
    }

    fun vincularPacienteExterno(peerId: String, alias: String) {
        val db = this.writableDatabase
        try {
            db.beginTransaction()
            db.execSQL(
                "INSERT OR IGNORE INTO usuario (peerId, nombre, fechaRegistro) VALUES (?, ?, ?)",
                arrayOf(peerId, alias, System.currentTimeMillis())
            )
            db.execSQL(
                "INSERT OR IGNORE INTO paciente (usuarioPeerId) VALUES (?)",
                arrayOf(peerId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }
}