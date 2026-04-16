# Copilot Instructions — flow-runtime-agent

## What This Repo Does

`flow-runtime-agent` is a **JVM bytecode agent** (attached via `-javaagent:` at JVM startup). It uses ByteBuddy to instrument methods at bytecode level — no source code changes required in the target application. It captures METHOD_ENTER/EXIT/ERROR/CHECKPOINT events, batches them, and POSTs them to FCS every 200ms (or at 100 events).

The agent has a built-in circuit breaker: 3 HTTP failures → OPEN (events dropped), 30s → HALF_OPEN (probe), success → CLOSED.

## Full Context

Complete documentation lives in the workspace at:
- **Quick start:** `flow-docs/agent/00-QUICK-START.md`
- **System map:** `flow-docs/agent/01-SYSTEM.md`
- **nodeId contract (critical):** `flow-docs/agent/02-CONTRACTS.md`
- **Current bugs:** `flow-docs/agent/03-BUGS-P0.md`
- **This repo deep-dive:** `flow-docs/agent/repos/flow-runtime-agent.md`

## Critical Rules

1. **nodeId format is a cross-repo contract** — `NodeIdBuilder.java` must use the same algorithm as `SignatureNormalizer.java` in `flow-adapter-java`. Format: `{FQCN}#{method}({shortParams}):{shortReturn}`. Never change one without the other.

2. **FQN_PATTERN regex** — `\b[a-z][a-z0-9]*(?:\.[a-z0-9$_]+)*\.([A-Z][a-zA-Z0-9$_]*)` must stay identical in both adapter and agent.

3. **Circuit breaker drops events** — when OPEN, events are discarded, not queued. This is intentional to protect the target application's performance. Do not buffer unboundedly.

4. **ByteBuddy advice classes must be static** — `@Advice.OnMethodEnter`/`@Advice.OnMethodExit` methods must be static. ByteBuddy inlines them at bytecode level.

5. **Target JVM is Java 11+** — this agent must compile to and run on Java 11. Do not use Java 17+ APIs.

## Known Bug (Bug #5)

`BatchAssembler` never sets `traceComplete=true` in the batch payload. FCS relies entirely on 3s idle timeout for merge triggering. Fix: in `EntryPointAdvice.onExit()`, when span stack depth returns to 0, set `traceComplete=true` on next flush.

## When You Change Code — Checklist

- [ ] Changed `NodeIdBuilder` normalization → MUST also update `SignatureNormalizer.java` in `flow-adapter-java` + update `flow-docs/agent/02-CONTRACTS.md` §1
- [ ] Changed batch payload schema (RuntimeEvent fields) → update `flow-docs/agent/02-CONTRACTS.md` §3 + verify FCS `RuntimeIngestController` handles new fields
- [ ] Changed ByteBuddy instrumentation chains → update `flow-docs/agent/repos/flow-runtime-agent.md` (4 ByteBuddy Instrumentation Chains section)
- [ ] Changed `AgentConfig` system property names → update `flow-docs/agent/repos/flow-runtime-agent.md` (Config section)
- [ ] Fixed Bug #5 (traceComplete) → update `flow-docs/agent/03-BUGS-P0.md` + `flow-docs/issues/P0-CRITICAL.md` + `flow-docs/issues/resolved.md`
- [ ] Changed circuit breaker defaults → update `flow-docs/agent/repos/flow-runtime-agent.md` (Config section)

## Git Workflow — Required for Every Change

Before making **any** code change in this repo:

1. **Create a feature branch** — never commit directly to `main`/`master`.
   ```
   git checkout -b feature/<short-description>   # new feature
   git checkout -b fix/<short-description>        # bug fix
   git checkout -b chore/<short-description>      # refactor / docs / config
   ```

2. **Keep changes focused** — one concern per branch.

3. **Commit atomically** — one logical change, one commit.
   Message format: `<type>(<scope>): <short summary>`
   e.g. `fix(batch-assembler): set traceComplete=true on span stack depth 0`

4. **Push immediately** after committing:
   ```
   git push -u origin <branch-name>
   ```

5. **Raise a Pull Request** against `main` and report the PR URL to the user.

> Do NOT push directly to `main`, force-push, or squash history without user approval.
