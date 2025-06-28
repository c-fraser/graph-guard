/*
Copyright 2023 c-fraser

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.github.cfraser.graphguard

import io.ktor.utils.io.core.toByteArray
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.experimental.and

/**
 * Utilities for (un)packing *Bolt 5+* [PackStream](https://neo4j.com/docs/bolt/current/packstream/)
 * data.
 */
@Suppress("TooManyFunctions")
internal object PackStream {

  /** The [Null](https://neo4j.com/docs/bolt/current/packstream/#data-type-null) type marker. */
  private const val NULL = 0xc0.toByte()

  /**
   * The [(Boolean) false](https://neo4j.com/docs/bolt/current/packstream/#data-type-boolean) type
   * marker.
   */
  private const val FALSE = 0xc2.toByte()

  /**
   * The [(Boolean) true](https://neo4j.com/docs/bolt/current/packstream/#data-type-boolean) type
   * marker.
   */
  private const val TRUE = 0xc3.toByte()

  /** The [INT_8](https://neo4j.com/docs/bolt/current/packstream/#data-type-integer) type marker. */
  private const val INT_8 = 0xc8.toByte()

  /**
   * The [INT_16](https://neo4j.com/docs/bolt/current/packstream/#data-type-integer) type marker.
   */
  private const val INT_16 = 0xc9.toByte()

  /**
   * The [INT_32](https://neo4j.com/docs/bolt/current/packstream/#data-type-integer) type marker.
   */
  private const val INT_32 = 0xca.toByte()

  /**
   * The [INT_64](https://neo4j.com/docs/bolt/current/packstream/#data-type-integer) type marker.
   */
  private const val INT_64 = 0xcb.toByte()

  /** The [Float](https://neo4j.com/docs/bolt/current/packstream/#data-type-float) type marker. */
  private const val FLOAT_64 = 0xc1.toByte()

  /** The [BYTES_8](https://neo4j.com/docs/bolt/current/packstream/#data-type-bytes) type marker. */
  private const val BYTES_8 = 0xcc.toByte()

  /**
   * The [BYTES_16](https://neo4j.com/docs/bolt/current/packstream/#data-type-bytes) type marker.
   */
  private const val BYTES_16 = 0xcd.toByte()

  /**
   * The [BYTES_32](https://neo4j.com/docs/bolt/current/packstream/#data-type-bytes) type marker.
   */
  private const val BYTES_32 = 0xce.toByte()

  /**
   * The [TINY_STRING](https://neo4j.com/docs/bolt/current/packstream/#data-type-string) type
   * marker.
   */
  private const val TINY_STRING = 0x80.toByte()

  /**
   * The [STRING_8](https://neo4j.com/docs/bolt/current/packstream/#data-type-string) type marker.
   */
  private const val STRING_8 = 0xd0.toByte()

  /**
   * The [STRING_16](https://neo4j.com/docs/bolt/current/packstream/#data-type-string) type marker.
   */
  private const val STRING_16 = 0xd1.toByte()

  /**
   * The [STRING_32](https://neo4j.com/docs/bolt/current/packstream/#data-type-string) type marker.
   */
  private const val STRING_32 = 0xd2.toByte()

  /**
   * The [TINY_LIST](https://neo4j.com/docs/bolt/current/packstream/#data-type-list) type marker.
   */
  private const val TINY_LIST = 0x90.toByte()

  /** The [LIST_8](https://neo4j.com/docs/bolt/current/packstream/#data-type-list) type marker. */
  private const val LIST_8 = 0xd4.toByte()

  /** The [LIST_16](https://neo4j.com/docs/bolt/current/packstream/#data-type-list) type marker. */
  private const val LIST_16 = 0xd5.toByte()

  /** The [LIST_32](https://neo4j.com/docs/bolt/current/packstream/#data-type-list) type marker. */
  private const val LIST_32 = 0xd6.toByte()

  /**
   * The [TINY_DICT](https://neo4j.com/docs/bolt/current/packstream/#data-type-dictionary) type
   * marker.
   */
  private const val TINY_DICT = 0xa0.toByte()

  /**
   * The [DICT_8](https://neo4j.com/docs/bolt/current/packstream/#data-type-dictionary) type marker.
   */
  private const val DICT_8 = 0xd8.toByte()

  /**
   * The [DICT_16](https://neo4j.com/docs/bolt/current/packstream/#data-type-dictionary) type
   * marker.
   */
  private const val DICT_16 = 0xd9.toByte()

