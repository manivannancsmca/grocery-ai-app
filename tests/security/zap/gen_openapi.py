"""Builds an OpenAPI 3 document for the gateway's main routes (the services expose no OpenAPI/springdoc endpoint), with
real example values: it registers a fresh customer, adds an address and looks up a product, so ZAP's API scan hits
existing resources with that customer's token.

Runs inside the ZAP container (python3, standard library only):
    python3 -I /zap/scripts/gen_openapi.py <api_url> <out.json> <token-file>
Writes the document to <out.json> and the customer's access token to <token-file>.
"""
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

API, OUT, TOKEN_FILE = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3]
TAG = time.strftime("%Y%m%d%H%M%S")


def call(method, path, body=None, token=None, expect=(200, 201)):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(API + path, data=data, method=method)
    request.add_header("Accept", "application/json")
    request.add_header("X-Correlation-Id", f"zap-setup-{TAG}-{uuid.uuid4().hex[:8]}")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    for attempt in range(10):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                text = response.read().decode() or "null"
                return response.status, json.loads(text)
        except urllib.error.HTTPError as error:
            if error.code == 429 and attempt < 9:
                time.sleep(1 + attempt)
                continue
            if error.code in expect:
                return error.code, None
            raise SystemExit(f"{method} {path} -> {error.code}: {error.read().decode()[:300]}")
    raise SystemExit(f"{method} {path}: still rate limited")


email = f"zap-{TAG}@scan.example.com"
password = "Secret123"
_, auth = call("POST", "/api/auth/register", {"email": email, "password": password, "fullName": "Zap Scanner",
                                              "phone": "+919876543210", "role": "CUSTOMER"})
token = auth["accessToken"]
for _ in range(60):
    status, _ = call("GET", "/api/customers/me", token=token, expect=(200, 404))
    if status == 200:
        break
    time.sleep(0.5)
_, address = call("POST", "/api/customers/me/addresses", {
    "label": "Home", "recipientName": "Zap Scanner", "phone": "+919876543210", "line1": "1 Scan Street",
    "city": "Bengaluru", "state": "Karnataka", "postalCode": "560001", "country": "IN",
    "latitude": 12.975, "longitude": 77.60, "isDefault": True}, token=token)
_, page = call("GET", "/api/search/products?q=rice&inStock=true&size=1")
product = (page or {}).get("content", [{}])[0] if (page or {}).get("content") else {}
product_id = product.get("id", str(uuid.uuid4()))
vendor_id = (product.get("vendor") or {}).get("id", str(uuid.uuid4()))
order_id = str(uuid.uuid4())  # a well-formed id nobody owns: the routes must answer 404, never someone's data

with open(TOKEN_FILE, "w") as handle:
    handle.write(token)


def q(name, example, required=False):
    return {"name": name, "in": "query", "required": required, "schema": {"type": "string"}, "example": example}


def p(name, example):
    return {"name": name, "in": "path", "required": True, "schema": {"type": "string"}, "example": example}


def body(example):
    return {"required": True, "content": {"application/json": {"schema": {"type": "object"}, "example": example}}}


def op(summary, params=(), request=None, public=False):
    operation = {"summary": summary, "parameters": list(params), "responses": {"200": {"description": "OK"}}}
    if request is not None:
        operation["requestBody"] = body(request)
    if public:
        operation["security"] = []
    return operation


paths = {
    # public
    "/api/health/gateway": {"get": op("gateway health", public=True)},
    "/.well-known/jwks.json": {"get": op("JWK set", public=True)},
    "/api/auth/login": {"post": op("login", request={"email": email, "password": password}, public=True)},
    "/api/search/products": {"get": op("search", [q("q", "rice"), q("page", "0"), q("size", "20"), q("inStock", "true"),
                                                  q("sort", "relevance")], public=True)},
    "/api/search/restaurants": {"get": op("restaurants nearby", [q("lat", "12.975"), q("lng", "77.6")], public=True)},
    "/api/catalog/categories": {"get": op("categories", public=True)},
    "/api/catalog/products/{productId}": {"get": op("product", [p("productId", product_id)], public=True)},
    "/api/reviews": {"get": op("public reviews", [q("targetType", "PRODUCT"), q("targetId", product_id)], public=True)},
    "/api/reviews/summary": {"get": op("rating summary", [q("targetType", "PRODUCT"), q("targetId", product_id)],
                                       public=True)},
    # signed-in customer
    "/api/auth/me": {"get": op("me")},
    "/api/customers/me": {"get": op("customer profile")},
    "/api/customers/me/addresses": {
        "get": op("addresses"),
        "post": op("add address", request={"label": "Work", "recipientName": "Zap Scanner", "phone": "+919876543210",
                                           "line1": "2 Scan Street", "city": "Bengaluru", "state": "Karnataka",
                                           "postalCode": "560001", "country": "IN", "latitude": 12.97,
                                           "longitude": 77.59}),
    },
    "/api/cart": {"get": op("cart")},
    "/api/cart/items": {"post": op("add to cart", request={"type": "PRODUCT", "productId": product_id, "quantity": 1})},
    "/api/cart/preview": {"get": op("checkout preview", [q("addressId", address["id"])])},
    "/api/cart/coupon": {"put": op("apply coupon", request={"code": "WELCOME50"})},
    "/api/coupons": {"get": op("store offers", [q("storeId", vendor_id)])},
    "/api/orders": {"get": op("my orders", [q("page", "0"), q("size", "20"), q("status", "DELIVERED")])},
    "/api/orders/{orderId}": {"get": op("an order", [p("orderId", order_id)])},
    "/api/delivery/orders/{orderId}/tracking": {"get": op("tracking", [p("orderId", order_id)])},
    "/api/payments/orders/{orderId}": {"get": op("payments of an order", [p("orderId", order_id)])},
    "/api/notifications": {"get": op("inbox", [q("page", "0"), q("size", "20")])},
    "/api/notifications/preferences": {"get": op("email preferences")},
    "/api/reviews/mine": {"get": op("my reviews")},
    "/api/reviews/eligible": {"get": op("what I may review")},
    "/api/ai/agents": {"get": op("assistants")},
    "/api/ai/status": {"get": op("assistant status")},
    "/api/approvals": {"get": op("approvals", [q("scope", "to-decide")])},
    # admin routes with a customer token: must stay 403
    "/api/admin/users": {"get": op("admin users (403 expected)", [q("query", "admin")])},
    "/api/admin/orders": {"get": op("admin orders (403 expected)", [q("q", "GA-")])},
}

document = {
    "openapi": "3.0.3",
    "info": {"title": "Grocery API gateway (main routes, generated for ZAP)", "version": TAG},
    "servers": [{"url": API}],
    "components": {"securitySchemes": {"bearer": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}}},
    "security": [{"bearer": []}],
    "paths": paths,
}
with open(OUT, "w") as handle:
    json.dump(document, handle, indent=2)
print(f"[gen_openapi] {len(paths)} paths, customer {email}, product {product_id}")
