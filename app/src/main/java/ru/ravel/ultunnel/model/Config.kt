package ru.ravel.ultunnel.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties


@JsonIgnoreProperties(ignoreUnknown = true)
data class Config(
	val outbounds: List<Outbound> = emptyList(),
)


@JsonIgnoreProperties(ignoreUnknown = true)
data class Outbound(
	val type: String = "null",
)