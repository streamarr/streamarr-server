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

# Intro

What is Streamarr? Streamarr is a modern open source media management solution.

Streamarr is currently in active development with the core functionality still a WIP. We are looking for contributors.

### Running in docker:

#### Step 1 - Ensure JAVA_HOME = JDK 17 (_MACOS example_)

`jenv local 17.0.3`

#### Step 2 - Build the docker image

`./mvnw spring-boot:build-image`

Resulting image from this build command will look like:

`docker.io/library/server:0.0.1-SNAPSHOT`

#### Step 3 - Start PG Docker Container:

`docker compose up -d`

### Running locally:

#### Step 1 - Ensure JAVA_HOME = JDK 17 (_MACOS example_)

`jenv local 17.0.3`

#### Step 2 - Start Streamarr Server

`./mvnw spring-boot:run`

## Attribution

<img src="https://www.themoviedb.org/assets/2/v4/logos/v2/blue_long_2-9665a76b1ae401a510ec1e0ca40ddcb3b0cfe45f1d51b77a308fea0845885571.svg" width="300" alt="TMDB logo">

This product uses the [TMDB API](https://www.themoviedb.org/) but is not endorsed or certified by TMDB.
