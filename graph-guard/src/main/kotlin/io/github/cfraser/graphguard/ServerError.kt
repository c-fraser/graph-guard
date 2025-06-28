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

/**
 * Structured error types for the graph-guard proxy server.
 * 
 * These error types provide better categorization and handling of different failure modes
 * compared to generic Exception catching.
 */
sealed class ServerError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Connection-related errors including socket failures, timeouts, and network issues.
     * 
     * These errors typically indicate problems with the underlying network infrastructure
     * or connectivity between the proxy server and clients/Neo4j.
     */
    sealed class ConnectionError(message: String, cause: Throwable? = null) : ServerError(message, cause) {
        
        /** Failed to bind the server socket to the specified address */
        data class BindFailure(val address: String, override val cause: Throwable) : 
            ConnectionError("Failed to bind server to address '$address'", cause)
            
        /** Failed to accept incoming client connection */
        data class AcceptFailure(override val cause: Throwable) : 
            ConnectionError("Failed to accept client connection", cause)
            
        /** Failed to establish connection to Neo4j graph database */
        data class GraphConnectionFailure(val graphAddress: String, override val cause: Throwable) : 
            ConnectionError("Failed to connect to graph at '$graphAddress'", cause)
            
        /** Connection timeout occurred */
        data class ConnectionTimeout(val timeoutMs: Long) : 
            ConnectionError("Connection timed out after ${timeoutMs}ms")
            
        /** Unexpected connection closure */
        data class ConnectionClosed(val reason: String? = null) : 
            ConnectionError("Connection closed unexpectedly" + (reason?.let { ": $it" } ?: ""))
    }

    /**
     * Bolt protocol parsing and communication errors.
     * 
     * These errors indicate malformed messages, unsupported protocol versions,
     * or other Bolt protocol violations.
     */
    sealed class ProtocolError(message: String, cause: Throwable? = null) : ServerError(message, cause) {
        
        /** Failed to parse PackStream data */
        data class PackStreamParseError(val data: String, override val cause: Throwable) : 
            ProtocolError("Failed to parse PackStream data: $data", cause)
            
        /** Unsupported Bolt protocol version */
        data class UnsupportedVersion(val version: Int) : 
            ProtocolError("Unsupported Bolt protocol version: $version")
            
        /** Malformed Bolt message structure */
        data class MalformedMessage(val messageType: String, val details: String) : 
            ProtocolError("Malformed $messageType message: $details")
            
        /** Failed to serialize message to PackStream */
        data class SerializationError(val messageType: String, override val cause: Throwable) : 
            ProtocolError("Failed to serialize $messageType message", cause)
    }

    /**
     * Schema validation and query processing errors.
     * 
     * These errors occur when queries violate the defined schema rules or
     * when the validation process itself fails.
     */
    sealed class ValidationError(message: String, cause: Throwable? = null) : ServerError(message, cause) {
        
        /** Schema rule violation */
        data class SchemaViolation(val query: String, val violation: String) : 
            ValidationError("Query violates schema: $violation in query '$query'")
            
        /** Failed to parse or compile schema */
        data class SchemaCompilationError(val schemaSource: String, override val cause: Throwable) : 
            ValidationError("Failed to compile schema from '$schemaSource'", cause)
            
        /** Query parsing failure during validation */
        data class QueryParseError(val query: String, override val cause: Throwable) : 
            ValidationError("Failed to parse query for validation: '$query'", cause)
    }

    /**
     * Plugin execution errors.
     * 
     * These errors occur when server plugins fail during message interception
     * or event observation.
     */
    sealed class PluginError(message: String, cause: Throwable? = null) : ServerError(message, cause) {
        
        /** Plugin interceptor function failed */
        data class InterceptorFailure(val pluginName: String, val messageType: String, override val cause: Throwable) : 
            PluginError("Plugin '$pluginName' failed to intercept $messageType message", cause)
            
        /** Plugin observer function failed */
        data class ObserverFailure(val pluginName: String, val eventType: String, override val cause: Throwable) : 
            PluginError("Plugin '$pluginName' failed to observe $eventType event", cause)
            
        /** Plugin took too long to execute */
        data class PluginTimeout(val pluginName: String, val timeoutMs: Long) : 
            PluginError("Plugin '$pluginName' timed out after ${timeoutMs}ms")
    }

    /**
     * Configuration and initialization errors.
     * 
     * These errors occur during server startup or when invalid configuration
     * parameters are provided.
     */
    sealed class ConfigurationError(message: String, cause: Throwable? = null) : ServerError(message, cause) {
        
        /** Invalid server configuration parameter */
        data class InvalidParameter(val parameterName: String, val value: String, val reason: String) : 
            ConfigurationError("Invalid configuration parameter '$parameterName' = '$value': $reason")
            
        /** Required configuration missing */
        data class MissingConfiguration(val parameterName: String) : 
            ConfigurationError("Required configuration parameter '$parameterName' is missing")
            
        /** TLS/SSL configuration error */
        data class TlsConfigurationError(val details: String, override val cause: Throwable? = null) : 
            ConfigurationError("TLS configuration error: $details", cause)
    }
}