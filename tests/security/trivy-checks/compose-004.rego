# METADATA
# title: "Unpinned container image"
# description: "Docker Compose check for infrastructure/local (Trivy has no built-in Compose checks)."
# scope: package
# custom:
#   id: GROCERY-COMPOSE-004
#   avd_id: GROCERY-COMPOSE-004
#   provider: compose
#   service: local
#   severity: MEDIUM
#   short_code: compose-unpinned-image
#   recommended_action: "Pin every image to an existing version tag (or a digest)."
#   input:
#     selector:
#     - type: yaml
package user.grocery.compose004

import rego.v1

deny contains msg if {
	some name, service in input.services
	is_string(service.image)
	not contains(service.image, "@sha256:")
	unpinned(service.image)
	msg := sprintf("service %q uses an unpinned image %q", [name, service.image])
}

unpinned(image) if endswith(image, ":latest")

unpinned(image) if {
	parts := split(image, "/")
	not contains(parts[count(parts) - 1], ":")
}
