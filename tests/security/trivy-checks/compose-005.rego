# METADATA
# title: "Container without a memory limit"
# description: "Docker Compose check for infrastructure/local (Trivy has no built-in Compose checks)."
# scope: package
# custom:
#   id: GROCERY-COMPOSE-005
#   avd_id: GROCERY-COMPOSE-005
#   provider: compose
#   service: local
#   severity: LOW
#   short_code: compose-no-memory-limit
#   recommended_action: "Give every service mem_limit (the whole stack runs on one laptop)."
#   input:
#     selector:
#     - type: yaml
package user.grocery.compose005

import rego.v1

deny contains msg if {
	some name, service in input.services
	not service.mem_limit
	not service.deploy.resources.limits.memory
	msg := sprintf("service %q has no memory limit", [name])
}
