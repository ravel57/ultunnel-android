package ru.ravel.ultunnel.model


data class ConfigWithServerName(
	val server: String = "",
	val configs: List<String> = emptyList(),
)