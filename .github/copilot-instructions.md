# Copilot Instructions for HertzBeat

## Build, Test, and Lint

### Backend (Java/Spring Boot)
- **Root Directory**: `.`
- **Build**: `mvn clean install -DskipTests`
- **Run Tests**: `mvn test` (runs all tests). To run a single test class: `mvn -Dtest=ClassName test`
- **Start Application**: Run `org.apache.hertzbeat.startup.HertzBeatApplication` in `hertzbeat-startup` module.
  - **Critical**: You MUST add the following VM Options when running locally:
    `--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED`
- **Linting**: Project uses Checkstyle. Configuration is in `script/checkstyle/checkstyle.xml`.
- **Java Version**: 17
- **Key Libraries**: Lombok (annotations), Spring Boot 3, Sureness (auth).

### Frontend (Angular)
- **Root Directory**: `web-app/`
- **Install Dependencies**: `pnpm install` (Ensure Node.js >= 18)
- **Start Development Server**: `pnpm start` (Runs on `localhost:4200`, proxies to backend)
- **Build**: `pnpm run build`
- **Lint**: `npm run lint` (ESLint + Stylelint)
- **Test**: `ng test`

## High-Level Architecture

HertzBeat is a real-time monitoring system designed with a modular architecture:

- **hertzbeat-startup**: The entry point. Bundles all modules and starts the Spring Boot application.
- **hertzbeat-manager**: Core business logic, monitoring management, and resource scheduling.
- **hertzbeat-collector**: Responsible for collecting metrics from various protocols (HTTP, JDBC, SSH, SNMP, etc.).
  - Supports both **Agentless** (default) and **Agent** modes.
- **hertzbeat-alerter**: Handles alert evaluation, triggering, and notification dispatching.
- **hertzbeat-common**: Shared utilities, constants, and core data structures.
- **hertzbeat-push**: Module for pushing metrics to external systems.
- **hertzbeat-warehouse**: Data storage abstraction and implementation (supports multiple TSDBs).

## Key Conventions

- **Commit Messages**: Follow the pattern `[module-name] type: description`.
  - Example: `[manager] feature: support new monitoring protocol`
  - Types: `feature`, `bugfix`, `doc`, `style`, `refactor`, `test`.
- **API Documentation**: Uses SpringDoc/Swagger. API definitions are often in `Controller` classes.
- **Exception Handling**: Global exception handling is used. Custom exceptions should extend `HertzBeatException` or its subclasses.
- **Code Style**:
  - Use Lombok `@Data`, `@Builder`, `@Slf4j` to reduce boilerplate.
  - Constants should be placed in `hertzbeat-common` if shared.
  - Prefer English for code comments and naming, even though documentation supports Chinese.
- **Configuration**:
  - Backend config: `hertzbeat-startup/src/main/resources/application.yml`
  - Monitor definitions: Defined in YAML files (likely under `manager` or `collector` resources), allowing dynamic protocol expansion without code changes.

## Development Workflow

1.  **Frontend/Backend Separation**: You must run backend and frontend separately for full stack development.
2.  **Database**: Supports H2 (dev default), MySQL, PostgreSQL, etc. schema managed via Flyway.
3.  **Monitor Extension**: To add a new monitor type, often you only need to add a YAML definition, not write Java code (unless a new protocol is needed).
