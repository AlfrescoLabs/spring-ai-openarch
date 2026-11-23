# Contributing

Thanks for your interest in this project. Issues and pull requests are welcome.

## Before you start

- For a bug, open an issue with the versions of Spring AI and Hyland OpenArch, the code or properties you use, and the error.
- For a new feature, open an issue first to agree on the design.

## Build and test

You need Java 17 or later, Maven and Docker.

```bash
# Unit tests only
mvn verify

# With the integration tests against a real engine
docker compose -f docker/compose.yaml up -d --wait
OPENARCH_URL=http://localhost:8080 mvn verify
docker compose -f docker/compose.yaml down
```

The end-to-end test also needs an OpenAI-compatible embedding endpoint that returns 1024-dimension vectors, for example `ai/mxbai-embed-large` on Docker Model Runner:

```bash
docker model pull ai/mxbai-embed-large
OPENARCH_URL=http://localhost:8080 MODEL_RUNNER_URL=http://localhost:12434/engines/v1 mvn verify
```

Set `OPENARCH_PORT` before `docker compose up` if port 8080 is in use, and use the same port in `OPENARCH_URL`.

## Pull requests

- One topic per pull request.
- Add or update tests for every change.
- Follow the code style of the existing files: tabs in Java, the Apache license header on new files.
- `mvn verify` must pass.

## Versions

The project follows semantic versioning (MAJOR.MINOR.PATCH).

## Code of conduct

This project follows the [Spring code of conduct](https://github.com/spring-projects/.github/blob/main/CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions are licensed under the Apache License 2.0.
