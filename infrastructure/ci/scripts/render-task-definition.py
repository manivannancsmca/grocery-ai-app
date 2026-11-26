#!/usr/bin/env python3
# =====================================================================================================================
# render-task-definition.py - the JSON for `aws ecs register-task-definition --cli-input-json file://...` that deploys
# a new image of one ECS unit (contract Phase 14 §5/§6). Used by the Jenkins shared library; runnable by hand.
#
# Two modes:
#   live      --current <file>: the output of
#               aws ecs describe-task-definition --task-definition grocery-<env>-<unit> --include TAGS --output json
#             The revision Terraform (or the last deployment) registered is kept as is - environment, secrets, roles,
#             sizes, the ADOT sidecar - and only the image of the app container is replaced. Terraform owns the
#             configuration, CI owns the image (modules/ecs-service ignores the service's task_definition).
#   template  no --current (DRY_RUN, no AWS access): the same task definition rendered from
#             infrastructure/aws/terraform/services.auto.tfvars.json and envs/<env>/<env>.tfvars, mirroring
#             modules/platform + modules/ecs-service (one-off tasks: modules/one-off-task). Values that exist only
#             after `terraform apply` (RDS address, MSK brokers, Redis endpoint, AMP endpoint ...) are written as
#             "<tf:...>" placeholders - a dry run shows the exact shape, never invented endpoints.
#
# Usage:
#   render-task-definition.py --unit pricing-service --env dev --image <registry>/grocery/pricing-service:<tag> \
#       [--current describe.json] [--out taskdef.json] [--account 000000000000] [--region ap-south-1] \
#       [--catalog services.auto.tfvars.json] [--tfvars envs/dev/dev.tfvars] [--container app]
#   --unit may be a one-off task of the catalog (schema-init).
# Prints a one-line summary on stderr; the JSON goes to --out (default stdout). Exit codes: 0 ok, 1 error, 2 usage.
# Python 3.8+, standard library only.
# =====================================================================================================================
import argparse
import json
import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
TF_DIR = os.path.join(REPO_ROOT, "infrastructure", "aws", "terraform")

# Keys of describe-task-definition's taskDefinition that register-task-definition does not accept.
READ_ONLY_KEYS = (
    "taskDefinitionArn", "revision", "status", "requiresAttributes", "compatibilities",
    "registeredAt", "registeredBy", "deregisteredAt", "deleteRequested",
)

# Defaults of modules/platform/variables.tf and modules/ecs-service/variables.tf (used when the tfvars do not set them).
DEFAULTS = {
    "java_tool_options": "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError",
    "adot_image": "public.ecr.aws/aws-observability/aws-otel-collector:v0.51.0",
    "adot_memory": 256,
    "service_connect_memory_reserve": 128,
    "health_check_start_period": 180,
    "tracing_sampling_probability": "0.1",
    "cpu_architecture": "X86_64",
    "ai_provider": "<tf:var.ai_provider>",
    "ollama_model": "<tf:var.ollama_model>",
    "anthropic_model": "<tf:var.anthropic_model>",
    "ai_knowledge_enabled": "<tf:var.ai_knowledge_enabled>",
    "bootstrap_admin_email": "<tf:var.bootstrap_admin_email>",
    "mail_enabled": "<tf:var.mail_enabled>",
}


def die(msg, code=1):
    print("render-task-definition: ERROR: " + msg, file=sys.stderr)
    sys.exit(code)


# ------------------------------------------------------------------------------------------------- tfvars (subset)
def parse_tfvars(path):
    """Top-level scalars plus the `sizing` and `service_overrides` maps of an <env>.tfvars (HCL subset)."""
    result = {"sizing": {}, "service_overrides": {}}
    if not path or not os.path.exists(path):
        return result
    text = open(path, encoding="utf-8").read()
    text = re.sub(r"(?m)#.*$", "", text)
    for m in re.finditer(r'(?m)^([a-z_][a-z0-9_]*)\s*=\s*("([^"]*)"|-?[0-9.]+|true|false|null)\s*$', text):
        key, raw, quoted = m.group(1), m.group(2), m.group(3)
        if raw == "null":
            continue
        result[key] = quoted if quoted is not None else raw
    def block(name):
        # `name = { ... }` on one line, else up to the first "}" at the start of a line
        return (re.search(r"(?m)^%s\s*=\s*\{([^}\n]*)\}" % name, text)
                or re.search(r"(?ms)^%s\s*=\s*\{(.*?)^\}" % name, text))

    m = block("sizing")
    if m:
        for k, v in re.findall(r"([a-z_]+)\s*=\s*([0-9]+)", m.group(1)):
            result["sizing"][k] = int(v)
    m = block("service_overrides")
    if m:
        for name, body in re.findall(r'"([a-z0-9-]+)"\s*=\s*\{([^}]*)\}', m.group(1)):
            result["service_overrides"][name] = {k: int(v) for k, v in re.findall(r"([a-z_]+)\s*=\s*([0-9]+)", body)}
    return result


