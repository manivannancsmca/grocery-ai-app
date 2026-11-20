# METADATA
# title: "Default credential written in the compose file"
# description: "Docker Compose check for infrastructure/local (Trivy has no built-in Compose checks)."
# scope: package
# custom:
#   id: GROCERY-COMPOSE-002
#   avd_id: GROCERY-COMPOSE-002
#   provider: compose
#   service: local
#   severity: LOW
#   short_code: compose-default-credential
#   recommended_action: "Keep the fallback only for local development; CI and AWS must inject real secrets."
#   input:
#     selector:
#     - type: yaml
package user.grocery.compose002

import rego.v1

deny contains msg if {
	some name, service in input.services
	is_object(service.environment)
	some key, value in service.environment
	is_string(value)
	regex.match(`(?i)(PASSWORD|SECRET)$`, key)
	regex.match(`^\$\{[A-Za-z0-9_]+:-[^}]+\}$`, value)
	msg := sprintf("service %q: %s falls back to a default credential (%s)", [name, key, value])
}
