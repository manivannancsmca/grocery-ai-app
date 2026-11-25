# LLM Gateway: எளிய தமிழில் (உங்கள் grocery-ai-app-ஐ உதாரணமாக வைத்து)

## இந்த ஆவணம் பற்றி

- இது ஒரு **கற்றல் வழிகாட்டி (learning guide)**. LLM Gateway என்றால் என்ன, அது நம் `ai-orchestrator-service`-ல் எங்கே பொருந்தும் என்பதை விளக்குகிறது. **LLM Gateway இன்னும் இந்த app-ல் implement செய்யப்படவில்லை.**
- கீழே உள்ள Java / YAML code அனைத்தும் **கற்றுக்கொள்வதற்கான மாதிரிகள் (illustrative examples)**. Project-ன் Spring Boot 4.1.2 / Spring AI 2.0.2-ஐ மனதில் வைத்து எழுதியவை. ஆனால் compile செய்தோ run செய்தோ சோதிக்கப்படவில்லை, எந்த service-லும் சேர்க்கப்படவில்லை.
- விளக்கம் வெறும் theory-ஆக இருக்கக் கூடாது என்பதற்காக, `ai-orchestrator-service`-ன் உண்மையான code-ஓடு இணைக்கப்பட்டுள்ளது (2026-11-25 நிலவரப்படி).

---

## 0. உங்கள் app இப்போது எப்படி இருக்கிறது?

