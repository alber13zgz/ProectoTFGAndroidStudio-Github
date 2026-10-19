package com.alberto.medp2p_poc.data.p2p

import io.libp2p.core.crypto.PrivKey
import kotlinx.serialization.Serializable

// ══════════════════════════════════════════════════════════════════════
// Registro clínico (pauta o suministro) FIRMADO POR SU AUTOR.
//
// La firma del sobre solo autentica a quien lo envía en ese salto. Cuando el
// dispositivo del paciente reenvía una pauta o un suministro a los demás
// cuidadores, el sobre lo firma el paciente; esta segunda firma, que viaja
// dentro y se guarda junto al registro, permite a cualquier cuidador
// comprobar QUIÉN lo creó realmente, aunque nunca haya hablado con él.
// ══════════════════════════════════════════════════════════════════════
@Serializable
data class RegistroFirmado(
    val tipo: String,         // SEND_PAUTA | SUMINISTRO_PAUTA
    val autorPeerId: String,  // cuidador que creó la pauta / administró la dosis
    val contenido: String,    // JSON exacto del PautaPayload / SuministroPayload firmado
    val firma: String         // Ed25519 del autor sobre tipo + autor + contenido
) {
    fun verificar(): Boolean =
        CryptoUtils.verifySignature(autorPeerId, bytesFirmados(tipo, autorPeerId, contenido), firma)

    companion object {
        private fun bytesFirmados(tipo: String, autor: String, contenido: String) =
            CryptoUtils.lengthPrefixed("medp2p-record", tipo, autor, contenido)

        fun crear(identity: PrivKey, tipo: String, autorPeerId: String, contenido: String) =
            RegistroFirmado(tipo, autorPeerId, contenido,
                CryptoUtils.sign(identity, bytesFirmados(tipo, autorPeerId, contenido)))
    }
}
