package com.alberto.medp2p_poc.data.p2p

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoUtilsTest {

    private val aad = "medp2p|v3|SEND_PAUTA|emisor|destino".toByteArray()
    private val mensaje = "pauta: ibuprofeno 400 mg".toByteArray()

    // El destinatario descifra con la clave privada de la época indicada en la cabecera
    @Test
    fun cifradoYDescifrado_conClaveDeLaEpoca() {
        val (priv, pub) = CryptoUtils.generarParX25519()
        val sobre = CryptoUtils.seal(mensaje, pub, 7, aad)
        val abierto = CryptoUtils.open(sobre, { epoch -> if (epoch == 7) priv.copyOf() else null }, aad)
        assertArrayEquals(mensaje, abierto)
    }

    // Cambiar el emisor/destino/tipo (AAD) o un byte del cifrado invalida el tag GCM
    @Test
    fun manipulacion_seDetecta() {
        val (priv, pub) = CryptoUtils.generarParX25519()
        val sobre = CryptoUtils.seal(mensaje, pub, 1, aad)
        val resolver = { _: Int -> priv.copyOf() }

        assertThrows(Exception::class.java) {
            CryptoUtils.open(sobre, resolver, "medp2p|v3|SEND_PAUTA|OTRO|destino".toByteArray())
        }
        val alterado = sobre.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { CryptoUtils.open(alterado, resolver, aad) }
    }

    // Revocación: si la clave de esa época se purgó, el mensaje ya no se puede abrir
    @Test
    fun epocaPurgada_noSePuedeDescifrar() {
        val (_, pub) = CryptoUtils.generarParX25519()
        val sobre = CryptoUtils.seal(mensaje, pub, 1, aad)
        assertThrows(IllegalStateException::class.java) { CryptoUtils.open(sobre, { null }, aad) }
    }

    // Otra clave (otro dispositivo u otra época) no descifra
    @Test
    fun otraClave_noDescifra() {
        val (_, pub) = CryptoUtils.generarParX25519()
        val (otraPriv, _) = CryptoUtils.generarParX25519()
        val sobre = CryptoUtils.seal(mensaje, pub, 1, aad)
        assertThrows(Exception::class.java) { CryptoUtils.open(sobre, { otraPriv.copyOf() }, aad) }
    }

    // La clave extraída del senderPeerId valida las firmas de su dueño y solo las suyas
    @Test
    fun firmaVerificadaConClaveDelPeerId() {
        val (priv, pub) = generateKeyPair(KeyType.ED25519)
        val (otraPriv, _) = generateKeyPair(KeyType.ED25519)
        val peerId = PeerId.fromPubKey(pub).toBase58()
        val datos = "sobre de prueba".toByteArray()

        val clave = CryptoUtils.ed25519PubKeyFromPeerId(peerId)
        assertEquals(peerId, PeerId.fromPubKey(clave).toBase58())
        assertTrue(clave.verify(datos, priv.sign(datos)))
        assertFalse(clave.verify("sobre alterado".toByteArray(), priv.sign(datos)))
        assertFalse(clave.verify(datos, otraPriv.sign(datos)))   // suplantación
    }
}
