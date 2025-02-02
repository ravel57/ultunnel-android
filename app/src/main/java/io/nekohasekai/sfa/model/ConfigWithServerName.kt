package io.nekohasekai.sfa.model

data class ConfigWithServerName(
    val server: String,
    val configs: List<String>,
)