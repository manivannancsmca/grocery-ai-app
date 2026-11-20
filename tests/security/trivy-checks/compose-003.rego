# METADATA
# title: "Docker socket mounted or privileged container"
# description: "Docker Compose check for infrastructure/local (Trivy has no built-in Compose checks)."
# scope: package
# custom:
#   id: GROCERY-COMPOSE-003
#   avd_id: GROCERY-COMPOSE-003
#   provider: compose
#   service: local
#   severity: HIGH
#   short_code: compose-docker-socket-or-privileged
#   recommended_action: "Do not give containers the Docker socket or privileged mode."
#   input:
#     selector:
#     - type: yaml
package user.grocery.compose003

import rego.v1

deny contains msg if {
	some name, service in input.services
	some volume in service.volumes
	is_string(volume)
	contains(volume, "docker.sock")
	msg := sprintf("service %q mounts the Docker socket (%s)", [name, volume])
}

deny contains msg if {
	some name, service in input.services
	service.privileged == true
	msg := sprintf("service %q runs privileged", [name])
}
