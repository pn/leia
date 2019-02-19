package leia.registry

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.response.readBytes
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.experimental.runBlocking
import mu.KLogging
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class K8sResourceHolder(private val parser: (String) -> List<K8sRegistry.RouteItem>) {
    private val entriesA = AtomicReference(listOf<K8sRegistry.RouteItem>())
    fun onChange(yaml: String) {
        entriesA.updateAndGet {
            try {
                parser(yaml)
            } catch (e: MissingKotlinParameterException) {
                K8sRegistry.logger.error { K8sRegistry.logger.error { "Failed to read objects from kubernetes: ${e.message}" } }
                it
            }
        }
    }

    fun getData(): List<K8sRegistry.RouteItem> = entriesA.get()
}

// Auto-watching registry for a directory of Kubernetes Yaml files.
class K8sRegistry(private val host: String, private val port: String) : Registry {
    private val watchers = mutableListOf<Triple<String, (Map<String, Any>) -> Any, (List<*>) -> Unit>>()
    private val holder = K8sResourceHolder { yaml ->
        val mapper = ObjectMapper(JsonFactory())
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        mapper.registerModule(KotlinModule()) // Enable Kotlin support
        val routes = mapper.readValue(yaml, K8sRegistry.Routes::class.java)
        routes.items.filter { apiVersions.contains(it.apiVersion) }
    }

    private val scheduler = Executors.newScheduledThreadPool(1)

    init {
        scheduler.scheduleAtFixedRate({ this.forceUpdate() }, 1, 1, TimeUnit.MINUTES)
    }

    override fun getMaps(name: String): List<Map<String, Any>> =
        holder.getData().filter { it.kind == nameToKind[name] }.map { it.spec.toMap() }

    private fun onUpdate(yaml: String) {
        holder.onChange(yaml)
        watchers.forEach { (table, fn, handler) -> handler(getMaps(table).map { fn(it) }) }
        logger.info { "Loaded ${holder.getData().size} objects from kubernetes" }
    }

    private fun getPort(): Int  = try {
        port.toInt()
    } catch (e: NumberFormatException) {
        logger.error { "Invalid port number: $port, using default 8080" }
        DEFAULT_KUBERNETES_PORT.toInt()
    }

    override fun forceUpdate() {
        logger.info("Polling all objects from kubernetes")
        val builder = HttpRequestBuilder()
            .also { it.method = HttpMethod.Get }
            .also { it.url.also { url ->
                url.host = host
                url.port = getPort()
            }.encodedPath = "/apis/leia.klira.io/v1/namespaces/default/leiaroutes" }
        var error: String? = null
        try {
            val response = runBlocking { HttpClient().call(builder).response }
            val content = runBlocking { response.readBytes().toString(Charsets.UTF_8) }

            onUpdate(content)
        } catch (e: UnresolvedAddressException) {
            error = e.message
        } catch (e: ConnectException) {
            error = e.message
        }
        error?.let { logger.warn { "Failed to connect to kubernetes: $error" }}
    }

    override fun <T> watch(name: String, fn: (Map<String, Any>) -> T, handler: (List<T>) -> Unit) {
        val t = Triple<String, (Map<String, Any>) -> Any, (List<*>) -> Unit>(
            name,
            fn as ((Map<String, Any>)) -> Any,
            handler as ((List<*>) -> Unit)
        )
        watchers.add(t)
    }

    companion object : KLogging() {
        const val DEFAULT_KUBERNETES_HOST = "localhost"
        const val DEFAULT_KUBERNETES_PORT = "8080"
        const val DEFAULT_KUBERNETES_ENABLE = "true"
        val nameToKind = hashMapOf("routes" to "LeiaRoute")
        val apiVersions = listOf("leia.klira.io/v1") // supported versions
    }

    // classes representing Custom Resource Definition in Kubernetes
    data class Routes(val apiVersion: String, val items: List<RouteItem>)

    data class RouteItem(val apiVersion: String, val kind: String, val spec: Route)
    data class Route(val path: String,
                     val topic: String,
                     val format: String? = null,
                     val verify: Boolean? = null,
                     val methods: Collection<String>? = null,
                     val cors: List<String>? = null,
                     val response: HttpStatusCode? = null,
                     val sink: String? = null,
                     val authenticateUsing: List<String>? = null,
                     val validateJson: Boolean?,
                     val jsonSchema: String? = null) {
        fun toMap(): Map<String, Any> {
            val map = HashMap<String, Any>()
            map["path"] = path
            map["topic"] = topic
            format?.let { map["format"] = it }
            verify?.let { map["verify"] = it }
            methods?.let { map["methods"] = it }
            cors?.let { map["cors"] = it }
            response?.let { map["response"] = it }
            sink?.let { map["sink"] = it }
            authenticateUsing?.let { map["authenticateUsing"] = it }
            validateJson?.let { map["validateJson"] = it }
            jsonSchema?.let { map["jsonSchema"] = it }
            return map
        }
    }
}