  /**
   * The [DICT_32](https://neo4j.com/docs/bolt/current/packstream/#data-type-dictionary) type
   * marker.
   */
  private const val DICT_32 = 0xda.toByte()

  /**
   * The [TINY_STRUCT](https://neo4j.com/docs/bolt/current/packstream/#data-type-structure) type
   * marker.
   */
  private const val TINY_STRUCT = 0xb0.toByte()

  /**
   * The [STRUCT_8](https://neo4j.com/docs/bolt/current/packstream/#data-type-structure) type
   * marker.
   */
  private const val STRUCT_8 = 0xdc.toByte()

  /**
   * The [STRUCT_16](https://neo4j.com/docs/bolt/current/packstream/#data-type-structure) type
   * marker.
   */
  private const val STRUCT_16 = 0xdd.toByte()

  /** The maximum size of an unsigned 16-bit integer. */
  private val MAX_16 = UShort.MAX_VALUE.toInt() + 1

  /** The maximum size of an unsigned 32-bit integer. */
  private val MAX_32 = MAX_16 * 2

  /**
   * The [Date](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-date)
   * signature.
   */
  private const val DATE = 'D'.code.toByte()

  /**
   * The [Time](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-time)
   * signature.
   */
  private const val TIME = 'T'.code.toByte()

  /**
   * The
   * [LocalTime](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-localtime)
   * signature.
   */
  private const val LOCAL_TIME = 't'.code.toByte()

  /**
   * The
   * [DateTime](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-datetime)
   * signature.
   */
  private const val DATE_TIME = 'I'.code.toByte()

  /**
   * The
   * [DateTimeZoneId](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-datetimezoneid)
   * signature.
   */
  private const val DATE_TIME_ZONE_ID = 'i'.code.toByte()

  /**
   * The
   * [LocalDateTime](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-localdatetime)
   * signature.
   */
  private const val LOCAL_DATE_TIME = 'd'.code.toByte()

  /**
   * The
   * [Duration](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-duration)
   * signature.
   */
  private const val DURATION = 'E'.code.toByte()

  /** Pack bytes using a [Packer]. */
  @OptIn(ExperimentalContracts::class)
  fun pack(
      buffer: ByteBuffer = ByteBuffer.allocate(MAX_32)!!,
      block: Packer.() -> Unit
  ): ByteArray {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return Packer(buffer).apply(block).buffer.copyBytes()
  }

  /** Unpack the [ByteArray] using an [Unpacker]. */
  @OptIn(ExperimentalContracts::class)
  fun <T> ByteArray.unpack(block: Unpacker.() -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return try {
      Unpacker(this).run(block)
    } catch (bufferException: java.nio.BufferUnderflowException) {
      throw ServerError.ProtocolError.PackStreamParseError(
          "ByteBuffer underflow",
          bufferException
      )
    } catch (indexException: IndexOutOfBoundsException) {
      throw ServerError.ProtocolError.PackStreamParseError(
          "Array index out of bounds",
          indexException
      )
    }
  }

  /** A [Structure](https://neo4j.com/docs/bolt/current/packstream/#data-type-structure). */
  data class Structure(val id: Byte, val fields: List<Any?>) {

    override fun toString(): String = "${id.toHex()}: [${fields.joinToString()}]"
  }

  /**
   * [Packer] packs [PackStream] data.
   *
   * @property buffer the [ByteBuffer] with the packed data
   */
  class Packer(val buffer: ByteBuffer) {

    /** Pack [Null](https://neo4j.com/docs/bolt/current/packstream/#data-type-null). */
    @Suppress("FunctionNaming") fun `null`(): Packer = apply { buffer.put(NULL) }

    /** Pack the [Boolean](https://neo4j.com/docs/bolt/current/packstream/#data-type-boolean). */
    fun boolean(value: Boolean): Packer = apply { buffer.put(if (value) TRUE else FALSE) }

    /** Pack the [Integer](https://neo4j.com/docs/bolt/current/packstream/#data-type-integer). */
    fun integer(value: Long): Packer = apply {
      when (value) {
        in -16..Byte.MAX_VALUE -> buffer.put(value.toByte())
        in Byte.MIN_VALUE..-16 -> buffer.put(byteArrayOf(INT_8, value.toByte()))
        in Short.MIN_VALUE..Short.MAX_VALUE -> {
          buffer.put(INT_16)
          buffer.putShort(value.toShort())
        }
        in Int.MIN_VALUE..Int.MAX_VALUE -> {
          buffer.put(INT_32)
          buffer.putInt(value.toInt())
        }
        else -> {
          buffer.put(INT_64)
          buffer.putLong(value)
        }
      }
    }

