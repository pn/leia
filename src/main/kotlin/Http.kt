package se.zensum.leia

import org.jetbrains.ktor.http.HttpMethod

object httpMethods {
    val verbs: Set<HttpMethod> = setOf(
        HttpMethod.Get,
        HttpMethod.Post,
        HttpMethod.Put,
        HttpMethod.Patch,
        HttpMethod.Delete,
        HttpMethod.Head,
        HttpMethod.Options
    )

    operator fun contains(method: HttpMethod): Boolean = method in verbs
    operator fun contains(method: String): Boolean = HttpMethod.parse(method.toUpperCase()) in verbs
}