# ------------------------------------------------------------------------------------------------------ live mode
def from_current(current, image, container):
    td = current.get("taskDefinition", current)
    tags = current.get("tags") or td.get("tags") or []
    out = {k: v for k, v in td.items() if k not in READ_ONLY_KEYS and v not in (None, [], {})}
    found = False
    for c in out.get("containerDefinitions", []):
        if c.get("name") == container:
            c["image"] = image
            found = True
    if not found:
        names = [c.get("name") for c in out.get("containerDefinitions", [])]
        die("container '%s' not found in %s (containers: %s)" % (container, td.get("family"), ", ".join(names)))
    if tags:
        out["tags"] = tags
    return out


# -------------------------------------------------------------------------------------------------- template mode
def secret_arn(region, account, name):
    # Partial ARN (without the random 6-character suffix): accepted by ECS for Secrets Manager references.
    return "arn:aws:secretsmanager:%s:%s:secret:%s" % (region, account, name)


def render_template(args, catalog, tfvars):
    env = args.env
    unit = args.unit
    name = "grocery-" + env
    region, account = args.region, args.account
    services = catalog.get("services", {})
    one_off = catalog.get("one_off_tasks", {})

    def tv(key):
        return tfvars.get(key, DEFAULTS.get(key))

    domain = tfvars.get("domain_name", "<tf:var.domain_name>")
    app_url = "https://" + domain
    arch = tv("cpu_architecture")
    log_group = "/grocery/%s/%s" % (env, unit)
    tags = [{"key": "Project", "value": "grocery"}, {"key": "Environment", "value": env},
            {"key": "Service", "value": unit}, {"key": "ManagedBy", "value": "terraform"},
            {"key": "DeployedBy", "value": "jenkins"}]

    if unit in one_off:
        if unit != "schema-init":
            die("one-off task '%s' has no CI-built image; only schema-init is deployed by CI" % unit)
        task = one_off[unit]
        family = "%s-%s" % (name, unit)
        sr_port = (services.get("schema-registry", {}).get("load_balancer") or {}).get("listener_port", 8081)
        api_origin = tfvars.get("api_origin_domain_name", "<tf:var.api_origin_domain_name>")
        return {
            "family": family,
            "taskRoleArn": "arn:aws:iam::%s:role/%s-task" % (account, family),
            "executionRoleArn": "arn:aws:iam::%s:role/%s-exec" % (account, family),
            "networkMode": "awsvpc",
            "requiresCompatibilities": ["FARGATE"],
            "cpu": str(task.get("cpu", 256)),
            "memory": str(task.get("memory", 512)),
            "runtimePlatform": {"operatingSystemFamily": "LINUX", "cpuArchitecture": arch},
            "containerDefinitions": [{
                "name": "app",
                "image": args.image,
                "essential": True,
                "readonlyRootFilesystem": False,
                "environment": sorted([
                    {"name": "SCHEMA_COMPATIBILITY", "value": "BACKWARD_TRANSITIVE"},
                    {"name": "SCHEMAS_DIR", "value": "/schemas"},
                    {"name": "SCHEMA_REGISTRY_URL", "value": "https://%s:%s" % (api_origin, sr_port)},
                ], key=lambda e: e["name"]),
                "secrets": [],
                "logConfiguration": {"logDriver": "awslogs", "options": {
                    "awslogs-group": log_group, "awslogs-region": region, "awslogs-stream-prefix": "run"}},
            }],
            "tags": tags,
        }

    svc = services.get(unit)
    if svc is None:
        die("unknown unit '%s' (services.auto.tfvars.json has: %s)" % (unit, ", ".join(sorted(services))))
    if svc.get("kind") != "ecs":
        die("'%s' is not an ECS unit (kind=%s); the web app is deployed with deploy-spa.sh" % (unit, svc.get("kind")))

    sizing = tfvars.get("sizing", {})
    override = tfvars.get("service_overrides", {}).get(unit, {})
    cpu = override.get("cpu") or sizing.get("cpu") or svc["cpu"]
    memory = override.get("memory") or sizing.get("memory") or svc["memory"]
    adot = bool(svc.get("adot"))
    adot_memory = int(tv("adot_memory")) if adot else 0
    app_memory = memory - adot_memory - DEFAULTS["service_connect_memory_reserve"]
    needs = svc.get("needs") or {}
    spring = svc.get("runtime") == "spring-boot"

    tokens = {
        "{{app_url}}": app_url,
        "{{bootstrap_admin_email}}": tv("bootstrap_admin_email"),
        "{{ai_provider}}": tv("ai_provider"),
        "{{ollama_base_url}}": tfvars.get("ollama_base_url", ""),
        "{{ollama_model}}": tv("ollama_model"),
        "{{anthropic_model}}": tv("anthropic_model"),
        "{{ai_knowledge_enabled}}": str(tv("ai_knowledge_enabled")).lower(),
        "{{mail_enabled}}": tv("mail_enabled"),
        "{{smtp_host}}": tfvars.get("smtp_host", "email-smtp.%s.amazonaws.com" % region),
        "{{kafka_bootstrap_scram}}": "<tf:module.msk.bootstrap_brokers_scram>",
        "{{kafka_replication_factor}}": "<tf:var.msk.replication_factor>",
    }

    env_vars = {}
    if spring:
        env_vars.update({
            "SPRING_PROFILES_ACTIVE": "aws",
            "GROCERY_ENV": env,
            "TZ": "UTC",
            "JAVA_TOOL_OPTIONS": tv("java_tool_options"),
            "OTEL_EXPORTER_OTLP_ENDPOINT": "http://localhost:4318",
            "TRACING_SAMPLING_PROBABILITY": str(tv("tracing_sampling_probability")),
            "JWT_ISSUER": tfvars.get("jwt_issuer", app_url),
            "IDENTITY_JWKS_URI": "http://identity-service:8101/.well-known/jwks.json",
        })
    if needs.get("database"):
        env_vars.update({"DB_HOST": "<tf:module.rds.address>", "DB_PORT": "3306"})
    if needs.get("kafka") and spring:
        env_vars["KAFKA_BOOTSTRAP_SERVERS"] = "<tf:module.msk.bootstrap_brokers_scram>"
    if needs.get("redis"):
        env_vars.update({"REDIS_HOST": "<tf:module.redis.primary_endpoint>", "REDIS_PORT": "6379", "REDIS_TLS": "true"})
    if needs.get("elasticsearch"):
        env_vars["ELASTICSEARCH_URIS"] = "<tf:module.elastic.elasticsearch_url>"
    if needs.get("schema_registry"):
        env_vars["SCHEMA_REGISTRY_URL"] = "http://schema-registry:8081"
        env_vars["GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL"] = "http://schema-registry:8081"
    if needs.get("approval_jwks"):
        env_vars["APPROVAL_JWKS_URI"] = "http://ai-orchestrator-service:8115/.well-known/approval-jwks.json"
    for k, v in (svc.get("environment") or {}).items():
        for tok, val in tokens.items():
            v = v.replace(tok, str(val))
        env_vars[k] = v
    env_vars = {k: v for k, v in env_vars.items() if v != ""}

    secret_names = {"redis": "grocery/%s/redis/auth-token" % env}
    if svc.get("database"):
        secret_names["db"] = "grocery/%s/%s/db" % (env, unit)
    if needs.get("kafka"):
        secret_names["kafka"] = "AmazonMSK_grocery/%s/%s/kafka" % (env, unit)
    for n in (svc.get("app_secrets") or {}):
        secret_names[n] = "grocery/%s/%s/%s" % (env, unit, n)
    secrets = []
    for ek, ref in sorted((svc.get("secrets") or {}).items()):
        parts = ref.split(":")
        if parts[0] not in secret_names:
            die("secret reference '%s' of %s has no secret '%s'" % (ref, unit, parts[0]))
        arn = secret_arn(region, account, secret_names[parts[0]])
        secrets.append({"name": ek, "valueFrom": arn + (":%s::" % parts[1] if len(parts) > 1 else "")})

    read_only = bool(svc.get("read_only_root_filesystem"))
    app = {
        "name": "app",
        "image": args.image,
        "essential": True,
        "memory": app_memory,
        "readonlyRootFilesystem": read_only,
        "stopTimeout": 30,
        "portMappings": [{"name": "http", "containerPort": svc["port"], "protocol": "tcp", "appProtocol": "http"}],
        "environment": [{"name": k, "value": env_vars[k]} for k in sorted(env_vars)],
        "secrets": secrets,
        "healthCheck": {"command": ["CMD-SHELL", svc["container_health_check"]], "interval": 15, "timeout": 5,
                        "retries": 4, "startPeriod": DEFAULTS["health_check_start_period"]},
        "linuxParameters": {"initProcessEnabled": True},
        "mountPoints": [{"sourceVolume": "tmp", "containerPath": "/tmp", "readOnly": False}] if read_only else [],
        "logConfiguration": {"logDriver": "awslogs", "options": {
            "awslogs-group": log_group, "awslogs-region": region, "awslogs-stream-prefix": "app",
            "mode": "non-blocking", "max-buffer-size": "25m"}},
        "dependsOn": [{"containerName": "adot-collector", "condition": "START"}] if adot else [],
    }
    if svc.get("command"):
        app["command"] = svc["command"]
    containers = [app]
    if adot:
        containers.append({
            "name": "adot-collector",
            "image": tv("adot_image"),
            "essential": True,
            "memory": adot_memory,
            "readonlyRootFilesystem": True,
            "environment": [
                {"name": "AMP_REMOTE_WRITE_ENDPOINT", "value": "<tf:module.observability.amp_remote_write_endpoint>"},
                {"name": "APP_PORT", "value": str(svc.get("metrics_port") or svc["port"])},   # mirrors ecs-service: the management port when set
                {"name": "GROCERY_ENV", "value": env},
                {"name": "METRICS_PATH", "value": svc.get("metrics_path") or "/actuator/prometheus"},
                {"name": "SERVICE_NAME", "value": unit},
            ],
            "secrets": [{"name": "AOT_CONFIG_CONTENT", "valueFrom":
                         "arn:aws:ssm:%s:%s:parameter/grocery/%s/adot/collector-config" % (region, account, env)}],
            "healthCheck": {"command": ["/healthcheck"], "interval": 30, "timeout": 5, "retries": 3, "startPeriod": 10},
            "logConfiguration": {"logDriver": "awslogs", "options": {
                "awslogs-group": log_group, "awslogs-region": region, "awslogs-stream-prefix": "adot",
                "mode": "non-blocking"}},
        })
    for c in containers:  # register-task-definition rejects nothing here, but keep the output tidy
        for k in [k for k, v in c.items() if v == []]:
            del c[k]

    family = "%s-%s" % (name, unit)
    out = {
        "family": family,
        "taskRoleArn": "arn:aws:iam::%s:role/%s-task" % (account, family),
        "executionRoleArn": "arn:aws:iam::%s:role/%s-exec" % (account, family),
        "networkMode": "awsvpc",
        "requiresCompatibilities": ["FARGATE"],
        "cpu": str(cpu),
        "memory": str(memory),
        "runtimePlatform": {"operatingSystemFamily": "LINUX", "cpuArchitecture": arch},
        "containerDefinitions": containers,
        "tags": tags,
    }
    if read_only:
        out["volumes"] = [{"name": "tmp"}]
    return out