    /** Pack the [Float](https://neo4j.com/docs/bolt/current/packstream/#data-type-float). */
    fun float(value: Double): Packer = apply {
      buffer.put(FLOAT_64)
      buffer.putDouble(value)
    }

    /** Pack the [Bytes](https://neo4j.com/docs/bolt/current/packstream/#data-type-bytes). */
    fun bytes(value: ByteArray): Packer = apply {
      when {
        value.size <= Byte.MAX_VALUE -> {
          buffer.put(BYTES_8)
          buffer.put(value.size.toByte())
        }
        value.size < MAX_16 -> {
          buffer.put(BYTES_16)
          buffer.putShort(value.size.toShort())
        }
        else -> {
          buffer.put(BYTES_32)
          buffer.putInt(value.size)
        }
      }
      buffer.put(value)
    }

    /** Pack the [String](https://neo4j.com/docs/bolt/current/packstream/#data-type-string). */
    fun string(value: String): Packer = apply {
      val bytes = value.toByteArray(Charsets.UTF_8)
      when {
        bytes.size < 0x10 -> {
          buffer.put((TINY_STRING.toInt() or bytes.size).toByte())
        }
        bytes.size <= Byte.MAX_VALUE -> {
          buffer.put(STRING_8)
          buffer.put(bytes.size.toByte())
        }
        bytes.size < MAX_16 -> {
          buffer.put(STRING_16)
          buffer.putShort(bytes.size.toShort())
        }
        else -> {
          buffer.put(STRING_32)
          buffer.putInt(bytes.size)
        }
      }
      buffer.put(bytes)
    }

    /** Pack the [List](https://neo4j.com/docs/bolt/current/packstream/#data-type-list). */
    fun list(value: List<*>): Packer = apply {
      when {
        value.size < 0x10 -> {
          buffer.put((TINY_LIST.toInt() or value.size).toByte())
        }
        value.size <= Byte.MAX_VALUE -> {
          buffer.put(LIST_8)
          buffer.put(value.size.toByte())
        }
        value.size < MAX_16 -> {
          buffer.put(LIST_16)
          buffer.putShort(value.size.toShort())
        }
        else -> {
          buffer.put(LIST_32)
          buffer.putInt(value.size)
        }
      }
      value.forEach(::any)
    }

    /**
     * Pack the [Dictionary](https://neo4j.com/docs/bolt/current/packstream/#data-type-dictionary).
     */
    fun dictionary(value: Map<*, *>): Packer = apply {
      when {
        value.size < 0x10 -> {
          buffer.put((TINY_DICT.toInt() or value.size).toByte())
        }
        value.size <= Byte.MAX_VALUE -> {
          buffer.put(DICT_8)
          buffer.put(value.size.toByte())
        }
        value.size < MAX_16 -> {
          buffer.put(DICT_16)
          buffer.putShort(value.size.toShort())
        }
        else -> {
          buffer.put(DICT_32)
          buffer.putInt(value.size)
        }
      }
      value.forEach { (key, value) ->
        any(key)
        any(value)
      }
    }

    /**
     * Pack the [Structure](https://neo4j.com/docs/bolt/current/packstream/#data-type-structure).
     */
    fun structure(value: Structure): Packer = apply {
      when {
        value.fields.size < 0x10 -> {
          buffer.put((TINY_STRUCT.toInt() or value.fields.size).toByte())
        }
        value.fields.size <= Byte.MAX_VALUE -> {
          buffer.put(STRUCT_8)
          buffer.put(value.fields.size.toByte())
        }
        value.fields.size < MAX_16 -> {
          buffer.put(STRUCT_16)
          buffer.putShort(value.fields.size.toShort())
        }
        else -> throw ServerError.ProtocolError.SerializationError(
            "Structure", 
            IllegalArgumentException("Structure size '${value.fields.size}' exceeds maximum supported size")
        )
      }
      buffer.put(value.id)
      value.fields.forEach(::any)
    }

    /**
     * Pack the [localDate] as a
     * [Date](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-date).
     */
    fun date(localDate: LocalDate): Packer =
        structure(Structure(DATE, listOf(localDate.toEpochDay())))

    /**
     * Pack the [offsetTime] as a
     * [Time](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-time).
     */
    fun time(offsetTime: OffsetTime): Packer =
        structure(
            Structure(
                TIME,
                listOf(offsetTime.toLocalTime().toNanoOfDay(), offsetTime.offset.totalSeconds)))