| விஷயம் | இப்போது எப்படி இருக்கிறது | எங்கே |
|---|---|---|
| Model provider | `grocery.ai.provider` = `ollama` / `anthropic` / `scripted`. **Startup-ல் ஒன்றை மட்டும்** தேர்வு செய்கிறது | [ModelProviderConfig.java](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/config/ModelProviderConfig.java) |
| எல்லா model call-களும் | **ஒரே `ChatClient` bean** வழியாக: `ChatService` (chat), `InsightsService` (admin insights), `ModelWarmUp` | [AiConfig.java:70](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/config/AiConfig.java#L70) |
| Ollama | `qwen3:4b-instruct`, 8192 tokens context, 120 s timeout, warm-up | [application.yml:29-41](../../services/ai-orchestrator-service/src/main/resources/application.yml#L29-L41) |
| Rate limit | ஒரு user-க்கு **மணிக்கு 30 messages** (tokens அல்ல) | [ChatService.java:128-136](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/chat/ChatService.java#L128-L136) |
| Metrics | `grocery.ai.model.latency{agent,outcome}`: latency மட்டும், tokens/cost இல்லை | [AiMetrics.java](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/audit/AiMetrics.java) |
| MCP | `McpGateway`: catalog, orders MCP server-களை api-gateway வழியாக **user-ன் token-உடன்** அழைக்கிறது. Server கிடைக்கவில்லை என்றால் REST API | [McpGateway.java](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/gateway/McpGateway.java) |
| "RAG" | Vector DB இல்லை (`spring.ai.model.embedding: none`). `ContextBuilder` context line + tool results = live data | [ContextBuilder.java](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/chat/ContextBuilder.java) |
| Tool பாதுகாப்பு | `ToolGuard`: role check, argument check, approvals | `com.grocery.ai.tool` |
| பொய்யான data-வைத் தடுப்பது | `Grounding` + `SkippedTools`: answer-ல் உள்ள எண்கள் tool data-விலிருந்து வந்ததா என்று சோதிக்கிறது | [Grounding.java](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/chat/Grounding.java) |

### முதலில் மூன்று பெயர்க் குழப்பங்களைத் தீர்த்துவிடுவோம்

1. **`api-gateway` என்பது LLM Gateway அல்ல.** [services/api-gateway](../../services/api-gateway) ஒரு *API Gateway*. Browser-லிருந்து **உள்ளே வரும்** request-களை (ingress) சரியான service-க்கு அனுப்புகிறது. LLM Gateway உங்கள் app-லிருந்து model provider-களுக்கு **வெளியே போகும்** call-களை (egress) கட்டுப்படுத்துகிறது. திசையும் வேலையும் வேறு.
2. **`McpGateway` ஒரு MCP client.** பெயரில் "gateway" இருந்தாலும், அது MCP server-களை அழைக்கும் client தான். LLM Gateway அல்ல.
3. **உங்கள் "RAG" என்பது live-data retrieval.** Embeddings-உம் vector search-உம் இல்லை. Model-க்குத் தேவையான data இரண்டு வழிகளில் வருகிறது:
   (a) `ContextBuilder` ஒவ்வொரு message-உடனும் அனுப்பும் context line (cart summary, latest order, இன்றைய தேதி);
   (b) model அழைக்கும் tools (MCP அல்லது REST).
   இதுவும் RAG-ன் ஒரு வகைதான் (*tool-based / agentic RAG*). பின்னாளில் classic RAG (documents → embeddings → vector search) சேர்த்தாலும் LLM Gateway அதே மாதிரி பொருந்தும்.

### இன்றைய architecture

```
 Browser (FreshCart UI)
        |  HTTPS + JWT
        v
 +--------------+
 | api-gateway  |   <-- API Gateway (ingress), NOT an LLM gateway
 +------+-------+
        v
 +------------------------ ai-orchestrator-service ------------------------+
 |                                                                          |
 |  ChatService --> ContextBuilder  (live data = your "RAG")                |
 |      |                                                                   |
 |      v                                                                   |
 |  ChatClient --> ChatModel  = Ollama  OR  Anthropic  OR  Scripted         |
 |      |           (only ONE, fixed at startup, no fallback)               |
 |      v                                                                   |
 |  ToolGuard --> McpGateway --> api-gateway --> catalog / order MCP server |
 +--------------------------------------------------------------------------+
```

---

## 1. LLM Gateway என்றால் என்ன?

**ஒரு வரியில்:** உங்கள் application-க்கும் LLM provider-களுக்கும் (Ollama, Claude, OpenAI…) நடுவில் நிற்கும் **ஒரே கதவு**.
App நேரடியாக model-ஐ அழைக்காது; gateway-யிடம் கேட்கும். எந்த model, எத்தனை முறை, எவ்வளவு செலவில், எவ்வளவு பாதுகாப்பாக என்பதை gateway முடிவு செய்யும்.

### Hotel உதாரணம்

ஒரு பெரிய hotel-ஐ நினைத்துப் பாருங்கள். Waiter-கள் நேரடியாக kitchen-க்குள் ஓடுவதில்லை. எல்லா order-உம் ஒரு **order counter** வழியாகத்தான் போகும்.

| Hotel-ல் | LLM Gateway-ல் | உங்கள் app-ல் |
|---|---|---|
| தோசை என்றால் South Indian kitchen, noodles என்றால் Chinese kitchen | **Routing** | shopping → Ollama, admin insights → Claude |
| ஒரு kitchen-ல் gas தீர்ந்தால் மற்ற kitchen-க்கு அனுப்புவது | **Fallback** | Ollama down என்றால் Claude |
| ஒரு table-க்கு இத்தனை order தான் | **Rate limit / Budget** | ஒரு user-க்கு நாளுக்கு N tokens |
| ஒவ்வொரு order-க்கும் bill | **Cost tracking / Logging** | assistant வாரியாக tokens, ₹ |
| "வேர்க்கடலை allergy" குறிப்பைச் சரிபார்ப்பது | **Guardrails** | phone number cloud-க்குப் போகும் முன் mask |
| எல்லா kitchen-க்கும் ஒரே order slip format | **Unified API** | Ollama-வின் `stop`, Claude-ன் `end_turn` இரண்டும் → `STOP` |

Waiters = `ChatService`, `InsightsService`. Kitchens = Ollama, Claude. Order counter = **LLM Gateway**.

```
   WITHOUT a gateway                         WITH a gateway
   -----------------                         --------------
   ChatService ------> Ollama                ChatService -----+
   InsightsService --> Ollama                InsightsService -+--> LLM GATEWAY --+--> Ollama (local)
   (future) VendorSvc -> Claude              (future) Vendor -+    routing       +--> Claude (cloud)
                                                                   fallback      +--> (any new model)
   Each caller handles keys, timeouts,                             budget, logs,
   retries and cost by itself.                                     guardrails, cache
```

### ஒரு LLM Gateway செய்யும் 8 முக்கிய வேலைகள்

1. **Unified API**: எல்லா provider-களுக்கும் app ஒரே மாதிரி call செய்கிறது.
2. **Routing**: வேலைக்கு ஏற்ற model-ஐத் தேர்வு செய்கிறது (சிறிய வேலைக்கு மலிவான model, கடினமான வேலைக்கு பெரிய model).
3. **Fallback & Retry**: ஒரு provider தோற்றால் இன்னொன்றுக்குச் செல்கிறது.
4. **Rate limiting & Budget**: user / assistant / நாள் அடிப்படையில் token எண்ணிக்கையைக் கட்டுப்படுத்துகிறது.
5. **Observability**: tokens, latency, cost, errors அனைத்தையும் ஒரே இடத்தில் அளக்கிறது.
6. **Guardrails**: உள்ளே போகும் prompt-ல் PII mask செய்கிறது; வெளியே வரும் answer-ஐச் சோதிக்கிறது.
7. **Caching**: ஒரே கேள்விக்கு மீண்டும் model-ஐ அழைக்காமல் இருக்க உதவுகிறது (கவனம் தேவை, பகுதி 6 பார்க்கவும்).
8. **Key management**: API keys gateway-ல் மட்டும் இருக்கும்; app-களுக்குத் தெரியாது.

---

## 2. LLM Gateway ஏன் தேவை? (உங்கள் app-ன் உண்மையான பிரச்சனைகளோடு)

| # | இன்றைய நிலை | என்ன பிரச்சனை | Gateway தீர்வு |
|---|---|---|---|
| 1 | Startup-ல் ஒரு provider மட்டும் | Ollama crash ஆனாலோ, CPU overload ஆனாலோ **எல்லா assistant-உம்** `AI_UNAVAILABLE` சொல்லும் | Automatic fallback + circuit breaker |
| 2 | எல்லா assistant-க்கும் ஒரே model | "show my cart"-க்கு 4B model போதும்; admin BI insights-க்கு பெரிய model நல்லது | Assistant வாரியாக routing |
| 3 | மணிக்கு 30 **messages** | ஒரு message-க்கு tool loop காரணமாக பல model calls நடக்கலாம் (`max-tool-calls: 6`, `SkippedTools` retry). Message எண்ணிக்கையை வைத்து செலவைக் கணிக்க முடியாது | **Token** budget (user / assistant / நாள்) |
| 4 | Claude-க்கு prompt அப்படியே போகிறது | Customer தட்டச்சு செய்த phone number, UPI ID cloud provider-க்குப் போகலாம் | PII masking (cloud-க்கு மட்டும்) |
| 5 | Latency metric மட்டும் உள்ளது | எந்த assistant எத்தனை token செலவழிக்கிறது, ₹ எவ்வளவு என்று தெரியாது | Token & cost metrics |
| 6 | Model logic `ai-orchestrator`-ல் மட்டும் | நாளை vendor-service-உம் LLM அழைத்தால் timeout, keys, metrics எல்லாவற்றையும் மீண்டும் எழுத வேண்டும் | Policy ஒரே இடத்தில் |

**நல்ல செய்தி:** உங்கள் code-ல் gateway-ன் பல துண்டுகள் ஏற்கனவே உள்ளன:
- timeouts: `ModelProviderConfig`
- finish reason normalize: `ChatService.normalize()`
- context budget: `ChatService.fitHistory()`
- metrics: `AiMetrics`
- error codes: `ModelErrors`

LLM Gateway என்பது இந்தத் துண்டுகளை **ஒரே இடத்தில் ஒழுங்குபடுத்தி**, routing, fallback, budget, PII masking ஆகியவற்றைச் சேர்ப்பதுதான்.

---

## 3. உங்கள் Spring Boot app-ல் எங்கே, எப்படி பயன்படுத்துவது?

### 3.1 சரியான இடம்: model-ஐ அழைக்கும் ஒரே புள்ளி

மூன்று caller-களும் (`ChatService`, `InsightsService`, `ModelWarmUp`) [AiConfig.java:70](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/config/AiConfig.java#L70)-ல் உள்ள `ChatClient` bean-ஐப் பயன்படுத்துகின்றன.
ஆகவே gateway நிற்க வேண்டிய இடம்: **`ChatClient`-க்கும் provider `ChatModel`-க்கும் நடுவில்.**

### 3.2 இரண்டு வழிகள்

| | **A. In-process** (app-க்குள் ஒரு layer) | **B. Out-of-process** (தனி gateway service) |
|---|---|---|
| எப்படி | `ChatModel`-ஐச் சுற்றி ஒரு Java wrapper (decorator) | LiteLLM / Kong AI Gateway / Envoy AI Gateway போன்ற proxy, அல்லது உங்கள் சொந்த `llm-gateway-service` |
| கூடுதல் network hop | இல்லை | உண்டு |
| Deploy, monitor செய்ய புதிய service | இல்லை | உண்டு |
| பல service-கள் பகிர | முடியாது (ஒவ்வொன்றும் library-ஆகச் சேர்க்க வேண்டும்) | முடியும் |
| API keys ஒரே இடத்தில் | ஒரு service-க்குள் மட்டும் | நிறுவனம் முழுவதும் |
| Provider-specific features (Claude prompt caching போன்றவை) | முழுமையாகக் கிடைக்கும் | Proxy ஆதரிப்பதைப் பொறுத்தது |

### 3.3 என் பரிந்துரை: **A-வில் தொடங்குங்கள்**

- இன்று LLM அழைக்கும் service ஒன்றுதான் (`ai-orchestrator-service`).
- Spring AI-ன் `ChatModel` interface ஏற்கனவே provider-களை ஒரே மாதிரி காட்டுகிறது. அதன் மேல் ஒரு layer சேர்ப்பது எளிது.
- புதிய service, network hop, deploy pipeline எதுவும் தேவையில்லை.
- **B-க்கு எப்போது மாற வேண்டும்:** இரண்டாவது service LLM அழைக்கத் தொடங்கும்போது, அல்லது நிறுவனம் முழுவதற்கும் keys, budget dashboard தேவைப்படும்போது.

### 3.4 In-process gateway எங்கே அமரும்

```
 ChatService / InsightsService
            |
            v
 LlmGateway.clientFor(agentId, userId)      <-- callers change only this line
            |
            v
 +--------------- TurnChatModel (one per chat turn) ----------------+
 | 1. Budget check      (tokens per user per day)                   |
 | 2. Route             (agent -> primary provider)                 |
 | 3. Circuit breaker   (primary down? -> fallback)                 |
 | 4. Guardrails        (mask PII, only for cloud providers)        |
 | 5. Call + first-token timeout                                    |
 | 6. Fallback          (ONLY before the first token is streamed)   |
 | 7. Record usage      (tokens, latency, fallbacks -> Micrometer)  |
 +-----------+-------------------------------------+----------------+
             v                                     v
     OllamaChatModel                       AnthropicChatModel
     (qwen3:4b, local)                     (Claude, cloud)
```

---

## 4. Ollama, MCP, RAG உடன் LLM Gateway எப்படி வேலை செய்கிறது?

### 4.1 Ollama: gateway-க்குப் பின்னால் இருக்கும் ஒரு "kitchen"

Gateway பார்வையில் Ollama ஒரு provider மட்டுமே. ஆனால் Ollama-வுக்குச் சில சிறப்புத் தன்மைகள் உண்டு. Gateway அவற்றை மதிக்க வேண்டும்:

- **Health check:** `ModelStatus` ஏற்கனவே `/api/tags` மூலம் Ollama இருக்கிறதா என்று பார்க்கிறது. Circuit breaker இதைப் பயன்படுத்தலாம்.
- **Warm-up:** Ollama ஒரு மணி நேரம் idle ஆனால் model-ஐ unload செய்துவிடும். `ModelWarmUp` நேரடியாக local model-ஐ அழைக்க வேண்டும். Gateway வழியாக அல்ல (warm-up-க்கு budget, fallback தேவையில்லை).
- **Prompt prefix-ஐத் தொடக் கூடாது:** [ChatService.java:280-288](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/chat/ChatService.java#L280-L288)-ல் உள்ள குறிப்பைப் பாருங்கள். Prompt-ன் தொடக்கம் மாறாமல் இருந்தால் Ollama பதில் 1–5 s-ல் வருகிறது; மாறினால் CPU-ல் 83–96 s ஆகிறது. Gateway prompt-ன் **தொடக்கத்தில்** timestamp, request-id போன்ற எதையும் சேர்க்கக் கூடாது.

### 4.2 RAG: Retrieve → Augment → Generate

```
  Retrieve                     Augment                         Generate
  (data fetch)                 (build the prompt)              (call the model)
  ContextBuilder, tools  -->   system prompt + context   -->   LLM GATEWAY --> Ollama / Claude
  (vector search, if added)    + history + question
       ^                                                       ^
       |  embeddings are model calls too:                      |  the gateway sees the FINAL prompt,
       +--- route them through the gateway as well             |  not the retrieval step itself
```

- Retrieval (data எடுப்பது) gateway-க்கு **முன்பே** நடக்கிறது. Gateway பார்ப்பது இறுதி prompt-ஐ மட்டும்.
- Classic RAG சேர்த்தால், **embedding call-உம் ஒரு model call தான்**. அதையும் gateway வழியாக அனுப்பி tokens, cost அளக்கலாம் (`EmbeddingModel`-க்கும் இதே மாதிரி wrapper).
- RAG data prompt-ஐ பெரிதாக்கும். ஆகவே context budget (`fitHistory`) முக்கியம். Gateway token எண்ணிக்கையை அளப்பதால், "ஒரு turn-க்கு அதிகபட்சம் N tokens" போன்ற விதிகளும் போடலாம்.
- **Output guardrail:** உங்கள் `Grounding` check இதே வேலையைச் செய்கிறது: answer-ல் உள்ள எண்கள் retrieve செய்த data-வில் இருக்கிறதா என்று பார்க்கிறது.

### 4.3 MCP: Gateway tools-ஐ **இயக்காது**

இதுதான் மிக முக்கியமான புரிதல்:

1. Model "`getOrderStatus` என்ற tool-ஐ அழை" என்று ஒரு **tool-call request** திருப்பி அனுப்புகிறது.
2. Gateway அதை **மாற்றாமல் அப்படியே** திருப்பித் தருகிறது (tokens மட்டும் எண்ணுகிறது).
3. உங்கள் `ToolGuard` role, arguments, risk ஆகியவற்றைச் சோதிக்கிறது.
4. `McpGateway` MCP server-ஐ அழைக்கிறது (api-gateway வழியாக, user-ன் JWT-உடன்).
5. Tool result-உடன் **மீண்டும் ஒரு model call** நடக்கிறது. அதுவும் gateway வழியாகத்தான்.

ஆகவே **ஒரு chat turn = பல model calls**. அதனால் இரண்டு விதிகள்:
- **Sticky provider per turn:** ஒரு turn-ல் முதல் call Claude-க்குப் போனால், அந்த turn-ன் அடுத்த calls-உம் Claude-க்கே போக வேண்டும். Ollama-வும் Claude-ம் tool-call id-களையும் format-களையும் வேறு வேறு விதமாகக் கையாளுகின்றன. Turn நடுவில் provider மாறினால் tool loop உடையலாம்.
- **Budget per turn, not per message:** tool loop-ன் எல்லா calls-ன் tokens-ஐயும் கூட்டி எண்ண வேண்டும்.

> **"MCP Gateway" என்பது தனி concept.** பல MCP server-களுக்கு முன்னால் ஒரே கதவு அமைத்து auth, tool allow-list, audit ஆகியவற்றைச் செய்வது. உங்கள் app-ல் இந்த வேலையை `ToolGuard` + `api-gateway` + ஒவ்வொரு service-ன் JWT check ஏற்கனவே செய்கின்றன. LLM Gateway, MCP Gateway இரண்டும் வேறு வேறு layer-கள்.

---

## 5. முக்கிய நன்மைகள்

| நன்மை | உங்கள் app-ல் இதன் அர்த்தம் |
|---|---|
| **High availability** | Ollama down ஆனாலும் customers-க்கு `AI_UNAVAILABLE` வராது. Claude பதில் சொல்லும் |
| **Cost control** | எளிய கேள்விகள் local Ollama-வில் (cloud செலவு இல்லை), கடினமானவை மட்டும் Claude-க்கு. தினசரி token budget |
| **Visibility** | "support assistant நேற்று 2.1M tokens செலவழித்தது" போன்ற எண்கள் Grafana-வில் தெரியும் |
| **Security & privacy** | PII masking ஒரே இடத்தில். Claude key ஒரே இடத்தில் |
| **Vendor flexibility** | நாளை புதிய model வந்தால், YAML-ல் ஒரு வரி மாற்றம். ChatService-ஐத் தொட வேண்டாம் |
| **Consistency** | Timeout, retry, error codes எல்லா caller-க்கும் ஒரே மாதிரி |
| **Safe experiments** | 10% admin-bi traffic-ஐ புதிய model-க்கு அனுப்பி ஒப்பிடலாம் (A/B routing) |

---

## 6. சவால்கள் & Best Practices

### சவால்கள் (உங்கள் app-க்கே உரிய உண்மையான பிரச்சனைகள்)

1. **Streaming + fallback.** முதல் token user-க்குப் போய்விட்டால், provider-ஐ மாற்ற முடியாது. இல்லையென்றால் user பாதி பதிலை இரண்டு முறை பார்ப்பார். **Fallback முதல் token-க்கு முன் மட்டும்தான்.**
2. **First-token timeout-ஐச் சரியாக அமைப்பது.** Cache இல்லாத CPU Ollama முதல் token தர 70–90 s ஆகலாம். 20 s timeout வைத்தால் எப்போதும் fallback நடக்கும்; Claude bill அதிகரிக்கும். Warm-up-ஐ வைத்துக்கொண்டு, அளந்த p95 latency-ஐ வைத்து முடிவு செய்யுங்கள்.
3. **Provider வேறுபாடுகள்.** Finish reason (இதை ஏற்கனவே `normalize()` கையாளுகிறது), tool-call format, token எண்ணும் முறை, options எல்லாம் வேறு வேறு.
4. **Prompt cache உடைவது.** Gateway prompt-ன் தொடக்கத்தை மாற்றினால் Ollama-வின் prefix reuse-உம் Claude-ன் `SYSTEM_AND_TOOLS` cache-உம் உடையும். Latency-உம் செலவும் உயரும்.
5. **PII masking-ன் பக்க விளைவுகள்.** Phone number-ஐ `[PHONE]` என்று மாற்றினால், அந்த number தேவைப்படும் tool argument உடையலாம். `Grounding` எண்களைச் சோதிப்பதும் பாதிக்கப்படலாம். எதை mask செய்கிறோம் என்பதைக் கவனமாக முடிவு செய்ய வேண்டும்.
6. **Cache-ல் data leak.** உங்கள் prompt-களில் user-ன் cart, orders உள்ளன. ஒரு user-ன் cached answer இன்னொருவருக்குப் போனால் அது பெரிய security bug. **User-ஐத் தாண்டி cache செய்யவே கூடாது.** உங்கள் app-ல் semantic cache பயன் மிகக் குறைவு.
7. **Streaming-ல் token எண்ணிக்கை.** Usage பெரும்பாலும் கடைசி chunk-ல் மட்டுமே வரும். அது provider-க்கு provider மாறுபடும். சோதித்துப் பார்க்க வேண்டும்.
8. **Gateway-யே single point of failure ஆகலாம்.** Budget store (Redis) down ஆனால் என்ன செய்வது? "Allow" செய்வதா, "block" செய்வதா என்பதை முன்பே முடிவு செய்து எழுதி வைக்கவும்.
9. **பல replicas.** In-memory budget counter ஒரு replica-க்குள் மட்டும் வேலை செய்யும். AWS-ல் 2+ tasks இருந்தால் Redis போன்ற shared store தேவை.

### Best Practices

- **சிறிதாகத் தொடங்குங்கள்:** routing + fallback + token metrics முதலில். Cache, A/B பிறகு.
- **Config-driven routing:** `if (agent.equals("shopping"))` போன்ற code எங்கும் சிதறக் கூடாது. YAML-ல் routes.
- **Turn-க்கு ஒரு provider (sticky)**, fallback முதல் token-க்கு முன் மட்டும்.
- **எல்லா இடத்திலும் timeout.** Circuit breaker-க்கு production-ல் Resilience4j பயன்படுத்துங்கள்.
- **முழு prompt-ஐ log செய்யாதீர்கள்.** Conversation id, agent, provider, token எண்ணிக்கை மட்டும் log செய்யுங்கள்.
- **ஒரே metric tags:** `agent`, `provider`, `outcome`. உங்கள் `AiMetrics` naming style-ஐப் (`grocery.ai.*`) பின்பற்றுங்கள்.
- **Keys:** AWS Secrets Manager-ல் (ஏற்கனவே அப்படித்தான்). Code-லோ log-லோ ஒருபோதும் வரக் கூடாது.
- **மலிவான LLM call என்பது நடக்காத call தான்:** உங்கள் `ShoppingCommands` ("add 2 basmati rice") model-ஐ அழைக்காமலேயே code-ல் முடிக்கிறது. இது மிக நல்ல pattern.
- **Alerts:** fallback rate, budget rejections ஆகியவற்றுக்கு Prometheus alerts (`infrastructure/local/observability/prometheus/alerts.yml`).
- **Test doubles:** `FakeOllama`, `ScriptedChatModel` ஏற்கனவே உள்ளன. Gateway tests-க்கு அவற்றையே பயன்படுத்தலாம்.

---

## 7. Real-world use case: FreshCart "Diwali Big Sale"

### சூழ்நிலை

- சாதாரண நாட்களில் local Ollama (qwen3 4B) போதுமானது.
- Diwali sale அன்று மாலை 7 மணிக்கு traffic 10 மடங்கு ஆகிறது. CPU-ல் ஓடும் Ollama-வில் queue நீள்கிறது, 120 s timeout வருகிறது, customers `AI_UNAVAILABLE` பார்க்கிறார்கள்.
- Admin-க்கு sale insights நல்ல தரத்தில் வேண்டும் (பெரிய model).
- Finance: "AI cloud செலவு நாளுக்கு ₹X-ஐத் தாண்டக் கூடாது."
- Security: "Customer phone number / UPI ID mask செய்யாமல் cloud-க்குப் போகக் கூடாது."

### Gateway விதிகள்

| Assistant | Primary | Fallback | ஏன் |
|---|---|---|---|
| `shopping` | ollama | anthropic | அதிக traffic, எளிய கேள்விகள். பல commands code-லேயே முடிகின்றன |
| `payment`, `support` | ollama | anthropic | Customers காத்திருக்கக் கூடாது |
| `vendor`, `restaurant`, `delivery` | ollama | anthropic | அதே காரணம் |
| `admin-bi` (insights) | anthropic | ollama | தரம் முக்கியம்; குறைவான calls |
| எல்லாருக்கும் | – | – | ஒரு user-க்கு நாளுக்கு 50,000 tokens; cloud call-களில் PII mask |

### End-to-end architecture

```
                         +---------------------------+
   Customer / Admin ---> | FreshCart web (Vite)      |
                         +-------------+-------------+
                                       | HTTPS + JWT
                         +-------------v-------------+
                         | api-gateway  (ingress)    |
                         +-------------+-------------+
                                       |
 +-------------------------------------v-------------------------------------------+
 | ai-orchestrator-service                                                         |
 |                                                                                 |
 |  ChatService --- ContextBuilder (retrieve: cart, latest order, date)            |
 |      |       \-- ChatMemory (MySQL ai_db)                                       |
 |      v                                                                          |
 |  LlmGateway.clientFor(agent, user)                                              |
 |      |                                                                          |
 |  +---v--------------------- LLM GATEWAY ----------------------------+           |
 |  | budget | router | circuit breaker | PII mask | metrics | fallback |           |
 |  +---+----------------------------------------------------+---------+           |
 |      | tool-call requests (passed through, not executed)  |                     |
 |      v                                                    |                     |
 |  ToolGuard (role, args, approvals)                        |                     |
 |      |                                                    |                     |
 |  McpGateway (MCP client) --- REST fallback                |                     |
 +------+----------------------------------------------------+---------------------+
        | via api-gateway, with the user's JWT               |
        v                                                    v
  catalog-query MCP server                  +----------------+-----------------+
  order-service MCP server                  | Ollama  (qwen3:4b, local / GPU)  |
                                            | Claude  (cloud, PII masked)      |
                                            +----------------------------------+

  Observability: Micrometer -> Prometheus (tokens, fallbacks, latency) -> Grafana + alerts.yml
```

### ஒரு கேள்வியின் பயணம்: "என் கடைசி order எங்கே இருக்கு?" (மாலை 7:05, Ollama overload)

1. **Browser → api-gateway:** JWT சோதிக்கப்படுகிறது. Request `POST /api/ai/agents/support/chat`-க்குப் போகிறது.
2. **ChatService:** இந்த user-க்கு `support` assistant அனுமதி உண்டா? Message 2000 எழுத்துகளுக்குள் உள்ளதா? மணிக்கு 30 limit தாண்டவில்லையா? என்று சோதிக்கிறது.
3. **ContextBuilder (RAG-ன் Retrieve பகுதி):** context line உருவாகிறது: role, first name, இன்றைய தேதி (India), latest order, open complaints. User-ன் token-உடன் API-களை அழைத்து data எடுக்கிறது.
4. **`gateway.clientFor("support", userId)`:** இந்த turn-க்கு ஒரு `TurnChatModel` உருவாகிறது.
5. **Budget:** இன்று இந்த user 12,400 / 50,000 tokens பயன்படுத்தியிருக்கிறார். அனுமதி உண்டு.
6. **Route + breaker:** `support` → primary `ollama`. ஆனால் கடந்த நிமிடத்தில் 3 timeouts நடந்ததால் Ollama breaker **OPEN**. ஆகவே fallback `anthropic` தேர்வாகிறது. `grocery.ai.gateway.fallbacks{reason=BREAKER_OPEN}` +1.
7. **Guardrail:** Claude ஒரு cloud provider என்பதால், customer தட்டச்சு செய்த "என் number 98xxxxxxxx" → `[PHONE]` ஆக mask ஆகிறது.
8. **Model call 1:** Claude `getOrderStatus(GA-…)` என்ற tool-call request அனுப்புகிறது. Gateway அதை அப்படியே திருப்புகிறது; tokens பதிவாகின்றன.
9. **ToolGuard → McpGateway → order-service MCP server:** user-ன் JWT-உடன் அழைக்கப்படுகிறது. Customer தன் சொந்த order-ஐ மட்டுமே பார்க்க முடியும். MCP down என்றால் REST API பதில் தரும்.
10. **Model call 2 (tool result-உடன்):** அதே turn என்பதால் **அதே Claude** (sticky). Tokens SSE மூலம் browser-க்கு stream ஆகின்றன.
11. **Output guardrail:** `Grounding` / `SkippedTools`: answer-ல் உள்ள order number, தொகை tool data-வில் இருக்கிறதா என்று சோதிக்கிறது. Answer சேமிக்கப்படுகிறது. Metrics: `tokens{agent=support, provider=anthropic}`, latency.
12. **7:30 PM:** Breaker-ன் 30 s முடிந்ததும் gateway மீண்டும் Ollama-வை முயற்சிக்கிறது. Ollama சரியாகிவிட்டால் traffic தானாக local-க்குத் திரும்புகிறது. Cloud செலவு குறைகிறது.

---

## 8. Step-by-step implementation (மாதிரி code, repo-வில் சேர்க்கப்படாது)

### Step 1: Configuration (YAML)

Routing விதிகளை code-ல் அல்லாமல் config-ல் வைக்கிறோம். Diwali அன்று ஒரு env variable மாற்றினாலே போதும்.

```yaml
grocery:
  ai:
    gateway:
      enabled: ${AI_GATEWAY_ENABLED:true}
      cloud-providers: [anthropic]          # PII masking applies to these only
      routes:
        shopping:   { primary: ollama,    fallback: anthropic }
        payment:    { primary: ollama,    fallback: anthropic }
        support:    { primary: ollama,    fallback: anthropic }
        admin-bi:   { primary: anthropic, fallback: ollama }
      default-route: { primary: ollama,   fallback: anthropic }
      first-token-timeout: ${AI_FIRST_TOKEN_TIMEOUT:30s}   # measure p95 first!
      budget:
        tokens-per-user-per-day: ${AI_TOKENS_PER_USER_PER_DAY:50000}
      circuit-breaker:
        failures-to-open: 3
        open-for: 30s
```

### Step 2: Properties record

```java
@ConfigurationProperties("grocery.ai.gateway")
public record GatewayProperties(boolean enabled, Set<String> cloudProviders, Map<String, Route> routes,
                                Route defaultRoute, Duration firstTokenTimeout, Budget budget,
                                Breaker circuitBreaker) {

    public record Route(String primary, String fallback) {}
    public record Budget(long tokensPerUserPerDay) {}
    public record Breaker(int failuresToOpen, Duration openFor) {}

    /** The assistant's route, or the default one. */
    public Route routeFor(String agentId) {
        return routes.getOrDefault(agentId, defaultRoute);
    }
}
```
(உங்கள் project-ல் இதை `AiProperties`-க்குள் ஒரு nested record ஆகவும் வைக்கலாம்.)

### Step 3: Circuit breaker (concept-ஐப் புரிந்துகொள்ள ஒரு எளிய version)

"தொடர்ந்து 3 முறை தோற்றால், 30 விநாடிகளுக்கு அந்த provider-ஐத் தவிர்" என்பதுதான் இதன் அடிப்படை.

```java
/** Minimal breaker to learn the idea. In production use Resilience4j's CircuitBreaker. */
final class SimpleBreaker {
    private final int failuresToOpen;
    private final Duration openFor;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile Instant openUntil = Instant.MIN;

    SimpleBreaker(int failuresToOpen, Duration openFor) {
        this.failuresToOpen = failuresToOpen;
        this.openFor = openFor;
    }

    boolean allows()  { return Instant.now().isAfter(openUntil); }  // after openFor: try again (half-open)
    void success()    { failures.set(0); }
    void failure() {
        if (failures.incrementAndGet() >= failuresToOpen) {
            openUntil = Instant.now().plus(openFor);
            failures.set(0);
        }
    }
}
```

### Step 4: Token budget

```java
/** Tokens per user per day. In-memory = one replica only; with several replicas use Redis (INCRBY + TTL). */
@Component
class TokenBudget {
    private final long perUserPerDay;
    private final Map<String, AtomicLong> used = new ConcurrentHashMap<>();   // key: userId:date (old keys: TTL in Redis)

    TokenBudget(GatewayProperties properties) {
        this.perUserPerDay = properties.budget().tokensPerUserPerDay();
    }

    void check(String userId) {
        if (counter(userId).get() >= perUserPerDay) {
            throw new BudgetExceededException(perUserPerDay);   // -> new error code AI_BUDGET_EXCEEDED
        }
    }

    void add(String userId, long tokens) {
        counter(userId).addAndGet(tokens);
    }

    private AtomicLong counter(String userId) {
        String key = userId + ":" + LocalDate.now(ContextBuilder.INDIA);
        return used.computeIfAbsent(key, k -> new AtomicLong());
    }
}
```

### Step 5: PII masking (input guardrail, cloud-க்கு மட்டும்)

```java
@Component
class PiiMasker {
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern UPI_ID = Pattern.compile("\\b[\\w.-]{2,}@[a-zA-Z]{2,}\\b");
    private static final Pattern INDIAN_MOBILE = Pattern.compile("(?<!\\d)(?:\\+91[\\s-]?)?[6-9]\\d{9}(?!\\d)");

    String mask(String text) {
        if (text == null) {
            return null;
        }
        String out = EMAIL.matcher(text).replaceAll("[EMAIL]");      // e-mail first: it also looks like a UPI id
        out = UPI_ID.matcher(out).replaceAll("[UPI_ID]");
        return INDIAN_MOBILE.matcher(out).replaceAll("[PHONE]");
    }

    /** Masks system and user messages; tool calls and tool results are left as they are (see challenge 5). */
    Prompt mask(Prompt prompt) {
        List<Message> masked = prompt.getInstructions().stream()
                .map(message -> switch (message) {
                    case UserMessage user -> (Message) new UserMessage(mask(user.getText()));
                    case SystemMessage system -> new SystemMessage(mask(system.getText()));
                    default -> message;
                })
                .toList();
        return new Prompt(masked, prompt.getOptions());
    }
}
```
System prompt-ல் PII இல்லாததால் mask எதையும் மாற்றாது. ஆகவே prompt-ன் தொடக்கம் மாறாது, Claude cache உடையாது (சவால் 4).

### Step 6: Gateway-ன் இதயம்: `LlmGateway` + `TurnChatModel`

**ஏன் "turn-க்கு ஒரு model object"?** Spring AI-ன் `ChatModel.stream(Prompt)` method-க்கு agent யார், user யார் என்று தெரியாது. அது `Prompt`-ஐ மட்டுமே பெறுகிறது. Turn தொடங்கும்போதே agent, user தெரிந்த ஒரு சிறிய object-ஐ உருவாக்கினால் மூன்று பலன்கள்:
(1) ThreadLocal தேவையில்லை (reactive streams-ல் thread மாறும்போது ThreadLocal காணாமல் போகும்);
(2) provider-ஐ அந்த object-லேயே நினைவில் வைக்கலாம், ஆகவே **sticky per turn** தானாக நடக்கிறது;
(3) callers-ல் ஒரு வரி மட்டுமே மாறுகிறது.

```java
@Component
public class LlmGateway {

    private final Map<String, ChatModel> providers;        // "ollama" -> OllamaChatModel, "anthropic" -> AnthropicChatModel
    private final GatewayProperties properties;
    private final TokenBudget budget;
    private final PiiMasker pii;
    private final MeterRegistry meters;
    private final Duration modelTimeout;
    private final Map<String, SimpleBreaker> breakers = new ConcurrentHashMap<>();

    // constructor omitted

    /** One ChatClient per chat turn: it knows the assistant and the user, and keeps one provider for the turn. */
    public ChatClient clientFor(String agentId, String userId) {
        return ChatClient.builder(new TurnChatModel(this, agentId, userId)).build();
    }

    /** The route's primary; its fallback while the primary's breaker is open. */
    String pick(String agentId) {
        GatewayProperties.Route route = properties.routeFor(agentId);
        if (route.fallback() == null || breaker(route.primary()).allows()) {
            return route.primary();
        }
        countFallback(agentId, route.primary(), route.fallback(), "BREAKER_OPEN");
        return route.fallback();
    }

    String fallbackFor(String agentId, String chosen) {
        String fallback = properties.routeFor(agentId).fallback();
        return chosen.equals(fallback) ? null : fallback;
    }

    /** One streamed call to one provider: guardrails, first-token timeout, breaker, usage. */
    Flux<ChatResponse> streamFrom(String provider, String agentId, String userId, Prompt prompt) {
        SimpleBreaker breaker = breaker(provider);
        Prompt prepared = properties.cloudProviders().contains(provider) ? pii.mask(prompt) : prompt;
        long started = System.nanoTime();
        AtomicReference<ChatResponse> last = new AtomicReference<>();
        return providers.get(provider).stream(prepared)
                // first token within first-token-timeout, then each next token within the model timeout
                .timeout(Mono.delay(properties.firstTokenTimeout()), next -> Mono.delay(modelTimeout))
                .doOnNext(last::set)
                .doOnComplete(() -> {
                    breaker.success();
                    recordUsage(provider, agentId, userId, last.get(), started);
                })
                .doOnError(error -> breaker.failure());
    }

    /** Usage usually arrives on the last streamed chunk (check this for each provider). */
    private void recordUsage(String provider, String agentId, String userId, ChatResponse last, long started) {
        if (last == null || last.getMetadata() == null || last.getMetadata().getUsage() == null) {
            return;
        }
        Usage usage = last.getMetadata().getUsage();
        long input = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long output = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        budget.add(userId, input + output);
        meters.counter("grocery.ai.gateway.tokens", "agent", agentId, "provider", provider, "type", "input")
                .increment(input);
        meters.counter("grocery.ai.gateway.tokens", "agent", agentId, "provider", provider, "type", "output")
                .increment(output);
        meters.timer("grocery.ai.gateway.calls", "agent", agentId, "provider", provider)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    void countFallback(String agentId, String from, String to, String reason) {
        meters.counter("grocery.ai.gateway.fallbacks", "agent", agentId, "from", from, "to", to, "reason", reason)
                .increment();
    }

    TokenBudget budget() { return budget; }

    private SimpleBreaker breaker(String provider) {
        GatewayProperties.Breaker settings = properties.circuitBreaker();
        return breakers.computeIfAbsent(provider,
                p -> new SimpleBreaker(settings.failuresToOpen(), settings.openFor()));
    }
}
```

```java
/** The ChatModel of one chat turn: budget, route, sticky provider, fallback before the first token. */
final class TurnChatModel implements ChatModel {

    private final LlmGateway gateway;
    private final String agentId;
    private final String userId;
    private volatile String provider;          // chosen by the turn's first call, reused by the tool loop's next calls

    TurnChatModel(LlmGateway gateway, String agentId, String userId) {
        this.gateway = gateway;
        this.agentId = agentId;
        this.userId = userId;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        gateway.budget().check(userId);                          // over budget -> AI_BUDGET_EXCEEDED
        boolean firstCall = provider == null;
        String chosen = firstCall ? gateway.pick(agentId) : provider;
        provider = chosen;

        AtomicBoolean emitted = new AtomicBoolean();
        Flux<ChatResponse> answer = gateway.streamFrom(chosen, agentId, userId, prompt)
                .doOnNext(chunk -> emitted.set(true));

        String fallback = gateway.fallbackFor(agentId, chosen);
        if (!firstCall || fallback == null) {
            return answer;                                       // never switch provider in the middle of a turn
        }
        return answer.onErrorResume(error -> !emitted.get(), error -> {   // only before the first token
            gateway.countFallback(agentId, chosen, fallback, error.getClass().getSimpleName());
            provider = fallback;
            return gateway.streamFrom(fallback, agentId, userId, prompt);
        });
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        // Same idea without streaming (InsightsService): try the provider, on failure call the fallback once.
        return stream(prompt).collectList().map(TurnChatModel::merge).block();
    }

    /** Portable options, so the tools and settings ChatClient adds fit whichever provider answers. */
    @Override
    public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    private static ChatResponse merge(List<ChatResponse> chunks) {
        // Left out of this example: join the chunks' text into one Generation and keep the
        // last chunk's metadata (usage).
        throw new UnsupportedOperationException("example only");
    }
}
```

### Step 7: இணைப்பது (wiring)

1. **இரண்டு provider bean-களும் ஒரே நேரத்தில் இருக்க வேண்டும்.** இன்று `spring.ai.model.chat: ${grocery.ai.provider}` என்பதால் ஒன்று மட்டுமே உருவாகிறது. `ModelProviderConfig`-ல் `AnthropicChatModel`-ஐ manual-ஆக build செய்வது போலவே `OllamaChatModel`-ஐயும் build செய்து, இரண்டையும் `Map<String, ChatModel>` ஆக `LlmGateway`-க்குக் கொடுக்க வேண்டும். (`scripted` mode local tests-க்கு அப்படியே இருக்கலாம்.)
2. **`ChatService`-ல் ஒரே மாற்றம்:** turn தொடங்கும்போது `ChatClient client = gateway.clientFor(agent.id(), user.userId());` என்று ஒருமுறை உருவாக்க வேண்டும். `stream(...)` method `chatClient.prompt()`-க்கு பதிலாக `client.prompt()` பயன்படுத்த வேண்டும். `SkippedTools` retry-க்கும் **அதே client** (sticky).
3. **`InsightsService`:** `gateway.clientFor("admin-bi", user.userId())`.
4. **`ModelWarmUp`:** Gateway வழியாக **அல்ல**. நேரடியாக `OllamaChatModel` மூலம் (budget, fallback தேவையில்லை).
5. **புதிய error code:** `ModelErrors`-ல் `AI_BUDGET_EXCEEDED` சேர்த்து, frontend-ல் "இன்றைய AI பயன்பாட்டு வரம்பை அடைந்துவிட்டீர்கள்" போன்ற செய்தி காட்ட வேண்டும்.

### Step 8: Observability (Prometheus / Grafana)

Micrometer `grocery.ai.gateway.tokens` என்ற பெயரை Prometheus-ல் `grocery_ai_gateway_tokens_total` ஆகக் காட்டும்.

```yaml
# infrastructure/local/observability/prometheus/alerts.yml-ல் சேர்க்கக்கூடிய மாதிரி alert
- alert: AiGatewayFallbackRateHigh
  expr: |
    sum(rate(grocery_ai_gateway_fallbacks_total[5m]))
      / sum(rate(grocery_ai_gateway_calls_seconds_count[5m])) > 0.2
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "More than 20% of AI calls use the fallback provider: check Ollama"
```

Grafana dashboard-ல் பார்க்க வேண்டியவை: assistant வாரியாக tokens/நாள், provider வாரியாக latency p95, fallback rate, budget rejections. ₹ cost = tokens × (model-ன் price, config-ல் வைத்தது).

### Step 9: Testing

| Test | எதிர்பார்ப்பு |
|---|---|
| `support` route | Ollama அழைக்கப்படுகிறது |
| Ollama முதல் token-க்கு முன் தோற்கிறது | Claude பதில் தருகிறது; `fallbacks{reason=…}` +1 |
| Ollama பாதி stream-ல் தோற்கிறது | Fallback **இல்லை**; `AI_ERROR` |
| Tool loop-ன் 2வது call | முதல் call-ன் அதே provider |
| Budget தீர்ந்தது | `AI_BUDGET_EXCEEDED`; model அழைக்கப்படவில்லை |
| Claude-க்குப் போகும் prompt | `[PHONE]`; Ollama-க்குப் போகும் prompt-ல் mask இல்லை |
| 3 தோல்விகள் | Breaker open, 30 s பிறகு மீண்டும் primary |

உங்கள் `FakeOllama` (HTTP-level fake) மற்றும் `ScriptedChatModel`-ஐ "fake providers" ஆகப் பயன்படுத்தலாம்.

### (பின்னாளுக்கு) Option B: தனி gateway service (LiteLLM உதாரணம்)

பல service-கள் LLM அழைக்கத் தொடங்கும்போது, ஒரு OpenAI-compatible proxy-ஐ முன்னால் வைக்கலாம்:

```yaml
# litellm-config.yaml (sample; check the LiteLLM version's docs for exact keys)
model_list:
  - model_name: grocery-small
    litellm_params:
      model: ollama_chat/qwen3:4b-instruct
      api_base: http://ollama:11434
  - model_name: grocery-large
    litellm_params:
      model: anthropic/claude-opus-5-5
      api_key: os.environ/ANTHROPIC_API_KEY
litellm_settings:
  fallbacks: [{"grocery-small": ["grocery-large"]}]
```

```yaml
# ai-orchestrator-service: only one OpenAI-compatible endpoint, the real keys stay in the gateway
spring:
  ai:
    openai:
      base-url: http://llm-gateway:4000
      api-key: ${LLM_GATEWAY_KEY}          # the gateway's virtual key, not the Claude key
      chat:
        options:
          model: grocery-small
```
Trade-off: app எளிமையாகும். ஆனால் நீங்கள் கவனமாக அமைத்த Claude-specific `SYSTEM_AND_TOOLS` prompt caching proxy வழியாக அப்படியே வேலை செய்யுமா என்று சோதிக்க வேண்டும். ஒரு service-ஐக் கூடுதலாக deploy செய்து monitor செய்ய வேண்டும்.

---

## 9. சுருக்கம்

- **LLM Gateway** = app-க்கும் model-களுக்கும் நடுவில் உள்ள ஒரே கதவு: routing, fallback, budget, guardrails, metrics.
- உங்கள் app-ல் அதன் இடம்: **`ChatClient` → [Gateway] → `ChatModel`**. `AiConfig`-ன் ஒரே `ChatClient` bean இதை எளிதாக்குகிறது.
- **Ollama** = gateway-க்குப் பின்னால் உள்ள ஒரு provider. **RAG** retrieval gateway-க்கு முன்பே நடக்கிறது (embeddings இருந்தால் அவையும் gateway வழியாக). **MCP** tools-ஐ gateway இயக்காது; tool-call request-களை pass செய்கிறது, ஒவ்வொரு loop-உம் ஒரு புதிய model call.
- மூன்று தங்க விதிகள்: **fallback முதல் token-க்கு முன் மட்டும்**, **turn-க்கு ஒரு provider**, **prompt-ன் தொடக்கத்தைத் தொடாதே**.
- In-process-ல் தொடங்குங்கள்; இரண்டாவது LLM caller வரும்போது தனி gateway service பற்றி யோசியுங்கள்.

### அடுத்து கற்க வேண்டியவை (வரிசையாக)

1. Spring AI `ChatModel` / `ChatClient` / Advisors: [ChatService.java](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/chat/ChatService.java)-ஐ முழுவதும் படியுங்கள்.
2. Reactor `Flux`: `timeout`, `onErrorResume`, `doOnNext`.
3. Resilience4j CircuitBreaker.
4. Micrometer counters / timers → Prometheus → Grafana.
5. LiteLLM அல்லது வேறு AI gateway-ஐ local-ஆக Docker-ல் ஓட்டிப் பாருங்கள் (repo-வுக்கு வெளியே).

---

## நீங்களே சரிபார்க்க

இந்த guide-ல் உள்ள gateway இன்னும் implement செய்யப்படவில்லை. மேலே சொன்ன இன்றைய நிலையை நீங்களே உறுதிப்படுத்த:

1. **படியுங்கள்:** [AiConfig.java:70](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/config/AiConfig.java#L70) (ஒரே `ChatClient`), [ModelProviderConfig.java](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/config/ModelProviderConfig.java) (startup-ல் ஒரு provider), [application.yml:103-107](../../services/ai-orchestrator-service/src/main/resources/application.yml#L103-L107) (`embedding: none`), [McpGateway.java](../../services/ai-orchestrator-service/src/main/java/com/grocery/ai/gateway/McpGateway.java) (MCP client + REST fallback).
2. **"ஏன் fallback தேவை" என்பதை நேரில் பாருங்கள் (விருப்பம்):** app-ஐ `AI_PROVIDER=ollama`-உடன் ஓட்டி, Ollama container-ஐ நிறுத்திவிட்டு shopping assistant-ல் ஒரு கேள்வி கேளுங்கள். `AI_UNAVAILABLE` வரும். Gateway fallback இதைத்தான் தவிர்க்கிறது.
3. **Prometheus:** `grocery_ai_model_latency_seconds` metric இருக்கிறது, ஆனால் token metric இல்லை என்பதைக் கவனியுங்கள். Gateway நிரப்பப் போகும் இடைவெளி இதுதான்.
