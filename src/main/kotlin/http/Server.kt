package leia.http

import leia.logic.Resolver
import leia.sink.SinkProvider

// Interface for a front-end delivering requests
interface Server {
    fun stop()
    fun start()
}

interface ServerFactory {
    fun create(resolver: Resolver, sinkProvider: SinkProvider): Server
}