    /**
     * Pack the [localTime] as a
     * [LocalTime](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-localtime).
     */
    fun localTime(localTime: LocalTime): Packer =
        structure(Structure(LOCAL_TIME, listOf(localTime.toNanoOfDay())))

    /**
     * Pack the [zonedDateTime] as a
     * [DateTime](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-datetime).
     */
    fun dateTime(zonedDateTime: ZonedDateTime): Packer =
        when (val zone = zonedDateTime.zone) {
          is ZoneOffset ->
              structure(
                  Structure(
                      DATE_TIME,
                      listOf(
                          zonedDateTime.toInstant().epochSecond,
                          zonedDateTime.nano,
                          zone.totalSeconds)))
          else -> throw ServerError.ProtocolError.SerializationError(
              "ZonedDateTime",
              IllegalArgumentException("ZonedDateTime '$zonedDateTime' has unsupported zone type")
          )
        }

    /**
     * Pack the [zonedDateTime] as a
     * [DateTimeZoneId](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-datetimezoneid).
     */
    fun dateTimeZoneId(zonedDateTime: ZonedDateTime): Packer =
        structure(
            Structure(
                DATE_TIME_ZONE_ID,
                listOf(
                    zonedDateTime.toInstant().epochSecond,
                    zonedDateTime.nano,
                    zonedDateTime.zone.id)))

    /**
     * Pack the [localDateTime] as a
     * [LocalDateTime](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-localdatetime).
     */
    fun localDateTime(localDateTime: LocalDateTime): Packer =
        structure(
            Structure(
                LOCAL_DATE_TIME,
                listOf(localDateTime.toEpochSecond(ZoneOffset.UTC), localDateTime.nano)))

    /**
     * Pack the [duration] as a
     * [Duration](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#structure-duration).
     */
    fun duration(duration: Duration): Packer =
        structure(Structure(DURATION, listOf(0L, 0L, duration.seconds, duration.nano)))

    @Suppress("CyclomaticComplexMethod")
    private fun any(value: Any?) {
      when (value) {
        null -> `null`()
        is Boolean -> boolean(value)
        is BooleanArray -> list(listOf(value))
        is Byte -> integer(value.toLong())
        is ByteArray -> bytes(value)
        is Short -> integer(value.toLong())
        is ShortArray -> list(listOf(value))
        is Int -> integer(value.toLong())
        is IntArray -> list(listOf(value))
        is Long -> integer(value)
        is LongArray -> list(listOf(value))
        is Float -> float(value.toDouble())
        is FloatArray -> list(listOf(value))
        is Double -> float(value)
        is DoubleArray -> list(listOf(value))
        is Char -> string(value.toString())
        is CharArray -> string(String(value))
        is String -> string(value)
        is Array<*> -> list(listOf(value))
        is List<*> -> list(value)
        is Map<*, *> -> dictionary(value)
        is LocalDate -> date(value)
        is OffsetTime -> time(value)
        is LocalTime -> localTime(value)
        is ZonedDateTime -> if (value.zone is ZoneOffset) dateTime(value) else dateTimeZoneId(value)
        is LocalDateTime -> localDateTime(value)
        is Duration -> duration(value)
        is Structure -> structure(value)
        else -> throw ServerError.ProtocolError.SerializationError(
            value::class.simpleName ?: "Unknown",
            IllegalArgumentException("Value of type '${value::class.simpleName}' is not supported for PackStream serialization")
        )
      }
    }
  }

  /**
   * [Unpacker] unpacks [PackStream] data.
   *
   * @param bytes the [ByteArray] to unpack
   */
  class Unpacker(bytes: ByteArray) {

    /** A [ByteBuffer] with the data to unpack. */
    private val buffer = ByteBuffer.wrap(bytes)!!

    /** Unpack [Null](https://neo4j.com/docs/bolt/current/packstream/#data-type-null). */
    @Suppress("FunctionNaming")
    fun `null`(): Any? =
        when (val marker = buffer.get()) {
          NULL -> null
          else -> marker.unexpected()
        }

    /** Unpack a [Boolean](https://neo4j.com/docs/bolt/current/packstream/#data-type-boolean). */
    fun boolean(): Boolean =
        when (val marker = buffer.get()) {
          TRUE -> true
          FALSE -> false
          else -> marker.unexpected()
        }

