package com.ronan.qmusicwatch.lyrics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Decodes QQ Music QRC payloads with the historical QQ DES S-box variant.
 * Algorithm behavior is adapted from wangqr/QQMusicDES (MIT); see THIRD_PARTY.md.
 */
object QqQrcDecoder {
    private val key1 = "!@#)(NHL".encodeToByteArray()
    private val key2 = "123ZXC!@".encodeToByteArray()
    private val key3 = "!@#)(*$%".encodeToByteArray()

    fun decode(hex: String): String {
        val data = decryptPayload(hex)
        return InflaterInputStream(ByteArrayInputStream(data)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    internal fun decryptPayload(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 16 == 0 && clean.matches(Regex("[0-9a-fA-F]+"))) { "QRC 数据格式无效" }
        var data = ByteArray(clean.length / 2) { index -> clean.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        data = crypt(data, key1, decrypt = true)
        data = crypt(data, key2, decrypt = false)
        data = crypt(data, key3, decrypt = true)
        return data
    }

    internal fun desBlockForTest(blockHex: String, keyHex: String, decrypt: Boolean = false): String {
        val block = ByteArray(8) { blockHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val key = ByteArray(8) { keyHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return crypt(block, key, decrypt).joinToString("") { "%02X".format(it) }
    }

    /** Creates a synthetic encrypted fixture without embedding third-party lyrics in the repository. */
    internal fun encodeForTest(xml: String): String {
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(xml.encodeToByteArray()) }
        }.toByteArray()
        val padded = compressed.copyOf(((compressed.size + 7) / 8) * 8)
        var data = crypt(padded, key3, decrypt = false)
        data = crypt(data, key2, decrypt = true)
        data = crypt(data, key1, decrypt = false)
        return data.joinToString("") { "%02x".format(it) }
    }

    private fun crypt(input: ByteArray, key: ByteArray, decrypt: Boolean): ByteArray {
        require(input.size % 8 == 0)
        val keys = keySchedule(key, decrypt)
        return ByteArray(input.size).also { output ->
            input.indices.step(8).forEach { offset ->
                val state = initialPermutation(input.copyOfRange(offset, offset + 8))
                repeat(15) { round ->
                    val swap = state[1]
                    state[1] = (feistel(state[1], keys[round]) xor state[0]) and mask32
                    state[0] = swap
                }
                state[0] = (feistel(state[1], keys[15]) xor state[0]) and mask32
                inversePermutation(state).copyInto(output, offset)
            }
        }
    }

    private fun keySchedule(key: ByteArray, decrypt: Boolean): Array<IntArray> {
        require(key.size == 8)
        var c = 0L
        var d = 0L
        repeat(28) { index ->
            c = c or bitNum(key, pc1[index] - 1, 31 - index)
            d = d or bitNum(key, pc1[index + 28] - 1, 31 - index)
        }
        return Array(16) { IntArray(6) }.also { schedule -> repeat(16) { round ->
            val shift = shifts[round]
            c = (((c shl shift) and mask32) or (c ushr (28 - shift))) and 0xfffffff0L
            d = (((d shl shift) and mask32) or (d ushr (28 - shift))) and 0xfffffff0L
            val target = if (decrypt) 15 - round else round
            repeat(24) { index -> schedule[target][index / 8] = schedule[target][index / 8] or bitNumIntR(c, pc2[index] - 1, 7 - index % 8).toInt() }
            (24 until 48).forEach { index -> schedule[target][index / 8] = schedule[target][index / 8] or bitNumIntR(d, pc2[index] - 28, 7 - index % 8).toInt() }
        } }
    }

    private fun initialPermutation(input: ByteArray): LongArray {
        val state = LongArray(2)
        repeat(32) { index -> state[0] = state[0] or bitNum(input, ip[index] - 1, 31 - index) }
        repeat(32) { index -> state[1] = state[1] or bitNum(input, ip[index + 32] - 1, 31 - index) }
        return state
    }

    private fun inversePermutation(state: LongArray): ByteArray = ByteArray(8) { index ->
        val offset = intArrayOf(4, 5, 6, 7, 0, 1, 2, 3)[index]
        var value = 0L
        repeat(4) { group ->
            val bit = offset + group * 8
            value = value or bitNumIntR(state[1], bit, 7 - group * 2)
            value = value or bitNumIntR(state[0], bit, 6 - group * 2)
        }
        value.toByte()
    }

    private fun feistel(right: Long, key: IntArray): Long {
        val state = right and mask32
        val t1 = bitNumIntL(state,31,0) or ((state and 0xf0000000L) ushr 1) or bitNumIntL(state,4,5) or bitNumIntL(state,3,6) or
            ((state and 0x0f000000L) ushr 3) or bitNumIntL(state,8,11) or bitNumIntL(state,7,12) or ((state and 0x00f00000L) ushr 5) or
            bitNumIntL(state,12,17) or bitNumIntL(state,11,18) or ((state and 0x000f0000L) ushr 7) or bitNumIntL(state,16,23)
        val t2 = bitNumIntL(state,15,0) or ((state and 0x0000f000L) shl 15) or bitNumIntL(state,20,5) or bitNumIntL(state,19,6) or
            ((state and 0x00000f00L) shl 13) or bitNumIntL(state,24,11) or bitNumIntL(state,23,12) or ((state and 0x000000f0L) shl 11) or
            bitNumIntL(state,28,17) or bitNumIntL(state,27,18) or ((state and 0x0000000fL) shl 9) or bitNumIntL(state,0,23)
        val blocks = intArrayOf((t1 ushr 24).toInt(), (t1 ushr 16).toInt(), (t1 ushr 8).toInt(), (t2 ushr 24).toInt(), (t2 ushr 16).toInt(), (t2 ushr 8).toInt())
        repeat(6) { blocks[it] = (blocks[it] and 0xff) xor key[it] }
        var substituted = 0L
        repeat(8) { box ->
            val six = when (box) {
                0 -> blocks[0] ushr 2
                1 -> ((blocks[0] and 3) shl 4) or (blocks[1] ushr 4)
                2 -> ((blocks[1] and 15) shl 2) or (blocks[2] ushr 6)
                3 -> blocks[2] and 63
                4 -> blocks[3] ushr 2
                5 -> ((blocks[3] and 3) shl 4) or (blocks[4] ushr 4)
                6 -> ((blocks[4] and 15) shl 2) or (blocks[5] ushr 6)
                else -> blocks[5] and 63
            }
            substituted = substituted or (sBoxes[box][sBoxBit(six)].toLong() shl (28 - box * 4))
        }
        var output = 0L
        pBox.forEachIndexed { index, position -> output = output or bitNumIntL(substituted, position - 1, index) }
        return output and mask32
    }

    private fun bitNum(input: ByteArray, bit: Int, shift: Int): Long = ((((input[(bit / 32) * 4 + 3 - (bit % 32) / 8].toInt() and 0xff) ushr (7 - bit % 8)) and 1).toLong() shl shift)
    private fun bitNumIntR(value: Long, bit: Int, shift: Int): Long = ((value ushr (31 - bit)) and 1L) shl shift
    private fun bitNumIntL(value: Long, bit: Int, shift: Int): Long = ((value shl bit) and 0x80000000L) ushr shift
    private fun sBoxBit(value: Int) = (value and 0x20) or ((value and 0x1f) ushr 1) or ((value and 1) shl 4)
    private const val mask32 = 0xffffffffL

    private val shifts = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val ip = intArrayOf(
        58,50,42,34,26,18,10,2,60,52,44,36,28,20,12,4,62,54,46,38,30,22,14,6,64,56,48,40,32,24,16,8,
        57,49,41,33,25,17,9,1,59,51,43,35,27,19,11,3,61,53,45,37,29,21,13,5,63,55,47,39,31,23,15,7,
    )
    private val pBox = intArrayOf(16,7,20,21,29,12,28,17,1,15,23,26,5,18,31,10,2,8,24,14,32,27,3,9,19,13,30,6,22,11,4,25)
    private val pc1 = intArrayOf(
        57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35,27,19,11,3,60,52,44,36,
        63,55,47,39,31,23,15,7,62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,28,20,12,4,
    )
    private val pc2 = intArrayOf(
        14,17,11,24,1,5,3,28,15,6,21,10,23,19,12,4,26,8,16,7,27,20,13,2,
        41,52,31,37,47,55,30,40,51,45,33,48,44,49,39,56,34,53,46,42,50,36,29,32,
    )
    private val sBoxes = arrayOf(
        intArrayOf(14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7,0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8,4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0,15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13),
        intArrayOf(15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10,3,13,4,7,15,2,8,15,12,0,1,10,6,9,11,5,0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15,13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9),
        intArrayOf(10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8,13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1,13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7,1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12),
        intArrayOf(7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15,13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9,10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4,3,15,0,6,10,10,13,8,9,4,5,11,12,7,2,14),
        intArrayOf(2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9,14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6,4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14,11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3),
        intArrayOf(12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11,10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8,9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6,4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13),
        intArrayOf(4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1,13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6,1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2,6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12),
        intArrayOf(13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7,1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2,7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8,2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11),
    )
}
