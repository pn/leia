package leia.http

import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.install
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.request.*
import io.ktor.response.header
import io.ktor.response.respondText
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.pipeline.PipelineContext
import io.ktor.util.toMap
import ktor_health_check.Health
import leia.logic.*
import leia.sink.SinkProvider
import leia.sink.SinkResult
import mu.KotlinLogging
import se.zensum.ktorPrometheusFeature.PrometheusFeature
import se.zensum.ktorSentry.SentryFeature
import se.zensum.leia.getEnv
import java.util.concurrent.TimeUnit

private val EMPTY_BUF = ByteArray(0)
private val RETURN_EMPTY_BUF: suspend () -> ByteArray = { EMPTY_BUF }

private fun ApplicationRequest.headerInt(name: String): Int =
    header(name)?.toIntOrNull() ?: 0

private val logger = KotlinLogging.logger {}

private fun createIncomingRequest(req: ApplicationRequest) =
    IncomingRequest(
        req.httpMethod,
        req.header("Origin"),
        req.path(),
        req.headers.toMap(),
        req.queryString(),
        req.host(),
        req.headerInt("Content-Length").let { len ->
            if (len == 0) {
                RETURN_EMPTY_BUF
            } else {
                { req.call.receiveStream().readBytes() }
            }
        }
    )

private suspend fun sendErrorResponse(error: ErrorMatch, call: ApplicationCall) {
    val (text, status) = when (error) {
        is NotAuthorized ->
            sendNotAuthorized(error, call)
        Forbidden ->
            "forbidden" to HttpStatusCode.Forbidden
        CorsNotAllowed ->
            "cors not allowed" to HttpStatusCode.Forbidden
        CorsPreflightAllowed ->
            throw RuntimeException("ASSERT FAILED CORS handled elsewhere")
        JsonValidationFailed ->
            "Body is not valid JSON" to HttpStatusCode.BadRequest
        JsonSchemaInvalid ->
            "JSON schema is invalid" to HttpStatusCode.BadRequest
        MethodNotAllowed ->
            "Method not allowed" to HttpStatusCode.MethodNotAllowed
    }
    call.respondText(text, status = status)
}

private suspend fun sendNotFoundResponse(call: ApplicationCall) {
    call.respondText(
        "404 - Not found!",
        status = HttpStatusCode.NotFound
    )
}

private fun sendNotAuthorized(
    error: NotAuthorized,
    call: ApplicationCall
): Pair<String, HttpStatusCode> {
    if ("basic_auth" in error.triedAuthMethods) {
        call.response.header("WWW-Authenticate", "Basic")
    }
    return "unauthorized" to HttpStatusCode.Unauthorized
}

private suspend fun sendSuccessResponse(call: ApplicationCall,
                                        sr: SinkResult,
                                        receipt: Receipt) {
    // If CORS is not allowed the Sink would have ignored it.
    call.request.headers[HttpHeaders.Origin]?.let {
        call.response.header(HttpHeaders.AccessControlAllowOrigin, it)
    }
    when (sr) {
        is SinkResult.WritingFailed -> {
            logger.error(sr.exc) { "Writing to sink failed" }
            call.respondText(
                "Something went wrong",
                status = HttpStatusCode.InternalServerError
            )
        }
        is SinkResult.SuccessfullyWritten ->
            call.respondText(
                receipt.body,
                status = HttpStatusCode.fromValue(receipt.status)
            )
    }
}

private suspend fun sendCorsPreflight(call: ApplicationCall) {
    val req = call.request
    val res = call.response
    req.headers[HttpHeaders.Origin]?.let {
        res.header(HttpHeaders.AccessControlAllowOrigin, it)
        res.header(HttpHeaders.AccessControlAllowHeaders, HttpHeaders.ContentType)
    }
    call.respondText("Allowed!")
}

// A server-frontend for the Ktor framework.
class KtorServer private constructor(
    private val resolver: Resolver,
    private val appender: suspend (LogAppend) -> SinkResult,
    private val installPrometheus: Boolean
) : Server {

    private suspend fun performLogAppend(logAppend: LogAppend,
                                         sinkResult: SinkResult,
                                         call: ApplicationCall) {
        sendSuccessResponse(call, sinkResult, logAppend.receipt)
    }

    private suspend fun handleRequest(ctx: PipelineContext<Unit, ApplicationCall>) {
        val req = createIncomingRequest(ctx.context.request)
        val resolveResult = resolver.resolve(req)
        when (resolveResult) {
            is LogAppend -> performLogAppend(
                resolveResult,
                appender(resolveResult),
                ctx.context
            )
            CorsPreflightAllowed -> sendCorsPreflight(ctx.context)
            is ErrorMatch -> sendErrorResponse(resolveResult, ctx.context)
            NoMatch -> sendNotFoundResponse(ctx.context)
        }
        logRequest(ctx.context, resolveResult)
    }

    private fun logRequest(call: ApplicationCall, result: Result) {
        val sink = if (result is LogAppend) result.sinkDescription.name ?: "default" else ""
        call.request.apply {
            logger.info("${host()} ${httpMethod.value} ${path()} ${call.response.status()?.value} $sink")
        }
    }

    private fun getKtorApplication(): Application.() -> Unit = {
        install(SentryFeature)
        if (installPrometheus) install(PrometheusFeature.Feature)
        install(Health)
        intercept(ApplicationCallPipeline.Call) {
            handleRequest(this)
        }
    }

    private fun getPort(): Int = Integer.parseInt(getEnv("PORT", "80"))

    private var ktorServer: ApplicationEngine? = null
    private fun getKtorServer(): ApplicationEngine {
        if (ktorServer == null) {
            ktorServer = embeddedServer(Netty, getPort(), module = getKtorApplication())
            return ktorServer!!
        }
        return ktorServer!!
    }

    override fun start() {
        getKtorServer().start(wait = false)
    }

    override fun stop() {
        getKtorServer().stop(5000, 15, TimeUnit.SECONDS)
    }

    companion object : ServerFactory {
        override fun create(resolver: Resolver, sinkProvider: SinkProvider): Server =
            KtorServer(
                resolver,
                { sinkProvider.handle(it.sinkDescription, it.request) },
                false
            )
    }
}
