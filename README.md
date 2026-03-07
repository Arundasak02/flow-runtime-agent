# Flow Runtime Agent

A **Java agent** (`-javaagent:flow-agent.jar`) that observes method executions inside a customer's JVM and sends lightweight events to Flow Core Service (FCS) so the static architecture graph can be animated in real time.

> **One sentence:** *The bridge between a running Java application and the Flow architecture graph.*

## Quick Start

```bash
# Build
mvn clean package -DskipTests

# Run with agent attached to your app
java -javaagent:flow-agent/target/flow-agent-0.1.0-SNAPSHOT.jar \
     -Dflow.server.url=http://localhost:8080 \
     -Dflow.graph-id=order-service \
     -Dflow.packages.include=com.greens.order \
     -jar my-app.jar
```

## Minimum Configuration

Only 3 properties are required:

| Property | Description |
|---|---|
| `flow.server.url` | FCS base URL (e.g., `http://localhost:8080`) |
| `flow.graph-id` | Maps to the static graph in FCS (e.g., `order-service`) |
| `flow.packages.include` | Comma-separated customer package prefixes to instrument |

## Configuration Sources (Priority Order)

1. **System properties:** `-Dflow.server.url=http://...` (highest priority)
2. **Environment variables:** `FLOW_SERVER_URL=http://...`
3. **Config file:** `flow-agent.properties` (via `-Dflow.config=/path`)
4. **Defaults:** Built into the agent (lowest priority)

## Project Structure

```
flow-runtime-agent/
├── pom.xml                     ← parent POM
├── flow-agent/                 ← the -javaagent module
│   └── src/main/java/com/flow/agent/
│       ├── FlowAgent.java             ← premain() entry point
│       ├── config/                    ← configuration model + loader
│       ├── instrumentation/           ← ByteBuddy transformer + advice + nodeId
│       ├── context/                   ← ThreadLocal trace context
│       ├── pipeline/                  ← ring buffer + batch assembler
│       ├── transport/                 ← async HTTP sender + circuit breaker
│       ├── filter/                    ← package/method/bridge filtering
│       ├── sampling/                  ← sampling interface + AlwaysSampler
│       └── monitor/                   ← agent self-monitoring metrics
└── flow-sdk/                   ← checkpoint SDK (zero dependencies)
    └── src/main/java/com/flow/sdk/
        └── Flow.java                  ← single class — Flow.checkpoint(key, value)
```

## The Golden Rule

> **The agent must NEVER cause the customer application to fail, slow down, or behave differently.**
> If in doubt, DROP data rather than risk affecting the app.

## Safety Guarantees

- All advice code wrapped in try-catch — exceptions never propagate to customer code
- Ring buffer uses non-blocking `offer()` — never blocks the application thread
- HTTP sends are async (`sendAsync`) — never synchronous on hot path
- Circuit breaker drops events when backend is down — never queues unboundedly
- Only 2 daemon threads — pipeline + metrics
- All data structures are bounded
- No data capture — no args, return values, or PII

## Kill Switch

```bash
java -javaagent:flow-agent.jar -Dflow.enabled=false -jar my-app.jar
```

## License

Proprietary — Flow project internal use.

