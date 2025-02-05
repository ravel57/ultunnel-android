package ru.ravel.ultunnel.model

data class Config(
	val log: Log,
	val dns: Dns,
	val inbounds: List<Inbound>,
	val outbounds: List<Outbound>,
	val route: Route,
)

data class Log(val level: String)

data class Dns(
	val servers: List<Server>,
	val rules: List<DnsRule>,
	val independent_cache: Boolean,
)

data class Server(
	val tag: String,
	val address: String,
	val address_resolver: String?,
	val strategy: String?,
	val detour: String?,
)

data class DnsRule(
	val domain: List<String>,
	val server: String,
)

data class Inbound(
	val type: String,
	val tag: String,
	val listen: String?,
	val listen_port: Int?,
	val override_address: String?,
	val override_port: Int?,
	val interface_name: String?,
	val mtu: Int?,
	val auto_route: Boolean?,
	val endpoint_independent_nat: Boolean?,
	val stack: String?,
	val sniff: Boolean?,
	val inet4_address: String?,
)

data class Outbound(
	val id: String?,
	val type: String?,
	val server: String?,
	val tag: String,
	val serverPort: Int?,
	val systemInterface: String?,
	val interfaceName: String?,
	val localAddress: List<String>?,
	val privateKey: String?,
	val peers: List<String>?,
	val peerPublicKey: String?,
	val preSharedKey: String?,
	val reserved: List<String>?,
	val workers: String?,
	val mtu: Int?,
	val network: String?,
	val gso: String?,
	val user: String?,
	val password: String?,
)

data class Route(
	val rules: List<RouteRule>,
	val auto_detect_interface: Boolean,
)

data class RouteRule(
	val port: Int?,
	val outbound: String,
	val inbound: String?,
	val source_ip_cidr: List<String>?,
	val ip_cidr: List<String>?,
)