    /** Unpack an [Integer](https://neo4j.com/docs/bolt/current/packstream/#data-type-integer). */
    fun integer(): Long {
      val marker = buffer.get()
      if (marker >= -16) return marker.toLong()
      return when (marker) {
        INT_8 -> buffer.get().toLong()
        INT_16 -> buffer.getShort().toLong()
        INT_32 -> buffer.getInt().toLong()
        INT_64 -> buffer.getLong()
        else -> marker.unexpected()
      }
    }

    /** Unpack a [Float](https://neo4j.com/docs/bolt/current/packstream/#data-type-float). */
    fun float(): Double =
        when (val marker = buffer.get()) {
          FLOAT_64 -> buffer.getDouble()
          else -> marker.unexpected()
        }

    /** Unpack [Bytes](https://neo4j.com/docs/bolt/current/packstream/#data-type-bytes). */
    fun bytes(): ByteArray {
      val size =
          when (val marker = buffer.get()) {
            BYTES_8 -> buffer.getUInt8()
            BYTES_16 -> buffer.getUInt16()
            BYTES_32 -> buffer.getUInt32()
            else -> marker.unexpected()
          }
      return buffer.getBytes(size)
    }

    /** Unpack a [String](https://neo4j.com/docs/bolt/current/packstream/#data-type-string). */
    fun string(): String {
      val marker = buffer.get()
      if (marker == TINY_STRING) return ""
      val size =
          when {
            marker and 0xf0.toByte() == TINY_STRING -> marker.toInt() and 0x0f
            marker == STRING_8 -> buffer.getUInt8()
            marker == STRING_16 -> buffer.getUInt16()
            marker == STRING_32 -> buffer.getUInt32()
            else -> marker.unexpected()
          }
      return String(buffer.getBytes(size), Charsets.UTF_8)
    }

    /** Unpack a [List](https://neo4j.com/docs/bolt/current/packstream/#data-type-list). */
    fun list(): List<Any?> {
      val marker = buffer.get()
      val size =
          when {
            marker and 0xf0.toByte() == TINY_LIST -> marker.toInt() and 0x0f
            marker == LIST_8 -> buffer.getUInt8()
            marker == LIST_16 -> buffer.getUInt16()
            marker == LIST_32 -> buffer.getUInt32()
            else -> marker.unexpected()
          }
      return List(size) { _ -> any() }
    }

    /**
     * Unpack a [Dictionary](https://neo4j.com/docs/bolt/current/packstream/#data-type-dictionary).
     */
    fun dictionary(): Map<String, Any?> {
      val marker = buffer.get()
      val size =
          when {
            marker and 0xf0.toByte() == TINY_DICT -> marker.toInt() and 0x0f
            marker == DICT_8 -> buffer.getUInt8()
            marker == DICT_16 -> buffer.getUInt16()
            marker == DICT_32 -> buffer.getUInt32()
            else -> marker.unexpected()
          }
      return buildMap(size) {
        repeat(size) { _ ->
          val key = string()
          val value = this@Unpacker.any()
          this += key to value
        }
      }
    }

    /**
     * Unpack a [Structure](https://neo4j.com/docs/bolt/current/packstream/#data-type-structure).
     */
    fun structure(): Structure {
      val marker = buffer.get()
      val size =
          when {
            marker and 0xf0.toByte() == TINY_STRUCT -> marker.toInt() and 0x0f
            marker == STRUCT_8 -> buffer.getUInt8()
            marker == STRUCT_16 -> buffer.getUInt16()
            else -> marker.unexpected()
          }
      val tag = buffer.get()
      val fields = List(size) { _ -> any() }
      return Structure(tag, fields)
    }

    @Suppress("CyclomaticComplexMethod")
    internal fun any(): Any? {
      val marker = buffer.run { mark().get().also { _ -> reset() } }
      return when (marker and 0xf0.toByte()) {
        TINY_STRING -> string()
        TINY_LIST -> list()
        TINY_DICT -> dictionary()
        TINY_STRUCT -> structure().toType()
        else ->
            when (marker) {
              NULL -> `null`()
              TRUE,
              FALSE -> boolean()
              INT_8,
              INT_16,
              INT_32,
              INT_64 -> integer()
              FLOAT_64 -> float()
              BYTES_8,
              BYTES_16,
              BYTES_32 -> bytes()
              STRING_8,
              STRING_16,
              STRING_32 -> string()
              LIST_8,
              LIST_16,
              LIST_32 -> list()
              DICT_8,
              DICT_16,
              DICT_32 -> dictionary()
              STRUCT_8,
              STRUCT_16 -> structure().toType()
              // TINY_INT
              else -> integer()
            }
      }
    }

