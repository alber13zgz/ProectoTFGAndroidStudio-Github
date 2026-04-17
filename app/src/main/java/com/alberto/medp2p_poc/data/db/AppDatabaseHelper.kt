// AppDatabaseHelper.kt
package com.alberto.medp2p_poc.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "medp2p_offline.db"
        // 👉 AUMENTADO A 2 PARA QUE ANDROID ACTUALICE LA DB AUTOMÁTICAMENTE
        const val DATABASE_VERSION = 2
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i("P2P_TFG", "[DB] Creando base de datos 'Offline-First' desde cero...")

        // --- MÓDULO 1: IDENTIDAD (CTI) ---

        // Usamos TEXT para los PeerIds (libp2p)
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

        // IMPORTANTE: idMedicamento es TEXT (UUID)
        db.execSQL("""
            CREATE TABLE medicamento (
                idMedicamento TEXT PRIMARY KEY,
                nombreComercial TEXT NOT NULL,
                principleActivo TEXT,
                concentracionMg REAL NOT NULL,
                stockActual INTEGER NOT NULL
            )
        """.trimIndent())

        // idPauta es TEXT (UUID)
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

        // indices compuestos para agilizar consultas médicas
        db.execSQL("CREATE INDEX idx_pauta_paciente ON pauta_medica(pacienteId)")
        db.execSQL("CREATE INDEX idx_pauta_medicamento ON pauta_medica(medicamentoId)")

        // --- MÓDULO 3: EJECUCIÓN Y SINCRONIZACIÓN ---

        // idToma es TEXT (UUID). confirmadoPor es opcional (puede dársela el otro móvil)
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

        // Tabla de auditoría para el P2P. logId sí es INTEGER (para orden cronológico)
        db.execSQL("""
            CREATE TABLE sync_log (
                logId INTEGER PRIMARY KEY AUTOINCREMENT,
                tablaAfectada TEXT NOT NULL,
                registroAfectadoId TEXT NOT NULL,
                accion TEXT NOT NULL,
                timestampModificacion INTEGER NOT NULL
            )
        """.trimIndent())

        // --- MÓDULO 4: HISTORIAL CLÍNICO (Chat P2P) ---
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

        Log.i("P2P_TFG", "[DB] ¡Todas las tablas y claves foráneas 'Offline-First' creadas con éxito!")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Para la PoC, simplemente borramos y recreamos si cambia la versión
        db.execSQL("DROP TABLE IF EXISTS historial_clinico") // 👉 AÑADIDO
        db.execSQL("DROP TABLE IF EXISTS sync_log")
        db.execSQL("DROP TABLE IF EXISTS toma_diaria")
        db.execSQL("DROP TABLE IF EXISTS pauta_medica")
        db.execSQL("DROP TABLE IF EXISTS medicamento")
        db.execSQL("DROP TABLE IF EXISTS cuidador")
        db.execSQL("DROP TABLE IF EXISTS paciente")
        db.execSQL("DROP TABLE IF EXISTS usuario")
        onCreate(db)
    }

    fun registrarMiPerfilLocal(peerId: String, nombre: String, esCuidador: Boolean) {
        val db = this.writableDatabase

        // 1. Guardamos el perfil genérico
        val queryUsuario = "INSERT INTO usuario (peerId, nombre, fechaRegistro) VALUES (?, ?, ?)"
        db.execSQL(queryUsuario, arrayOf(peerId, nombre, System.currentTimeMillis()))

        // 2. Guardamos el rol específico
        if (esCuidador) {
            val queryCuidador = "INSERT INTO cuidador (usuarioPeerId, nivelPermisos) VALUES (?, ?)"
            db.execSQL(queryCuidador, arrayOf(peerId, 1)) // Nivel 1 por defecto
            Log.i("P2P_TFG", "[DB] Perfil CUIDADOR creado localmente.")
        } else {
            val queryPaciente = "INSERT INTO paciente (usuarioPeerId) VALUES (?)"
            db.execSQL(queryPaciente, arrayOf(peerId))
            Log.i("P2P_TFG", "[DB] Perfil PACIENTE creado localmente.")
        }
        db.close()
    }

    fun obtenerTodosLosPacientes(): List<com.alberto.medp2p_poc.Paciente> {
        val listaPacientes = mutableListOf<com.alberto.medp2p_poc.Paciente>()
        val db = this.readableDatabase

        // Cruzamos la tabla paciente con usuario para tener el nombre
        val query = """
            SELECT u.peerId, u.nombre 
            FROM paciente p 
            INNER JOIN usuario u ON p.usuarioPeerId = u.peerId
        """.trimIndent()

        val cursor = db.rawQuery(query, null)

        if (cursor.moveToFirst()) {
            do {
                val peerId = cursor.getString(0)
                val nombre = cursor.getString(1)

                // Usamos el data class de la interfaz visual
                listaPacientes.add(
                    com.alberto.medp2p_poc.Paciente(
                        alias = nombre,
                        peerIdGlobal = peerId
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

            // 1. Lo registramos como usuario general
            val queryUsuario = "INSERT OR IGNORE INTO usuario (peerId, nombre, fechaRegistro) VALUES (?, ?, ?)"
            db.execSQL(queryUsuario, arrayOf(peerId, alias, System.currentTimeMillis()))

            // 2. Le asignamos el rol de paciente
            val queryPaciente = "INSERT OR IGNORE INTO paciente (usuarioPeerId) VALUES (?)"
            db.execSQL(queryPaciente, arrayOf(peerId))

            db.setTransactionSuccessful()
            Log.i("P2P_TFG", "[DB] Paciente vinculado exitosamente: $alias")
        } catch (e: Exception) {
            Log.e("P2P_TFG", "[DB] Error vinculando paciente: ${e.message}")
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    // 👉 NUEVO: Función para guardar un mensaje/registro en el historial
    fun guardarRegistroMedico(record: com.alberto.medp2p_poc.data.model.MedicalRecord) {
        val db = this.writableDatabase
        val query = "INSERT OR REPLACE INTO historial_clinico (id, patientId, text, timestamp, isMine, senderAlias) VALUES (?, ?, ?, ?, ?, ?)"

        // SQLite no guarda booleanos directamente, guardamos 1 (true) o 0 (false)
        val isMineInt = if (record.isMine) 1 else 0

        db.execSQL(query, arrayOf(record.id, record.patientId, record.text, record.timestamp, isMineInt, record.senderAlias))
        db.close()
        Log.i("P2P_TFG", "[DB] Registro médico guardado en el historial de ${record.patientId}")
    }

    // 👉 NUEVO: Función para recuperar todo el historial con un paciente concreto
    fun obtenerHistorial(patientId: String): List<com.alberto.medp2p_poc.data.model.MedicalRecord> {
        val listaHistorial = mutableListOf<com.alberto.medp2p_poc.data.model.MedicalRecord>()
        val db = this.readableDatabase

        // Los ordenamos por fecha de más antiguo a más nuevo
        val cursor = db.rawQuery("SELECT * FROM historial_clinico WHERE patientId = ? ORDER BY timestamp ASC", arrayOf(patientId))

        if (cursor.moveToFirst()) {
            do {
                listaHistorial.add(
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

        return listaHistorial
    }
    fun insertarMedicamento(med: com.alberto.medp2p_poc.data.model.Medicamento) {
        val db = this.writableDatabase
        val query = """
            INSERT INTO medicamento (idMedicamento, nombreComercial, principleActivo, concentracionMg, stockActual) 
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        db.execSQL(query, arrayOf(med.idMedicamento, med.nombreComercial, med.principleActivo, med.concentracionMg, med.stockActual))
        db.close()
    }

    // 👉 NUEVO: Obtener la lista completa de medicamentos
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
}