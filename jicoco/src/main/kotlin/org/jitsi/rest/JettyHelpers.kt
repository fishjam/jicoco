@file:JvmName("JettyHelpers")
/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.rest

import jakarta.servlet.DispatcherType
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.SecureRequestCustomizer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.SslConnectionFactory
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.servlets.CrossOriginFilter
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.jitsi.utils.getJavaVersion
import java.nio.file.Paths
import java.util.EnumSet

/**
 * Create a non-secure Jetty server instance listening on the given [port] and [host] address.
 * [sendServerVersion] controls whether Jetty should send its server version in the error responses or not.
 */
fun createJettyServer(
    port: Int,
    host: String? = null,
    sendServerVersion: Boolean = true
): Server {
    val config = HttpConfiguration().apply {
        this.sendServerVersion = sendServerVersion
    }
    val server = Server().apply {
        handler = ServletContextHandler()
    }
    val connector = ServerConnector(server, HttpConnectionFactory(config)).apply {
        this.port = port
        this.host = host
    }
    server.addConnector(connector)
    return server
}

/**
 * Create a secure Jetty server instance listening on the given [port] and [host] address and using the
 * KeyStore located at [keyStorePath], optionally protected by [keyStorePassword].  [needClientAuth] sets whether
 * client auth is needed for SSL (see [SslContextFactory.setNeedClientAuth]).
 * [sendServerVersion] controls whether Jetty should send its server version in the error responses or not.
 */
fun createSecureJettyServer(
    port: Int,
    keyStorePath: String,
    host: String? = null,
    keyStorePassword: String? = null,
    needClientAuth: Boolean = false,
    sendServerVersion: Boolean = true
): Server {
    val sslContextFactoryKeyStoreFile = Paths.get(keyStorePath).toFile()
    val sslContextFactory = SslContextFactory.Server().apply {
        if (supportsTls13()) {
            setIncludeProtocols("TLSv1.2", "TLSv1.3")
        } else {
            setIncludeProtocols("TLSv1.2")
        }
        setIncludeCipherSuites(
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"
        )
        isRenegotiationAllowed = false
        if (keyStorePassword != null) {
            setKeyStorePassword(keyStorePassword)
        }
        this.keyStorePath = sslContextFactoryKeyStoreFile.path
        this.needClientAuth = needClientAuth
    }
    val config = HttpConfiguration().apply {
        securePort = port
        secureScheme = "https"
        addCustomizer(SecureRequestCustomizer())
        this.sendServerVersion = sendServerVersion
    }
    val server = Server().apply {
        handler = ServletContextHandler()
    }

    val connector = ServerConnector(
        server,
        SslConnectionFactory(
            sslContextFactory,
            "http/1.1"
        ),
        HttpConnectionFactory(config)
    ).apply {
        this.host = host
        this.port = port
    }
    server.addConnector(connector)
    return server
}

/**
 * Create a Jetty [Server] instance based on the given [config].
 */
fun createServer(config: JettyBundleActivatorConfig): Server {
    return if (config.isTls) {
        createSecureJettyServer(
            config.tlsPort,
            config.keyStorePath!!,
            config.host,
            config.keyStorePassword,
            config.needClientAuth,
            config.sendServerVersion
        )
    } else {
        createJettyServer(
            config.port,
            config.host,
            config.sendServerVersion
        )
    }
}

fun JettyBundleActivatorConfig.isEnabled(): Boolean = port != -1 || tlsPort != -1

// Note: it's technically possible that this cast fails, but
// shouldn't happen in practice given that the above methods always install
// a ServletContextHandler handler.
val Server.servletContextHandler: ServletContextHandler
    get() = handler as ServletContextHandler

fun ServletContextHandler.enableCors(pathSpec: String = "/*") {
    addFilter(CrossOriginFilter::class.java, pathSpec, EnumSet.of(DispatcherType.REQUEST)).apply {
        setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*")
        setInitParameter(CrossOriginFilter.ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*")
        setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, "GET,POST")
        setInitParameter(CrossOriginFilter.ALLOWED_HEADERS_PARAM, "X-Requested-With,Content-Type,Accept,Origin")
    }
}

fun Server.addServlet(servlet: ServletHolder, pathSpec: String) =
    this.servletContextHandler.addServlet(servlet, pathSpec)

// TLS 1.3 requires Java 11 or later.
private fun supportsTls13(): Boolean {
    return try {
        getJavaVersion() >= 11
    } catch (t: Throwable) {
        false
    }
}
