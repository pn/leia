package se.zensum.leia.auth


interface AuthProviderFactory {
    fun create(spec: AuthProviderSpec): AuthProvider?
}

object DefaultAuthProviderFactory: AuthProviderFactory {
    override fun create(spec: AuthProviderSpec): AuthProvider =
        when(spec.type.toLowerCase()) {
            "basic_auth" -> BasicAuth.fromOptions(spec.options)
            "no_auth" -> NoCheck
            "jwk" -> JwkAuth.fromOptions(spec.options)
            else -> throw RuntimeException("No AuthProvider matching type ${spec.type}")
    }

}