def main():
    p = argparse.ArgumentParser(description="Render the register-task-definition input of one ECS unit.")
    p.add_argument("--unit", "--service", dest="unit", required=True)
    p.add_argument("--env", required=True, choices=["dev", "staging", "prod"])
    p.add_argument("--image", required=True)
    p.add_argument("--current", help="describe-task-definition output (live mode)")
    p.add_argument("--container", default="app")
    p.add_argument("--catalog", default=os.path.join(TF_DIR, "services.auto.tfvars.json"))
    p.add_argument("--tfvars", help="default: infrastructure/aws/terraform/envs/<env>/<env>.tfvars")
    p.add_argument("--account", default=os.environ.get("AWS_ACCOUNT_ID") or "000000000000")
    p.add_argument("--region", default=os.environ.get("AWS_REGION") or None)
    p.add_argument("--out")
    args = p.parse_args()

    if not re.match(r"^[a-z0-9][a-z0-9._/:@-]*$", args.image.lower()) or ":" not in args.image.split("/")[-1]:
        die("--image must be <registry>/<repository>:<tag>, got '%s'" % args.image, 2)

    if args.current:
        try:
            current = json.load(open(args.current, encoding="utf-8"))
        except (OSError, ValueError) as e:
            die("cannot read %s: %s" % (args.current, e))
        out = from_current(current, args.image, args.container)
        mode = "live (%s:%s)" % (current.get("taskDefinition", current).get("family"),
                                 current.get("taskDefinition", current).get("revision"))
    else:
        tfvars_path = args.tfvars or os.path.join(TF_DIR, "envs", args.env, args.env + ".tfvars")
        tfvars = parse_tfvars(tfvars_path)
        args.region = args.region or tfvars.get("aws_region") or "ap-south-1"
        try:
            catalog = json.load(open(args.catalog, encoding="utf-8"))
        except (OSError, ValueError) as e:
            die("cannot read the catalog %s: %s" % (args.catalog, e))
        out = render_template(args, catalog, tfvars)
        mode = "template (services.auto.tfvars.json + %s)" % os.path.basename(tfvars_path)

    text = json.dumps(out, indent=2, sort_keys=False) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    else:
        sys.stdout.write(text)
    app = next((c for c in out["containerDefinitions"] if c["name"] == args.container), {})
    print("render-task-definition: %s family=%s cpu=%s memory=%s containers=%s image=%s" % (
        mode, out["family"], out.get("cpu"), out.get("memory"),
        ",".join(c["name"] for c in out["containerDefinitions"]), app.get("image")), file=sys.stderr)


if __name__ == "__main__":
    main()
