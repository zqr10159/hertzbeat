# HertzBeat Collector Refactoring Plan

## Goal
Refactor `hertzbeat-collector` to migrate to Quarkus for faster startup, lower memory usage, and virtual thread support (JDK 21), while maintaining existing capabilities (embedded mode, independent deployment) and user experience (configurations, UI).

## Phase 1: Core Logic Extraction (Splitting "Core" from "Runtime")
**Objective:** Decouple collector logic from Spring Framework.

### 1.1 Create `hertzbeat-collector-core` Module
- **Action:** Create a new Maven module `hertzbeat-collector/hertzbeat-collector-core`.
- **Structure:**
  - `collector-core-api`: Interfaces (CollectorEngine, Job, CollectRep).
  - `collector-core-impl`: Implementation of the engine.
  - `collector-protocols`: Protocol implementations (moved from `basic` and `common`).
  - `collector-scheduler`: Scheduling logic (TimerDispatch, WorkerPool).

### 1.2 Migrate Common Logic
- **Source:** `hertzbeat-collector-common`
- **Target:** `hertzbeat-collector-core`
- **Tasks:**
  - Move `CollectJobService`, `TimerDispatch`, `WorkerPool` to core.
  - Remove `@Component`, `@Autowired`, `@Service` Spring annotations.
  - Refactor to use Constructor Injection or a simple Factory pattern.
  - Move model classes (`Job`, `CollectRep`, `Configmap`) to core.

### 1.3 Migrate Protocol Implementations
- **Source:** `hertzbeat-collector-basic` and `hertzbeat-collector-xxx`
- **Target:** `hertzbeat-collector-core` (or sub-modules of core).
- **Tasks:**
  - Move protocol implementations (HTTP, JDBC, SSH, etc.) to core.
  - Replace `JdbcTemplate` usage (if tied to Spring context) or keep `spring-jdbc` as a pure library without Spring Boot context.
  - Ensure `CollectResponseEventListener` callback mechanism works without Spring events.

### 1.4 Define `CollectorEngine` Interface
```java
public interface CollectorEngine {
    void start(CollectorRuntimeConfig config);
    long submitAsync(Job job);
    List<CollectRep.MetricsData> collectOnce(Job job);
    void cancel(long jobId);
    void shutdown();
}
```

## Phase 2: Embedded Mode Adaptation
**Objective:** Ensure Manager can still run collector internally using the new Core.

### 2.1 Create `hertzbeat-collector-runtime-embedded`
- **Action:** Create/Refactor a module for embedded usage.
- **Dependency:** `hertzbeat-collector-core`.

### 2.2 Refactor Manager Integration
- **Target:** `hertzbeat-manager`
- **Tasks:**
  - Replace direct injection of `CollectJobService` with `CollectorEngine`.
  - Initialize `CollectorEngine` manually or via a Spring Bean wrapper in Manager configuration.
  - Maintain `MAIN_COLLECTOR_NODE` logic for local dispatching.

## Phase 3: Quarkus Runtime Implementation
**Objective:** Create a standalone Quarkus application for the collector.

### 3.1 Create `hertzbeat-collector-runtime-quarkus` Module
- **Action:** Create a new Maven module.
- **Dependencies:** `hertzbeat-collector-core`, Quarkus BOM, Netty.

### 3.2 Implement Quarkus Application
- **Tasks:**
  - Create `QuarkusCollectorApp` with `@QuarkusMain`.
  - Map `application.yml` (existing structure) to Quarkus Config.
  - Implement Netty Client to connect to Manager (migrate `CollectServer`/`NettyClient`).
  - Initialize `CollectorEngine` with configuration.

### 3.3 Configuration Mapping
- Ensure the following properties map correctly:
  - `collector.dispatch.entrance.netty.enabled`
  - `collector.dispatch.entrance.netty.manager-host`
  - `collector.dispatch.entrance.netty.manager-port`
  - `collector.dispatch.entrance.netty.identity`

## Phase 4: Virtual Threads & Optimization
**Objective:** Leverage JDK 21 features.

### 4.1 Executor Abstraction
- **Task:** Abstract `WorkerPool` to use an injected `ExecutorService`.

### 4.2 Implementation
- **Quarkus:** Inject `Executors.newVirtualThreadPerTaskExecutor()`.
- **Embedded:** Allow configuration to switch between Platform Threads (legacy) and Virtual Threads.

## Phase 5: Verification & Compatibility
**Objective:** Ensure no regression.

### 5.1 Functional Testing
- Verify Manager starts up and collects metrics (Embedded mode).
- Verify Independent Collector connects to Manager and accepts jobs.
- Verify UI status reporting.

### 5.2 Performance Benchmarking
- Measure Startup Time (Quarkus vs Spring Boot).
- Measure Memory Footprint.

## Detailed Class & Responsibility Mapping

### 1. Collector Core (`hertzbeat-collector-core`)
**Target Package:** `org.apache.hertzbeat.collector.core`

| Old Class (in `collector-common`) | New Class (in `collector-core`) | Responsibility |
| :--- | :--- | :--- |
| `dispatch.entrance.internal.CollectJobService` | `engine.CollectJobService` | Core scheduling service. Remove `@Service`, `@Autowired`. |
| `dispatch.TimerDispatch` | `dispatch.TimerDispatch` | Timer-based job dispatching. |
| `dispatch.WorkerPool` | `dispatch.WorkerPool` | Thread pool management. |
| `dispatch.CollectDataDispatch` | `dispatch.CollectDataDispatch` | Data dispatching logic. |
| `dispatch.CommonExpression` | `dispatch.CommonExpression` | Expression evaluation logic. |
| `model.Job` | `model.Job` | Job data model. |
| `model.CollectRep` | `model.CollectRep` | Collection result model. |

### 2. Collector Engine Interface
**Location:** `org.apache.hertzbeat.collector.core.api.CollectorEngine`

```java
package org.apache.hertzbeat.collector.core.api;

import org.apache.hertzbeat.common.entity.job.Job;
import org.apache.hertzbeat.common.entity.message.CollectRep;
import java.util.List;

public interface CollectorEngine {
    void start();
    long submitAsync(Job job);
    List<CollectRep.MetricsData> collectOnce(Job job);
    void cancel(long jobId);
    void shutdown();
}
```

### 3. Quarkus Runtime (`hertzbeat-collector-runtime-quarkus`)
**Target Package:** `org.apache.hertzbeat.collector.runtime.quarkus`

| Component | Description |
| :--- | :--- |
| `QuarkusCollectorApp` | Main entry point (`@QuarkusMain`). |
| `QuarkusCollectorConfig` | Configuration mapping class (`@ConfigMapping(prefix = "collector")`). |
| `NettyConnectClient` | Netty client to connect to Manager (adapted from `CollectServer`). |

### 4. Configuration Mapping (Quarkus)
Map existing `application.yml` structure to Quarkus properties:

- `collector.dispatch.entrance.netty.enabled` -> `quarkus.collector.netty.enabled` (or keep existing)
- `collector.dispatch.entrance.netty.identity` -> `quarkus.collector.netty.identity`
- `collector.dispatch.entrance.netty.manager-host` -> `quarkus.collector.netty.manager-host`
- `collector.dispatch.entrance.netty.manager-port` -> `quarkus.collector.netty.manager-port`

**Note:** Use `@ConfigMapping(prefix = "collector.dispatch.entrance.netty")` to maintain exact compatibility with existing YAML.
