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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.ConnectException
import java.net.SocketTimeoutException

class ServerErrorTest : FunSpec() {
    init {
        context("ConnectionError") {
            test("BindFailure should include address and cause") {
                val cause = RuntimeException("Port already in use")
                val error = ServerError.ConnectionError.BindFailure("localhost:8787", cause)
                
                error.message shouldContain "localhost:8787"
                error.cause shouldBe cause
                error.shouldBeInstanceOf<ServerError.ConnectionError>()
            }
            
            test("AcceptFailure should wrap cause") {
                val cause = RuntimeException("Socket closed")
                val error = ServerError.ConnectionError.AcceptFailure(cause)
                
                error.message shouldContain "Failed to accept client connection"
                error.cause shouldBe cause
            }
            
            test("GraphConnectionFailure should include graph address") {
                val cause = ConnectException("Connection refused")
                val error = ServerError.ConnectionError.GraphConnectionFailure("bolt://localhost:7687", cause)
                
                error.message shouldContain "bolt://localhost:7687"
                error.cause shouldBe cause
            }
            
            test("ConnectionTimeout should include timeout duration") {
                val error = ServerError.ConnectionError.ConnectionTimeout(30000L)
                
                error.message shouldContain "30000ms"
                error.cause shouldBe null
            }
            
            test("ConnectionClosed should include reason when provided") {
                val error = ServerError.ConnectionError.ConnectionClosed("Client disconnected")
                
                error.message shouldContain "Client disconnected"
            }
        }
        
        context("ProtocolError") {
            test("PackStreamParseError should include data context") {
                val cause = RuntimeException("Invalid marker")
                val error = ServerError.ProtocolError.PackStreamParseError("message header", cause)
                
                error.message shouldContain "message header"
                error.cause shouldBe cause
                error.shouldBeInstanceOf<ServerError.ProtocolError>()
            }
            
            test("UnsupportedVersion should include version number") {
                val error = ServerError.ProtocolError.UnsupportedVersion(3)
                
                error.message shouldContain "version: 3"
                error.cause shouldBe null
            }
            
            test("MalformedMessage should include message type and details") {
                val error = ServerError.ProtocolError.MalformedMessage("HELLO", "missing user_agent field")
                
                error.message shouldContain "HELLO"
                error.message shouldContain "missing user_agent field"
            }
            
            test("SerializationError should include message type") {
                val cause = RuntimeException("Unsupported type")
                val error = ServerError.ProtocolError.SerializationError("RUN", cause)
                
                error.message shouldContain "RUN"
                error.cause shouldBe cause
            }
        }
        
        context("ValidationError") {
            test("SchemaViolation should include query and violation details") {
                val error = ServerError.ValidationError.SchemaViolation(
                    "CREATE (n:User {name: 'test'})",
                    "User nodes require email property"
                )
                
                error.message shouldContain "CREATE (n:User"
                error.message shouldContain "email property"
                error.shouldBeInstanceOf<ServerError.ValidationError>()
            }
            
            test("SchemaCompilationError should include schema source") {
                val cause = RuntimeException("Parse error at line 5")
                val error = ServerError.ValidationError.SchemaCompilationError("schema.txt", cause)
                
                error.message shouldContain "schema.txt"
                error.cause shouldBe cause
            }
            
            test("QueryParseError should include problematic query") {
                val cause = RuntimeException("Syntax error")
                val error = ServerError.ValidationError.QueryParseError("INVALID CYPHER", cause)
                
                error.message shouldContain "INVALID CYPHER"
                error.cause shouldBe cause
            }
        }
        
        context("PluginError") {
            test("InterceptorFailure should include plugin and message type") {
                val cause = RuntimeException("Plugin crashed")
                val error = ServerError.PluginError.InterceptorFailure("ValidatorPlugin", "RUN", cause)
                
                error.message shouldContain "ValidatorPlugin"
                error.message shouldContain "RUN"
                error.cause shouldBe cause
                error.shouldBeInstanceOf<ServerError.PluginError>()
            }
            
            test("ObserverFailure should include plugin and event type") {
                val cause = RuntimeException("Observer failed")
                val error = ServerError.PluginError.ObserverFailure("LoggingPlugin", "Connected", cause)
                
                error.message shouldContain "LoggingPlugin"
                error.message shouldContain "Connected"
                error.cause shouldBe cause
            }
            
            test("PluginTimeout should include timeout duration") {
                val error = ServerError.PluginError.PluginTimeout("SlowPlugin", 5000L)
                
                error.message shouldContain "SlowPlugin"
                error.message shouldContain "5000ms"
                error.cause shouldBe null
            }
        }
        
        context("ConfigurationError") {
            test("InvalidParameter should include parameter details") {
                val error = ServerError.ConfigurationError.InvalidParameter(
                    "maxConnections",
                    "-1",
                    "must be positive"
                )
                
                error.message shouldContain "maxConnections"
                error.message shouldContain "-1"
                error.message shouldContain "must be positive"
                error.shouldBeInstanceOf<ServerError.ConfigurationError>()
            }
            
            test("MissingConfiguration should include parameter name") {
                val error = ServerError.ConfigurationError.MissingConfiguration("databaseUrl")
                
                error.message shouldContain "databaseUrl"
                error.message shouldContain "missing"
            }
            
            test("TlsConfigurationError should include details") {
                val cause = RuntimeException("Certificate invalid")
                val error = ServerError.ConfigurationError.TlsConfigurationError("Invalid certificate", cause)
                
                error.message shouldContain "Invalid certificate"
                error.cause shouldBe cause
            }
        }
        
        context("Error hierarchy") {
            test("all error types should extend ServerError") {
                val connectionError = ServerError.ConnectionError.BindFailure("test", RuntimeException())
                val protocolError = ServerError.ProtocolError.UnsupportedVersion(1)
                val validationError = ServerError.ValidationError.SchemaViolation("query", "violation")
                val pluginError = ServerError.PluginError.PluginTimeout("plugin", 1000L)
                val configError = ServerError.ConfigurationError.MissingConfiguration("param")
                
                connectionError.shouldBeInstanceOf<ServerError>()
                protocolError.shouldBeInstanceOf<ServerError>()
                validationError.shouldBeInstanceOf<ServerError>()
                pluginError.shouldBeInstanceOf<ServerError>()
                configError.shouldBeInstanceOf<ServerError>()
            }
        }
    }
}