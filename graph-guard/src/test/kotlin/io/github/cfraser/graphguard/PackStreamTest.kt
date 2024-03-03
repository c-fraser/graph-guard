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

import io.github.cfraser.graphguard.PackStream.unpack
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

@OptIn(ExperimentalUnsignedTypes::class)
class PackStreamTest : FunSpec() {

  init {
    test("pack null") { PackStream.pack { `null`() } shouldBe byteArrayOf(0xc0.toByte()) }

    context("pack boolean") {
      withData(
          PackStream.pack { boolean(true) } to byteArrayOf(0xc3.toByte()),
          PackStream.pack { boolean(false) } to byteArrayOf(0xc2.toByte())) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("pack integer") {
      withData(
          PackStream.pack { integer(0) } to byteArrayOf(0x00),
          PackStream.pack { integer(-7) } to byteArrayOf(0xf9.toByte()),
          PackStream.pack { integer(-16) } to byteArrayOf(0xf0.toByte()),
          PackStream.pack { integer(-17) } to ubyteArrayOf(0xc8U, 0xefU).toByteArray(),
          PackStream.pack { integer(-128) } to ubyteArrayOf(0xc8U, 0x80U).toByteArray(),
          PackStream.pack { integer(-129) } to ubyteArrayOf(0xc9U, 0xffU, 0x7fU).toByteArray(),
          PackStream.pack { integer(-32768) } to ubyteArrayOf(0xc9U, 0x80U, 0x00U).toByteArray(),
          PackStream.pack { integer(-32769) } to
              ubyteArrayOf(0xcaU, 0xffU, 0xffU, 0x7fU, 0xffU).toByteArray(),
          PackStream.pack { integer(-2147483648) } to
              ubyteArrayOf(0xcaU, 0x80U, 0x00U, 0x00U, 0x00U).toByteArray(),
          PackStream.pack { integer(-2147483649) } to
              ubyteArrayOf(0xcbU, 0xffU, 0xffU, 0xffU, 0xffU, 0x7fU, 0xffU, 0xffU, 0xffU)
                  .toByteArray(),
          PackStream.pack { integer(7) } to ubyteArrayOf(0x07U).toByteArray(),
          PackStream.pack { integer(127) } to ubyteArrayOf(0x7fU).toByteArray(),
          PackStream.pack { integer(128) } to ubyteArrayOf(0xc9U, 0x00U, 0x80U).toByteArray(),
          PackStream.pack { integer(32767) } to ubyteArrayOf(0xc9U, 0x7fU, 0xffU).toByteArray(),
          PackStream.pack { integer(32768) } to
              ubyteArrayOf(0xcaU, 0x00U, 0x00U, 0x80U, 0x00U).toByteArray(),
          PackStream.pack { integer(2147483647) } to
              ubyteArrayOf(0xcaU, 0x7fU, 0xffU, 0xffU, 0xffU).toByteArray(),
          PackStream.pack { integer(2147483648) } to
              ubyteArrayOf(0xcbU, 0x00U, 0x00U, 0x00U, 0x00U, 0x80U, 0x00U, 0x00U, 0x00U)
                  .toByteArray(),
          PackStream.pack { integer(255) } to ubyteArrayOf(0xc9U, 0x00U, 0xffU).toByteArray(),
          PackStream.pack { integer(-128) } to ubyteArrayOf(0xc8U, 0x80U).toByteArray(),
          PackStream.pack { integer(65535) } to
              ubyteArrayOf(0xcaU, 0x00U, 0x00U, 0xffU, 0xffU).toByteArray(),
          PackStream.pack { integer(-32768) } to ubyteArrayOf(0xc9U, 0x80U, 0x00U).toByteArray(),
          PackStream.pack { integer(4294967295) } to
              ubyteArrayOf(0xcbU, 0x00U, 0x00U, 0x00U, 0x00U, 0xffU, 0xffU, 0xffU, 0xffU)
                  .toByteArray(),
          PackStream.pack { integer(-2147483648) } to
              ubyteArrayOf(0xcaU, 0x80U, 0x00U, 0x00U, 0x00U).toByteArray()) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("pack float") {
      withData(
          PackStream.pack { float(0.0) } to
              ubyteArrayOf(0xc1U, 0x00U, 0x00U, 0x00U, 0x00U, 0x00U, 0x00U, 0x00U, 0x00U)
                  .toByteArray(),
          PackStream.pack { float(3.14) } to
              ubyteArrayOf(0xc1U, 0x40U, 0x09U, 0x1eU, 0xb8U, 0x51U, 0xebU, 0x85U, 0x1fU)
                  .toByteArray()) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("pack bytes") {
      withData(
          PackStream.pack { bytes(byteArrayOf()) } to ubyteArrayOf(0xccU, 0x00U).toByteArray(),
          PackStream.pack { bytes(byteArrayOf(0x01, 0x02, 0x03)) } to
              ubyteArrayOf(0xccU, 0x03U, 0x01U, 0x02U, 0x03U).toByteArray(),
          byteArrayOfSize(127).let { bytes ->
            PackStream.pack { bytes(bytes) } to
                ubyteArrayOf(0xccU, 0x7fU, *bytes.toUByteArray()).toByteArray()
          },
          byteArrayOfSize(128).let { bytes ->
            PackStream.pack { bytes(bytes) } to
                ubyteArrayOf(0xcdU, 0x00U, 0x80U, *bytes.toUByteArray()).toByteArray()
          },
          byteArrayOfSize(65535).let { bytes ->
            PackStream.pack { bytes(bytes) } to
                ubyteArrayOf(0xcdU, 0xffU, 0xffU, *bytes.toUByteArray()).toByteArray()
          },
          byteArrayOfSize(65536).let { bytes ->
            PackStream.pack { bytes(bytes) } to
                ubyteArrayOf(0xceU, 0x00U, 0x01U, 0x00U, 0x00U, *bytes.toUByteArray()).toByteArray()
          }) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("pack string") {
      withData(
          PackStream.pack { string("") } to byteArrayOf(0x80.toByte()),
          PackStream.pack { string("1") } to ubyteArrayOf(0x81U, 0x31U).toByteArray(),
          PackStream.pack { string("12") } to ubyteArrayOf(0x82U, 0x31U, 0x32U).toByteArray(),
          PackStream.pack { string("123") } to
              ubyteArrayOf(0x83U, 0x31U, 0x32U, 0x33U).toByteArray(),
          stringOfSize(15).let { string ->
            PackStream.pack { string(string) } to
                ubyteArrayOf(0x8fU, *string.toByteArray(Charsets.UTF_8).toUByteArray())
                    .toByteArray()
          },
          stringOfSize(16).let { string ->
            PackStream.pack { string(string) } to
                ubyteArrayOf(0xd0U, 0x10U, *string.toByteArray(Charsets.UTF_8).toUByteArray())
                    .toByteArray()
          },
          stringOfSize(127).let { string ->
            PackStream.pack { string(string) } to
                ubyteArrayOf(0xd0U, 0x7fU, *string.toByteArray(Charsets.UTF_8).toUByteArray())
                    .toByteArray()
          },
          stringOfSize(128).let { string ->
            PackStream.pack { string(string) } to
                ubyteArrayOf(
                        0xd1U, 0x00U, 0x80U, *string.toByteArray(Charsets.UTF_8).toUByteArray())
                    .toByteArray()
          },
          stringOfSize(65535).let { string ->
            PackStream.pack { string(string) } to
                ubyteArrayOf(
                        0xd1U, 0xffU, 0xffU, *string.toByteArray(Charsets.UTF_8).toUByteArray())
                    .toByteArray()
          },
          stringOfSize(65536).let { string ->
            PackStream.pack { string(string) } to
                ubyteArrayOf(
                        0xd2U,
                        0x00U,
                        0x01U,
                        0x00U,
                        0x00U,
                        *string.toByteArray(Charsets.UTF_8).toUByteArray())
                    .toByteArray()
          }) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("pack list") {
      withData(
          PackStream.pack { list(emptyList<Any>()) } to byteArrayOf(0x90.toByte()),
          PackStream.pack { list(listOf<Any?>(null, "s", 1)) } to
              ubyteArrayOf(0x93U, 0xc0U, 0x81U, 0x73U, 0x01U).toByteArray(),
          PackStream.pack { list(listOf(1, 32768, 0)) } to
              ubyteArrayOf(0x93U, 0x01U, 0xcaU, 0x00U, 0x00U, 0x80U, 0x00U, 0x00U).toByteArray(),
          PackStream.pack { list(listOf(3.14)) } to
              ubyteArrayOf(0x91U, 0xc1U, 0x40U, 0x09U, 0x1eU, 0xb8U, 0x51U, 0xebU, 0x85U, 0x1fU)
                  .toByteArray(),
          stringOfSize(16).let { string ->
            PackStream.pack { list(listOf("short", string)) } to
                ubyteArrayOf(
                        0x92U,
                        0x85U,
                        0x73U,
                        0x68U,
                        0x6fU,
                        0x72U,
                        0x74U,
                        0xd0U,
                        0x10U,
                        *string.toByteArray(Charsets.UTF_8).toUByteArray())
                    .toByteArray()
          },
          listOfSize(15).let { list ->
            PackStream.pack { list(list) } to
                ubyteArrayOf(0x9fU, *list.map { it.toUByte() }.toUByteArray()).toByteArray()
          },
          listOfSize(16).let { list ->
            PackStream.pack { list(list) } to
                ubyteArrayOf(0xd4U, 0x10U, *list.map { it.toUByte() }.toUByteArray()).toByteArray()
          },
          listOfSize(127).let { list ->
            PackStream.pack { list(list) } to
                ubyteArrayOf(0xd4U, 0x7fU, *list.map { it.toUByte() }.toUByteArray()).toByteArray()
          },
          listOfSize(128).let { list ->
            PackStream.pack { list(list) } to
                ubyteArrayOf(0xd5U, 0x00U, 0x80U, *list.map { it.toUByte() }.toUByteArray())
                    .toByteArray()
          },
          listOfSize(65535).let { list ->
            PackStream.pack { list(list) } to
                ubyteArrayOf(0xd5U, 0xffU, 0xffU, *list.map { it.toUByte() }.toUByteArray())
                    .toByteArray()
          },
          listOfSize(65536).let { list ->
            PackStream.pack { list(list) } to
                ubyteArrayOf(
                        0xd6U,
                        0x00U,
                        0x01U,
                        0x00U,
                        0x00U,
                        *list.map { it.toUByte() }.toUByteArray())
                    .toByteArray()
          }) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("pack dictionary") {
      withData(
          PackStream.pack { dictionary(emptyMap<Any, Any>()) } to byteArrayOf(0xa0.toByte()),
          PackStream.pack { dictionary(mapOf<String, Any?>("nil" to null)) } to
              ubyteArrayOf(0xa1U, 0x83U, 0x6eU, 0x69U, 0x6cU, 0xc0U).toByteArray(),
          PackStream.pack { dictionary(mapOf<String, Any>("s" to "str")) } to
              ubyteArrayOf(0xa1U, 0x81U, 0x73U, 0x83U, 0x73U, 0x74U, 0x72U).toByteArray(),
          PackStream.pack { dictionary(mapOf("key" to "value")) } to
              ubyteArrayOf(
                      0xa1U, 0x83U, 0x6bU, 0x65U, 0x79U, 0x85U, 0x76U, 0x61U, 0x6cU, 0x75U, 0x65U)
                  .toByteArray(),
          PackStream.pack { dictionary(mapOf<String, Any>("l" to 1)) } to
              ubyteArrayOf(0xa1U, 0x81U, 0x6cU, 0x01U).toByteArray()) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("pack structure") {
      withData(
          PackStream.pack { structure(PackStream.Structure(0x66, emptyList())) } to
              ubyteArrayOf(0xb0U, 0x66U).toByteArray(),
          PackStream.pack { structure(PackStream.Structure(0x01, listOf(1))) } to
              ubyteArrayOf(0xb1U, 0x01U, 0x01U).toByteArray(),
          PackStream.pack { structure(PackStream.Structure(0x67, (1..15).map { "$it" })) } to
              ubyteArrayOf(
                      0xbfU,
                      0x67U,
                      0x81U,
                      0x31U,
                      0x81U,
                      0x32U,
                      0x81U,
                      0x33U,
                      0x81U,
                      0x34U,
                      0x81U,
                      0x35U,
                      0x81U,
                      0x36U,
                      0x81U,
                      0x37U,
                      0x81U,
                      0x38U,
                      0x81U,
                      0x39U,
                      0x82U,
                      0x31U,
                      0x30U,
                      0x82U,
                      0x31U,
                      0x31U,
                      0x82U,
                      0x31U,
                      0x32U,
                      0x82U,
                      0x31U,
                      0x33U,
                      0x82U,
                      0x31U,
                      0x34U,
                      0x82U,
                      0x31U,
                      0x35U)
                  .toByteArray(),
          PackStream.pack {
            structure(
                PackStream.Structure(
                    0x66,
                    listOf(
                        PackStream.Structure(0x67, listOf("1", "2")),
                        PackStream.Structure(0x68, listOf("3", "4")))))
          } to
              ubyteArrayOf(
                      0xb2U,
                      0x66U,
                      0xb2U,
                      0x67U,
                      0x81U,
                      0x31U,
                      0x81U,
                      0x32U,
                      0xb2U,
                      0x68U,
                      0x81U,
                      0x33U,
                      0x81U,
                      0x34U)
                  .toByteArray(),
      ) { (actual, expected) ->
        actual shouldBe expected
      }
    }

    test("unpack null") { byteArrayOf(0xc0.toByte()).unpack { `null`() } shouldBe null }

    context("unpack boolean") {
      withData(
          byteArrayOf(0xc3.toByte()).unpack { boolean() } to true,
          byteArrayOf(0xc2.toByte()).unpack { boolean() } to false) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("unpack integer") {
      withData(
          byteArrayOf(0x00).unpack { integer() } to 0,
          byteArrayOf(0xf9.toByte()).unpack { integer() } to -7,
          byteArrayOf(0xf0.toByte()).unpack { integer() } to -16,
          ubyteArrayOf(0xc8U, 0xefU).toByteArray().unpack { integer() } to -17,
          ubyteArrayOf(0xc8U, 0x80U).toByteArray().unpack { integer() } to -128,
          ubyteArrayOf(0xc9U, 0xffU, 0x7fU).toByteArray().unpack { integer() } to -129,
          ubyteArrayOf(0xc9U, 0x80U, 0x00U).toByteArray().unpack { integer() } to -32768,
          ubyteArrayOf(0xcaU, 0xffU, 0xffU, 0x7fU, 0xffU).toByteArray().unpack { integer() } to
              -32769,
          ubyteArrayOf(0xcaU, 0x80U, 0x00U, 0x00U, 0x00U).toByteArray().unpack { integer() } to
              -2147483648,
          ubyteArrayOf(0xcbU, 0xffU, 0xffU, 0xffU, 0xffU, 0x7fU, 0xffU, 0xffU, 0xffU)
              .toByteArray()
              .unpack { integer() } to -2147483649,
          ubyteArrayOf(0x07U).toByteArray().unpack { integer() } to 7,
          ubyteArrayOf(0x7fU).toByteArray().unpack { integer() } to 127,
          ubyteArrayOf(0xc9U, 0x00U, 0x80U).toByteArray().unpack { integer() } to 128,
          ubyteArrayOf(0xc9U, 0x7fU, 0xffU).toByteArray().unpack { integer() } to 32767,
          ubyteArrayOf(0xcaU, 0x00U, 0x00U, 0x80U, 0x00U).toByteArray().unpack { integer() } to
              32768,
          ubyteArrayOf(0xcaU, 0x7fU, 0xffU, 0xffU, 0xffU).toByteArray().unpack { integer() } to
              2147483647,
          ubyteArrayOf(0xcbU, 0x00U, 0x00U, 0x00U, 0x00U, 0x80U, 0x00U, 0x00U, 0x00U)
              .toByteArray()
              .unpack { integer() } to 2147483648,
          ubyteArrayOf(0xc9U, 0x00U, 0xffU).toByteArray().unpack { integer() } to 255,
          ubyteArrayOf(0xc8U, 0x80U).toByteArray().unpack { integer() } to -128,
          ubyteArrayOf(0xcaU, 0x00U, 0x00U, 0xffU, 0xffU).toByteArray().unpack { integer() } to
              65535,
          ubyteArrayOf(0xc9U, 0x80U, 0x00U).toByteArray().unpack { integer() } to -32768,
          ubyteArrayOf(0xcbU, 0x00U, 0x00U, 0x00U, 0x00U, 0xffU, 0xffU, 0xffU, 0xffU)
              .toByteArray()
              .unpack { integer() } to 4294967295,
          ubyteArrayOf(0xcaU, 0x80U, 0x00U, 0x00U, 0x00U).toByteArray().unpack { integer() } to
              -2147483648) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("unpack float") {
      withData(
          ubyteArrayOf(0xc1U, 0x00U, 0x00U, 0x00U, 0x00U, 0x00U, 0x00U, 0x00U, 0x00U)
              .toByteArray()
              .unpack { float() } to 0.0,
          ubyteArrayOf(0xc1U, 0x40U, 0x09U, 0x1eU, 0xb8U, 0x51U, 0xebU, 0x85U, 0x1fU)
              .toByteArray()
              .unpack { float() } to 3.14) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("unpack bytes") {
      withData(
          ubyteArrayOf(0xccU, 0x00U).toByteArray().unpack { bytes() } to byteArrayOf(),
          ubyteArrayOf(0xccU, 0x03U, 0x01U, 0x02U, 0x03U).toByteArray().unpack { bytes() } to
              byteArrayOf(0x01, 0x02, 0x03),
          byteArrayOfSize(127).let { bytes ->
            ubyteArrayOf(0xccU, 0x7fU, *bytes.toUByteArray()).toByteArray().unpack { bytes() } to
                bytes
          },
          byteArrayOfSize(128).let { bytes ->
            ubyteArrayOf(0xcdU, 0x00U, 0x80U, *bytes.toUByteArray()).toByteArray().unpack {
              bytes()
            } to bytes
          },
          byteArrayOfSize(65535).let { bytes ->
            ubyteArrayOf(0xcdU, 0xffU, 0xffU, *bytes.toUByteArray()).toByteArray().unpack {
              bytes()
            } to bytes
          },
          byteArrayOfSize(65536).let { bytes ->
            ubyteArrayOf(0xceU, 0x00U, 0x01U, 0x00U, 0x00U, *bytes.toUByteArray())
                .toByteArray()
                .unpack { bytes() } to bytes
          }) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("unpack string") {
      withData(
          byteArrayOf(0x80.toByte()).unpack { string() } to "",
          ubyteArrayOf(0x81U, 0x31U).toByteArray().unpack { string() } to "1",
          ubyteArrayOf(0x82U, 0x31U, 0x32U).toByteArray().unpack { string() } to "12",
          ubyteArrayOf(0x83U, 0x31U, 0x32U, 0x33U).toByteArray().unpack { string() } to "123",
          stringOfSize(15).let { string ->
            ubyteArrayOf(0x8fU, *string.toByteArray(Charsets.UTF_8).toUByteArray())
                .toByteArray()
                .unpack { string() } to string
          },
          stringOfSize(16).let { string ->
            ubyteArrayOf(0xd0U, 0x10U, *string.toByteArray(Charsets.UTF_8).toUByteArray())
                .toByteArray()
                .unpack { string() } to string
          },
          stringOfSize(127).let { string ->
            ubyteArrayOf(0xd0U, 0x7fU, *string.toByteArray(Charsets.UTF_8).toUByteArray())
                .toByteArray()
                .unpack { string() } to string
          },
          stringOfSize(128).let { string ->
            ubyteArrayOf(0xd1U, 0x00U, 0x80U, *string.toByteArray(Charsets.UTF_8).toUByteArray())
                .toByteArray()
                .unpack { string() } to string
          },
          stringOfSize(65535).let { string ->
            ubyteArrayOf(0xd1U, 0xffU, 0xffU, *string.toByteArray(Charsets.UTF_8).toUByteArray())
                .toByteArray()
                .unpack { string() } to string
          },
          stringOfSize(65536).let { string ->
            ubyteArrayOf(
                    0xd2U,
                    0x00U,
                    0x01U,
                    0x00U,
                    0x00U,
                    *string.toByteArray(Charsets.UTF_8).toUByteArray())
                .toByteArray()
                .unpack { string() } to string
          }) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("unpack list") {
      withData(
          byteArrayOf(0x90.toByte()).unpack { list() } to emptyList<Any>(),
          ubyteArrayOf(0x93U, 0xc0U, 0x81U, 0x73U, 0x01U).toByteArray().unpack { list() } to
              listOf(null, "s", 1L),
          ubyteArrayOf(0x93U, 0x01U, 0xcaU, 0x00U, 0x00U, 0x80U, 0x00U, 0x00U)
              .toByteArray()
              .unpack { list() } to listOf(1L, 32768L, 0L),
          ubyteArrayOf(0x91U, 0xc1U, 0x40U, 0x09U, 0x1eU, 0xb8U, 0x51U, 0xebU, 0x85U, 0x1fU)
              .toByteArray()
              .unpack { list() } to listOf(3.14),
          stringOfSize(16).let { string ->
            ubyteArrayOf(
                    0x92U,
                    0x85U,
                    0x73U,
                    0x68U,
                    0x6fU,
                    0x72U,
                    0x74U,
                    0xd0U,
                    0x10U,
                    *string.toByteArray(Charsets.UTF_8).toUByteArray())
                .toByteArray()
                .unpack { list() } to listOf("short", string)
          },
          listOfSize(15).let { list ->
            ubyteArrayOf(0x9fU, *list.map { it.toUByte() }.toUByteArray()).toByteArray().unpack {
              list()
            } to list
          },
          listOfSize(16).let { list ->
            ubyteArrayOf(0xd4U, 0x10U, *list.map { it.toUByte() }.toUByteArray())
                .toByteArray()
                .unpack { list() } to list
          },
          listOfSize(127).let { list ->
            ubyteArrayOf(0xd4U, 0x7fU, *list.map { it.toUByte() }.toUByteArray())
                .toByteArray()
                .unpack { list() } to list
          },
          listOfSize(128).let { list ->
            ubyteArrayOf(0xd5U, 0x00U, 0x80U, *list.map { it.toUByte() }.toUByteArray())
                .toByteArray()
                .unpack { list() } to list
          },
          listOfSize(65535).let { list ->
            ubyteArrayOf(0xd5U, 0xffU, 0xffU, *list.map { it.toUByte() }.toUByteArray())
                .toByteArray()
                .unpack { list() } to list
          },
          listOfSize(65536).let { list ->
            ubyteArrayOf(
                    0xd6U, 0x00U, 0x01U, 0x00U, 0x00U, *list.map { it.toUByte() }.toUByteArray())
                .toByteArray()
                .unpack { list() } to list
          }) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    context("unpack dictionary") {
      withData(
          byteArrayOf(0xa0.toByte()).unpack { dictionary() } to emptyMap<String, Any?>(),
          ubyteArrayOf(0xa1U, 0x83U, 0x6eU, 0x69U, 0x6cU, 0xc0U).toByteArray().unpack {
            dictionary()
          } to mapOf<String, Any?>("nil" to null),
          ubyteArrayOf(0xa1U, 0x81U, 0x73U, 0x83U, 0x73U, 0x74U, 0x72U).toByteArray().unpack {
            dictionary()
          } to mapOf<String, Any>("s" to "str"),
          ubyteArrayOf(0xa1U, 0x83U, 0x6bU, 0x65U, 0x79U, 0x85U, 0x76U, 0x61U, 0x6cU, 0x75U, 0x65U)
              .toByteArray()
              .unpack { dictionary() } to mapOf("key" to "value"),
          ubyteArrayOf(0xa1U, 0x81U, 0x6cU, 0x01U).toByteArray().unpack { dictionary() } to
              mapOf<String, Any>("l" to 1L),
      ) { (actual, expected) ->
        actual shouldContainExactly expected
      }
    }

    context("unpack structure") {
      withData(
          ubyteArrayOf(0xb0U, 0x66U).toByteArray().unpack { structure() } to
              PackStream.Structure(0x66, emptyList()),
          ubyteArrayOf(0xb1U, 0x01U, 0x01U).toByteArray().unpack { structure() } to
              PackStream.Structure(0x01, listOf(1L)),
          ubyteArrayOf(
                  0xbfU,
                  0x67U,
                  0x81U,
                  0x31U,
                  0x81U,
                  0x32U,
                  0x81U,
                  0x33U,
                  0x81U,
                  0x34U,
                  0x81U,
                  0x35U,
                  0x81U,
                  0x36U,
                  0x81U,
                  0x37U,
                  0x81U,
                  0x38U,
                  0x81U,
                  0x39U,
                  0x82U,
                  0x31U,
                  0x30U,
                  0x82U,
                  0x31U,
                  0x31U,
                  0x82U,
                  0x31U,
                  0x32U,
                  0x82U,
                  0x31U,
                  0x33U,
                  0x82U,
                  0x31U,
                  0x34U,
                  0x82U,
                  0x31U,
                  0x35U)
              .toByteArray()
              .unpack { structure() } to PackStream.Structure(0x67, (1..15).map { "$it" }),
          ubyteArrayOf(
                  0xb2U,
                  0x66U,
                  0xb2U,
                  0x67U,
                  0x81U,
                  0x31U,
                  0x81U,
                  0x32U,
                  0xb2U,
                  0x68U,
                  0x81U,
                  0x33U,
                  0x81U,
                  0x34U)
              .toByteArray()
              .unpack { structure() } to
              PackStream.Structure(
                  0x66,
                  listOf(
                      PackStream.Structure(0x67, listOf("1", "2")),
                      PackStream.Structure(0x68, listOf("3", "4"))))) { (actual, expected) ->
            actual shouldBe expected
          }
    }

    test("(un)pack date") { verify(LocalDate.now(), PackStream.Packer::date) }

    test("(un)pack time") { verify(OffsetTime.now(), PackStream.Packer::time) }

    test("(un)pack local time") { verify(LocalTime.now(), PackStream.Packer::localTime) }

    test("(un)pack date time") {
      verify(ZonedDateTime.of(LocalDateTime.now(), ZoneOffset.UTC), PackStream.Packer::dateTime)
    }

    test("(un)pack date time zone id") {
      verify(ZonedDateTime.now(), PackStream.Packer::dateTimeZoneId)
    }

    test("(un)pack local date time") {
      verify(LocalDateTime.now(), PackStream.Packer::localDateTime)
    }

    context("(un)pack duration") {
      withData(
          Duration.ofNanos(1),
          Duration.ofMillis(1),
          Duration.ofSeconds(1),
          Duration.ofMinutes(1),
          Duration.ofHours(1),
          Duration.ofDays(1)) { duration ->
            verify(duration, PackStream.Packer::duration)
          }
    }
  }

  companion object {

    fun byteArrayOfSize(size: Int, byte: Byte = 0x3f): ByteArray {
      val bytes = ByteArray(size)
      bytes.indices.forEach { i -> bytes[i] = byte }
      return bytes
    }

    private fun stringOfSize(size: Int, char: Char = 'a'): String {
      return buildString { repeat(size) { _ -> append(char) } }
    }

    private fun listOfSize(size: Int, value: Long = 1): List<Long> {
      return buildList { repeat(size) { _ -> this += value } }
    }

    private fun <T> verify(expected: T, packer: PackStream.Packer.(T) -> PackStream.Packer) {
      PackStream.pack { packer(expected) }.unpack { any() } shouldBe expected
    }
  }
}
