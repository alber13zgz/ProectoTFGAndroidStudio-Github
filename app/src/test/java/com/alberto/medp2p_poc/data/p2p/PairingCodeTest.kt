package com.alberto.medp2p_poc.data.p2p

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCodeTest {

    private val peerId = PeerId.fromPubKey(generateKeyPair(KeyType.ED25519).second).toBase58()
    private val token = "AbCdEfGhIjKlMnOpQrSt_-"                         // 22 caracteres Base64url
    private val clave = "A".repeat(42) + "Q"                              // 43 caracteres Base64url

    @Test
    fun codigoValido_seParsea() {
        val parsed = PairingCode.parse("  " + PairingCode.codificar(peerId, token, 3, clave) + "\n")
        assertEquals(PairingCode.Parsed(peerId, token, 3, clave), parsed)
    }

    // El PeerId solo, o sin clave de cifrado (formatos antiguos del QR), ya no basta
    @Test
    fun formatosAntiguos_seRechazan() {
        assertNull(PairingCode.parse(peerId))
        assertNull(PairingCode.parse("$peerId#$token"))
    }

    @Test
    fun camposMalformados_seRechazan() {
        assertNull(PairingCode.parse("$peerId#corto#1#$clave"))
        assertNull(PairingCode.parse("$peerId#$token#0#$clave"))          // época 0 no válida
        assertNull(PairingCode.parse("$peerId#$token#1#claveCorta"))
        assertNull(PairingCode.parse("noEsUnPeerId#$token#1#$clave"))
        assertNull(PairingCode.parse("$peerId#$token#1#$clave#extra"))
    }
}
