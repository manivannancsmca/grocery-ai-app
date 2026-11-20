# METADATA
# title: "Infrastructure port published on every interface"
# description: "Docker Compose check for infrastructure/local (Trivy has no built-in Compose checks)."
# scope: package
# custom:
#   id: GROCERY-COMPOSE-001
#   avd_id: GROCERY-COMPOSE-001
#   provider: compose
#   service: local
#   severity: MEDIUM
#   short_code: compose-infra-port-all-interfaces
#   recommended_action: "Publish data stores, brokers and admin UIs on 127.0.0.1 only, e.g. 127.0.0.1:3307:3306."
#   input:
#     selector:
#     - type: yaml
package user.grocery.compose001

import rego.v1

# container ports of data stores, brokers and admin UIs that should never be reachable from the LAN
infra_ports := {"3306", "6379", "9092", "9200", "8081", "8083", "11434", "8025", "1025"}

deny contains msg if {
	some name, service in input.services
	some port in service.ports
	is_string(port)
	not startswith(port, "127.0.0.1:")
	segments := split(port, ":")
	container := split(segments[count(segments) - 1], "/")[0]
	infra_ports[container]
	msg := sprintf("service %q publishes infrastructure port %s on all interfaces (%q)", [name, container, port])
}