    /**
     * Convert the [Structure] to a
     * [type](https://neo4j.com/docs/bolt/current/bolt/structure-semantics/#_structures).
     *
     * Returns *this* [Structure] if the [Structure.id] is unknown or unsupported.
     */
    private fun Structure.toType(): Any {
      return try {
        when (id) {
          DATE -> {
            check(fields.size == 1)
            val epochDay = fields[0] as Long
            LocalDate.ofEpochDay(epochDay)
          }
          TIME -> {
            check(fields.size == 2)
            val nanoOfDayLocal = fields[0] as Long
            val offsetSeconds = Math.toIntExact(fields[1] as Long)
            val localTime = LocalTime.ofNanoOfDay(nanoOfDayLocal)
            val offset = ZoneOffset.ofTotalSeconds(offsetSeconds)
            OffsetTime.of(localTime, offset)
          }
          LOCAL_TIME -> {
            check(fields.size == 1)
            val nanoOfDayLocal = fields[0] as Long
            LocalTime.ofNanoOfDay(nanoOfDayLocal)
          }
          DATE_TIME,
          DATE_TIME_ZONE_ID -> {
            check(fields.size == 3)
            val epochSecondLocal = fields[0] as Long
            val nano = fields[1] as Long
            val zoneId =
                if (id == DATE_TIME) {
                  val offsetSeconds = Math.toIntExact(fields[2] as Long)
                  ZoneOffset.ofTotalSeconds(offsetSeconds)
                } else {
                  val zoneId = fields[2] as String
                  ZoneId.of(zoneId)
                }
            val instant = Instant.ofEpochSecond(epochSecondLocal, nano)
            val localDateTime = LocalDateTime.ofInstant(instant, zoneId)
            ZonedDateTime.of(localDateTime, zoneId)
          }
          LOCAL_DATE_TIME -> {
            check(fields.size == 2)
            val epochSecondUtc = fields[0] as Long
            val nano = Math.toIntExact(fields[1] as Long)
            LocalDateTime.ofEpochSecond(epochSecondUtc, nano, ZoneOffset.UTC)
          }
          DURATION -> {
            check(fields.size == 4)
            val seconds = fields[2] as Long
            val nanoseconds = fields[3] as Long
            Duration.ofSeconds(seconds, nanoseconds)
          }
          else -> this
        }
      } catch (conversionException: Exception) {
        throw ServerError.ProtocolError.PackStreamParseError(
            "Structure (${Char(id.toInt())}) conversion",
            conversionException
        )
      }
    }

    private companion object {

      /** Get an unsigned 8-bit [Int] from the [ByteBuffer]. */
      fun ByteBuffer.getUInt8(): Int = get().toUByte().toInt()

      /** Get an unsigned 16-bit [Int] from the [ByteBuffer]. */
      fun ByteBuffer.getUInt16(): Int = getShort().toUShort().toInt()

      /**
       * Get an unsigned 32-bit [Int] from the [ByteBuffer].
       *
       * @throws IllegalStateException if the unsigned 32-bit value is greater than [Int.MAX_VALUE]
       */
      fun ByteBuffer.getUInt32(): Int {
        val uint32 = getInt().toUInt().toLong()
        if (uint32 > Int.MAX_VALUE) {
          throw ServerError.ProtocolError.PackStreamParseError(
              "uint32 size",
              IllegalArgumentException("Size '$uint32' exceeds maximum supported value")
          )
        }
        return uint32.toInt()
      }

      /** Get a [ByteArray] of the [size] from the [ByteBuffer]. */
      fun ByteBuffer.getBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        if (size == 0) return bytes
        get(bytes, 0, bytes.size)
        return bytes
      }

      /** Throw a [ServerError.ProtocolError] because the marker [Byte] is unexpected. */
      fun Byte.unexpected(): Nothing = throw ServerError.ProtocolError.PackStreamParseError(
          "marker byte",
          IllegalArgumentException("Unexpected PackStream marker '${toHex()}'")
      )
    }
  }

  /** Copy the *used* bytes from the [ByteBuffer] to a [ByteArray]. */
  private fun ByteBuffer.copyBytes(): ByteArray = array().sliceArray(0 until position())

  /** Convert the [Byte] to an unsigned *base16* [String]. */
  private fun Byte.toHex(): String = "0x${Integer.toHexString(toInt() and 0xff)}"
}
