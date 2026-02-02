<p align="center">

<br />

<img src="https://github.com/streamarr/streamarr-ux/blob/main/branding/assets/streamarr-logo-text.svg" width="425" alt="Streamarr logo">

<br />
<br />
<br />

<a href="https://github.com/streamarr/streamarr-server/blob/main/LICENSE">
<img alt="GPL 3.0 License" src="https://img.shields.io/badge/license-GPL--3.0-orange.svg"/>
</a>

<a href="https://sonarcloud.io/summary/new_code?id=streamarr_streamarr-server">
<img alt="Code Coverage" src="https://sonarcloud.io/api/project_badges/measure?project=streamarr_streamarr-server&metric=coverage"/>
</a>

<a href="https://sonarcloud.io/summary/new_code?id=streamarr_streamarr-server">
<img alt="Security Rating" src="https://sonarcloud.io/api/project_badges/measure?project=streamarr_streamarr-server&metric=security_rating"/>
</a>

</p>

# Streamarr

Streamarr is an open-source media server that organizes your personal video library and streams it to your devices. It scans your filesystem, matches files against metadata providers (TMDB), and serves content via a GraphQL API designed for rich client experiences.

**Status:** Active development. Core scanning, metadata enrichment, and GraphQL API are functional. HLS transcoding and Series support are in progress.

## Tech Stack

Java 25, Spring Boot 4, PostgreSQL 18, Netflix DGS (GraphQL), jOOQ, Flyway, FFmpeg.

## Getting Started

See [Developer Setup](docs/dev-setup.adoc) for prerequisites, build instructions, and local development workflow.

## Architecture

See [Architecture Overview](docs/architecture.adoc) for how the system is structured, and [Architecture Decision Records](docs/adr/) for key design rationale.

## Contributing

See [Contributing](CONTRIBUTING.adoc) for the development process, commit conventions, and code style.

## Attribution

<img src="https://www.themoviedb.org/assets/2/v4/logos/v2/blue_long_2-9665a76b1ae401a510ec1e0ca40ddcb3b0cfe45f1d51b77a308fea0845885571.svg" width="300" alt="TMDB logo">

This product uses the [TMDB API](https://www.themoviedb.org/) but is not endorsed or certified by TMDB.
